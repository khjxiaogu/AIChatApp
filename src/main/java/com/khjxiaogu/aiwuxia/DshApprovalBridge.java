/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Deepseek Harness 工具调用许可（approval）桥的 Java 端。
 * <p>
 * DSH 的审批 seam（{@code dsh-user-approval}）只在进程内分发 {@code approval/request}
 * 瀑布，官方 SDK 协议并不承载审批；因此连接器用 {@code --patch} 覆盖层往 sdk profile
 * 里插入一个很小的插件（见 {@code resources/dsh/approval-bridge.mjs}），由该插件通过
 * 本机回环 TCP 把审批请求转给本类，本类再交给 QQ 用户决定，最后把决定原路返回。
 * </p>
 * <p>
 * 协议：每行一个 JSON 对象。
 * </p>
 * <ul>
 * <li>插件 → 连接器：{@code {"type":"approval","token":"...","sessionId":"...",
 * "toolName":"...","reason":"...","callId":"..."}}</li>
 * <li>连接器 → 插件：{@code {"decision":"allowed-once"}} 或 {@code {"decision":"rejected"}}；
 * 不返回 {@code decision} 字段表示"交给后续应答者"（最终按拒绝关闭）。</li>
 * </ul>
 * <p>
 * 只监听 {@code 127.0.0.1} 的临时端口，并用一次性 token 校验来源，避免同机其它进程
 * 冒充 QSH 插件替用户批准操作。
 * </p>
 */
public class DshApprovalBridge implements Closeable {
	/** 允许本次操作。DSH 审批词汇里唯一代表"批准"的取值。 */
	public static final String ALLOW = "allowed-once";
	/** 拒绝本次操作。 */
	public static final String DENY = "rejected";

	/**
	 * 一次审批请求的内容。DSH 只会给出工具名、原因与调用 id，不携带工具参数。
	 */
	public static class ApprovalRequest {
		/** 发起请求的 DSH 会话 id，用于反查对应的 QQ 会话。 */
		public final String sessionId;
		/** 想要执行（或提权执行）的工具名。 */
		public final String toolName;
		/** DSH 给出的请求原因，可能为空。 */
		public final String reason;
		/** 工具调用 id，可能为空。 */
		public final String callId;

		public ApprovalRequest(String sessionId, String toolName, String reason, String callId) {
			this.sessionId = sessionId;
			this.toolName = toolName;
			this.reason = reason;
			this.callId = callId;
		}

		@Override
		public String toString() {
			return "ApprovalRequest [tool=" + toolName + ", reason=" + reason + ", session=" + sessionId + "]";
		}
	}

	/** 审批处理器：返回 {@link #ALLOW}/{@link #DENY}，返回 null 表示放弃作答。 */
	public interface Handler {
		String onApproval(ApprovalRequest request);
	}

	private final ServerSocket server;
	private final String token;
	private volatile Handler handler;
	private volatile boolean closed;
	private Thread acceptor;

	public DshApprovalBridge(Handler handler) throws IOException {
		this.handler = handler;
		// 端口交给系统分配，避免固定端口冲突
		this.server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
		this.token = newToken();
	}

	private static String newToken() {
		byte[] data = new byte[16];
		new SecureRandom().nextBytes(data);
		StringBuilder sb = new StringBuilder(data.length * 2);
		for (byte b : data)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}

	/** 供生成的 patch 文件使用的端口。 */
	public int getPort() {
		return server.getLocalPort();
	}

	/** 供生成的 patch 文件使用的共享 token。 */
	public String getToken() {
		return token;
	}

	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	/** 开始接受插件连接。 */
	public synchronized void start() {
		if (acceptor != null || closed)
			return;
		acceptor = new Thread(this::acceptLoop, "dsh-approval-bridge");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	private void acceptLoop() {
		while (!closed) {
			try {
				Socket socket = server.accept();
				Thread worker = new Thread(() -> serve(socket), "dsh-approval-conn");
				worker.setDaemon(true);
				worker.start();
			} catch (IOException e) {
				if (!closed)
					System.out.println("[DSH连接器] 审批桥接受连接失败：" + e);
				return;
			}
		}
	}

	/** 处理一次插件连接：读一行请求，交给处理器，写回一行决定。 */
	private void serve(Socket socket) {
		try (Socket s = socket) {
			s.setSoTimeout(0);
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			String line = reader.readLine();
			if (line == null || line.trim().isEmpty())
				return;
			JsonObject request;
			try {
				request = JsonParser.parseString(line).getAsJsonObject();
			} catch (RuntimeException e) {
				return;
			}
			if (!token.equals(request.has("token") ? request.get("token").getAsString() : ""))
				return;
			Handler current = handler;
			if (current == null)
				return;
			String decision = current.onApproval(new ApprovalRequest(
					request.has("sessionId") ? request.get("sessionId").getAsString() : "",
					request.has("toolName") ? request.get("toolName").getAsString() : "",
					request.has("reason") ? request.get("reason").getAsString() : "",
					request.has("callId") ? request.get("callId").getAsString() : ""));
			if (decision == null)
				return;
			JsonObject answer = new JsonObject();
			answer.addProperty("decision", decision);
			Writer writer = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
			writer.write(answer.toString());
			writer.write("\n");
			writer.flush();
		} catch (IOException e) {
			if (!closed)
				System.out.println("[DSH连接器] 审批桥通信失败：" + e);
		} catch (RuntimeException e) {
			System.out.println("[DSH连接器] 审批桥处理失败：" + e);
		}
	}

	@Override
	public void close() {
		closed = true;
		try {
			server.close();
		} catch (IOException e) {
			// 关闭失败无需处理
		}
	}
}
