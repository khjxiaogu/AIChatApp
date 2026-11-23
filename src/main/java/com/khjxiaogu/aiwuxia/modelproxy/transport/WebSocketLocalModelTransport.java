package com.khjxiaogu.aiwuxia.modelproxy.transport;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * 基于 WebSocket 的传输实现。
 * <p>
 * 只处理连接与报文收发，收到的文本报文原样交给 {@link LocalModelTransport.Listener}；
 * 业务逻辑（任务分发、进度上报、重连策略、健康检查）全部在
 * {@link com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient} 中。
 * </p>
 */
public class WebSocketLocalModelTransport extends WebSocketClient implements LocalModelTransport {

	/** 事件监听器，建连前由上层设置。 */
	private volatile Listener listener;

	/**
	 * @param serverUri 服务器地址（已带 {@code model_type} 等查询参数）
	 * @param headers   HTTP 头，可为 null
	 */
	public WebSocketLocalModelTransport(URI serverUri, Map<String, String> headers) {
		super(serverUri, headers == null ? new HashMap<>() : new HashMap<>(headers));
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public void onOpen(ServerHandshake handshake) {
		Listener current = listener;
		if (current != null) {
			current.onOpen();
		}
	}

	@Override
	public void onMessage(String message) {
		Listener current = listener;
		if (current != null) {
			current.onMessage(message);
		}
	}

	@Override
	public void sendBinary(byte[] message) {
		if (message == null) {
			return;
		}
		if (!isOpen()) {
			// 这里以前是静默丢弃：结果是二进制分块时服务端只能一直等到超时，日志里什么都看不到
			System.err.println("[LocalModelClient] 连接未打开，二进制结果未发送（" + message.length + "B）");
			return;
		}
		send(message);
	}

	@Override
	public void onMessage(ByteBuffer bytes) {
		// 文件分块走二进制帧；复制一份再交给监听器，避免 Netty 缓冲被回收
		Listener current = listener;
		if (current == null || bytes == null) {
			return;
		}
		ByteBuffer copy = bytes.slice();
		byte[] data = new byte[copy.remaining()];
		copy.get(data);
		current.onBinary(data);
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
		Listener current = listener;
		if (current != null) {
			current.onClose(code, reason, remote);
		}
	}

	@Override
	public void onError(Exception error) {
		Listener current = listener;
		if (current != null) {
			current.onError(error);
		}
	}

	@Override
	public URI getUri() {
		return getURI();
	}
}
