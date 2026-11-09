package com.khjxiaogu.aiwuxia.chat;

/**
 * 适配器向控制器回调的事件。
 * <p>
 * 适配器保证同一连接上的事件按到达顺序串行回调；回调运行在协议读线程上，因此实现里不要做
 * 长时间阻塞的操作（等待发送回执、模型调用、语音合成等应交给自己的工作线程），否则会卡住
 * 后续消息的接收。
 * </p>
 */
public interface ChatEventHandler {

	/** 连接建立。 */
	default void onOpen() {
	}

	/**
	 * 连接关闭。
	 *
	 * @param code   关闭码
	 * @param reason 关闭原因
	 * @param remote 是否由对端/网络导致（重连策略由适配器自己负责）
	 */
	default void onClose(int code, String reason, boolean remote) {
	}

	/** 连接异常。 */
	default void onError(Throwable error) {
	}

	/**
	 * 收到群消息。
	 *
	 * @param message 归一化后的消息
	 */
	void onGroupMessage(IncomingMessage message);

	/**
	 * 收到私聊消息（目前用于管理员答复工具审核）。
	 *
	 * @param message 归一化后的消息
	 */
	default void onPrivateMessage(IncomingMessage message) {
	}
}
