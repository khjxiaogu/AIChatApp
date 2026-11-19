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
package com.khjxiaogu.aiwuxia.voice;

import java.io.File;
import java.net.URI;

import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
import com.khjxiaogu.webserver.annotations.Query;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;
import com.khjxiaogu.webserver.builder.OpenedWebServerCreater;
import com.khjxiaogu.webserver.web.AbstractServiceClass;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.DataIn;

/**
 * 本地模型网关：把本地模型部署通道暴露在聊天进程的回环端口上。
 * <p>
 * {@link LocalModelConnectionService} 不再尝试连接公网域名：默认直接连本机
 * {@code ws://127.0.0.1:}{@value #DEFAULT_PORT}{@value #DEPLOY_PATH}，由聊天进程
 * （{@code NapCatAIConnector}）在启动时监听。这样模型服务与聊天在同一台机器时：
 * </p>
 * <ul>
 *   <li>不需要公网域名、反向代理或 TLS；</li>
 *   <li>模型服务先启动、聊天后启动也能自动重连；</li>
 *   <li>工具通过 {@link LocalModel#lhs} 拿到同一批模型连接，链路和网页端完全一致。</li>
 * </ul>
 * <p>
 * 端口默认 {@value #DEFAULT_PORT}，可用 {@code -DlocalModelPort=...} 覆盖；网关只监听
 * {@link #HOST 回环地址}，不会把模型通道暴露到局域网或公网。需要把模型注册到别的机器时，
 * 仍然在模型服务一侧用 {@code -DlocalModelServer=host:port} 指定服务器。
 * </p>
 */
public final class LocalModelGateway implements AutoCloseable {

	/** 网关只监听回环地址。 */
	public static final String HOST = "127.0.0.1";
	/** 默认端口：避开聊天网页（8999）与模型回调服务（8998）。 */
	public static final int DEFAULT_PORT = 8997;
	/** 端口系统属性名，聊天进程与模型服务共用。 */
	public static final String PORT_PROPERTY = "localModelPort";
	/** 部署通道的完整路径（挂载在 {@code /aichat} 下）。 */
	public static final String DEPLOY_PATH = "/aichat/kh$localModelDeploy";

	/** 同一 JVM 内只保留一个网关。 */
	private static LocalModelGateway shared;

	private final int port;
	private final OpenedWebServerCreater server;

	private LocalModelGateway(int port, OpenedWebServerCreater server) {
		this.port = port;
		this.server = server;
	}

	/**
	 * 读取 {@value #PORT_PROPERTY}，非法值回退到 {@value #DEFAULT_PORT}。
	 *
	 * @return 本地模型网关端口
	 */
	public static int portFromProperty() {
		String raw = System.getProperty(PORT_PROPERTY, "").trim();
		if (raw.isEmpty()) {
			return DEFAULT_PORT;
		}
		try {
			int value = Integer.parseInt(raw);
			return value > 0 && value < 65536 ? value : DEFAULT_PORT;
		} catch (NumberFormatException ex) {
			return DEFAULT_PORT;
		}
	}

	/**
	 * 本机模型服务应连接的部署通道地址。
	 *
	 * @return {@code ws://127.0.0.1:<port>/aichat/kh$localModelDeploy}
	 */
	public static URI deployUri() {
		return URI.create("ws://" + HOST + ":" + portFromProperty() + DEPLOY_PATH);
	}

	/**
	 * 启动（或复用）本机模型网关。
	 * <p>
	 * 重复调用返回同一个实例；端口被占用时抛出异常，由调用方决定是否继续启动聊天。
	 * </p>
	 *
	 * @param dataFolder 运行数据目录（save/），用于 404 页面
	 * @return 已启动的网关
	 * @throws InterruptedException 绑定端口过程被中断
	 */
	public static synchronized LocalModelGateway start(File dataFolder) throws InterruptedException {
		int port = portFromProperty();
		if (shared != null && shared.port == port) {
			return shared;
		}
		if (shared != null) {
			shared.close();
		}
		OpenedWebServerCreater server = BasicWebServerBuilder.build()
				.createURIRoot()
				.createWrapper(new DeployService())
				.rule("/aichat")
				.complete()
				.complete()
				.setNotFound(new File(dataFolder, "404.html"))
				.compile()
				.serverHttp(HOST, port);
		LocalModelGateway gateway = new LocalModelGateway(port, server);
		shared = gateway;
		Runtime.getRuntime().addShutdownHook(new Thread(gateway::close, "local-model-gateway-shutdown"));
		System.out.println("[LocalModel] 本地模型部署通道已监听 ws://" + HOST + ":" + port + DEPLOY_PATH);
		return gateway;
	}

	/**
	 * 网关端口。
	 *
	 * @return 实际监听的端口
	 */
	public int getPort() {
		return port;
	}

	/**
	 * 网关的部署通道地址。
	 *
	 * @return 形如 {@code ws://127.0.0.1:8997/aichat/kh$localModelDeploy} 的地址
	 */
	public URI getDeployUri() {
		return URI.create("ws://" + HOST + ":" + port + DEPLOY_PATH);
	}

	/** 关闭网关；重复调用无副作用。 */
	@Override
	public synchronized void close() {
		if (shared == this) {
			shared = null;
		}
		try {
			server.close();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	/**
	 * 只提供本地模型部署通道的服务类。
	 * <p>
	 * 与 {@link VoiceModelLocalServer} 使用同一份 {@link LocalModel#lhs}，但不挂载本地模型终端
	 * 页面与音乐文件端点，避免网关顺带暴露文件服务。
	 * </p>
	 */
	public static final class DeployService extends AbstractServiceClass {

		/**
		 * WebSocket 端点：本地模型客户端在这里接入。
		 *
		 * @param req HTTP 请求
		 * @param res HTTP 响应（升级为 WebSocket）
		 */
		@HttpPath("/kh$localModelDeploy")
		public void deploy(Request req, Response res) {
			res.suscribeWebsocketEvents(LocalModel.lhs);
		}

		/**
		 * 本地模型产出回调端点（部分模型通过 HTTP 回传结果）。
		 *
		 * @param reqid 请求 ID
		 * @param type  数据格式
		 * @param data  结果字节
		 * @return 200 表示已接收
		 */
		@HttpPath("/kh$localModelDeployData")
		@Adapter
		@HttpMethod("POST")
		public ResultDTO data(@Query("reqid") String reqid, @Query("type") String type,
				@GetBy(DataIn.class) byte[] data) {
			LocalModel.lhs.onMessage(reqid, data, type);
			return new ResultDTO(200);
		}

		@Override
		public String getName() {
			return "LocalModelGateway";
		}
	}
}
