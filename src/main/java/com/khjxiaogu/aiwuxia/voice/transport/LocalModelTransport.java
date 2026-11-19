package com.khjxiaogu.aiwuxia.voice.transport;

import java.net.URI;

/**
 * 本地模型服务的网络传输抽象（SPI）。
 * <p>
 * 只负责“把一条文本报文送出去 / 收进来”，不关心报文语义、模型类型、任务排队与进度协商：
 * </p>
 * <ul>
 *   <li>连接生命周期（建立、断开、是否在线）；</li>
 *   <li>文本报文的发送与接收；</li>
 *   <li>通过 {@link Listener} 把连接事件回调给上层。</li>
 * </ul>
 * <p>
 * 应用层协议见 {@link com.khjxiaogu.aiwuxia.voice.protocol.LocalModelProtocol}；默认实现是
 * {@link WebSocketLocalModelTransport}，新增其它协议（TCP 自定义帧、HTTP 长轮询、stdio、本地 IPC 等）
 * 只需实现本接口并通过 {@link LocalModelTransportFactory} 注入即可，业务层无需改动。
 * </p>
 */
public interface LocalModelTransport {

	/**
	 * 连接事件监听器。
	 */
	interface Listener {
		/** 连接建立。 */
		void onOpen();

		/**
		 * 收到一条文本报文。
		 *
		 * @param message 报文内容
		 */
		void onMessage(String message);

		/**
		 * 收到一条二进制报文。
		 *
		 * @param message 报文内容
		 */
		void onBinary(byte[] message);

		/**
		 * 连接关闭。
		 *
		 * @param code   关闭码
		 * @param reason 原因
		 * @param remote 是否由对端发起
		 */
		void onClose(int code, String reason, boolean remote);

		/**
		 * 连接异常。
		 *
		 * @param error 异常
		 */
		void onError(Throwable error);
	}

	/**
	 * 设置事件监听器；应在 {@link #connectBlocking()} 之前调用。
	 *
	 * @param listener 监听器，可为 null
	 */
	void setListener(Listener listener);

	/**
	 * 阻塞建立连接。
	 *
	 * @return 是否成功
	 * @throws InterruptedException 建连过程被中断
	 */
	boolean connectBlocking() throws InterruptedException;

	/**
	 * 当前连接是否可用。
	 *
	 * @return true 表示已连接
	 */
	boolean isOpen();

	/**
	 * 发送一条文本报文；未连接时静默丢弃。
	 *
	 * @param message 报文内容
	 */
	void send(String message);

	/**
	 * 发送一帧二进制报文；未连接时静默丢弃。
	 *
	 * @param message 报文内容
	 */
	void sendBinary(byte[] message);

	/** 关闭连接；重复调用无副作用。 */
	void close();

	/**
	 * 连接的完整地址。
	 *
	 * @return 地址
	 */
	URI getUri();
}
