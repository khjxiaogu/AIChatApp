package com.khjxiaogu.aiwuxia.chat;

import com.google.gson.JsonObject;

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

	/**
	 * 群消息被撤回。
	 *
	 * @param groupId    群 id
	 * @param messageId  被撤回的消息 id
	 * @param operatorId 操作者 id（可能是群管理/群主，可能为空）
	 * @param senderId   原消息发送者 id（可能为空）
	 */
	default void onGroupRecall(String groupId, String messageId, String operatorId, String senderId) {
	}

	/**
	 * 私聊消息被撤回。
	 *
	 * @param userId    私聊对象 id
	 * @param messageId 被撤回的消息 id
	 */
	default void onPrivateRecall(String userId, String messageId) {
	}

	/**
	 * 协议系统事件（入群/退群、好友添加/删除等）。
	 * <p>
	 * 不同协议能提供的事件不同；实现方按事件名自行判断。事件体是协议原始 JSON，
	 * 便于工具类（例如 openid 采集器）读取协议字段。
	 * </p>
	 *
	 * @param event 事件名，例如 {@code GROUP_ADD_ROBOT}、{@code FRIEND_ADD}
	 * @param data  原始事件体
	 */
	default void onSystemEvent(String event, JsonObject data) {
	}

	/**
	 * 用户点击消息按钮或快捷菜单。
	 * <p>
	 * 只包含按钮互动；适配器已经回应过平台，处理器只需处理业务。
	 * </p>
	 *
	 * @param interaction 互动数据
	 */
	default void onInteraction(ChatInteraction interaction) {
	}
}
