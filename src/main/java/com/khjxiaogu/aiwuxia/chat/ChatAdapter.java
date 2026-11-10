package com.khjxiaogu.aiwuxia.chat;

import java.util.concurrent.CompletableFuture;

/**
 * 聊天协议适配器：把某一种聊天平台的连接与收发归一化成通用消息。
 * <p>
 * 控制器 {@code NapCatAIConnector} 只依赖本接口，不接触任何协议细节；要接入别的平台，
 * 实现本接口并把它传给控制器即可。当前实现是 {@link NapcatAdapter}（NapCat / OneBot v11）。
 * </p>
 * <p>
 * 约定：
 * </p>
 * <ul>
 *   <li>入站事件通过 {@link #setEventHandler(ChatEventHandler)} 注册的处理器回调，
 *       同一连接上的事件串行到达；</li>
 *   <li>{@code chatId} 是控制器用来匹配会话的标识：群消息为群 id，私聊为对方用户 id，统一用字符串。
 *       控制器直接把它与 {@code AIGroupSession.groupId}（同样是字符串）比较，所以适配器给出的
 *       群 {@code chatId} 应当与配置里的群 id 一致；</li>
 *   <li>发送方法里的 {@code file} 是“适配器能识别的媒体引用”，可能是 http(s) 链接、data URL、
 *       本地文件或协议内部句柄，由适配器自行解释；</li>
 *   <li>发送方法不阻塞，返回的 future 在协议回执到达后以消息 id 完成；失败时异常完成，
 *       协议不提供消息 id 时以 null 完成。</li>
 * </ul>
 */
public interface ChatAdapter {

	/** 适配器显示名，用于日志。 */
	default String getName() {
		return getClass().getSimpleName();
	}

	/** 注册事件处理器，必须在 {@link #connect()} 之前调用。 */
	void setEventHandler(ChatEventHandler handler);

	/** 建立连接（阻塞到连接可用或失败）。 */
	void connect() throws Exception;

	/** 关闭连接。 */
	void close();

	/** 当前连接是否可用。 */
	boolean isConnected();

	/**
	 * 发送群/会话文本消息。
	 *
	 * @param chatId 会话标识（与 {@code AIGroupSession.groupId} 的字符串形式对应）
	 * @param text   文本
	 * @return 消息 id 的 future
	 */
	CompletableFuture<String> sendText(String chatId, String text);

	/**
	 * 发送图片消息。
	 *
	 * @param chatId  会话标识
	 * @param file    图片引用
	 * @param caption 图片后附带的文字，可为 null
	 * @return 消息 id 的 future
	 */
	CompletableFuture<String> sendImage(String chatId, String file, String caption);

	/**
	 * 发送视频消息。
	 *
	 * @param chatId 会话标识
	 * @param file   视频引用
	 * @return 消息 id 的 future
	 */
	CompletableFuture<String> sendVideo(String chatId, String file);

	/**
	 * 发送语音消息。
	 *
	 * @param chatId 会话标识
	 * @param file   音频引用
	 * @return 消息 id 的 future
	 */
	CompletableFuture<String> sendRecord(String chatId, String file);

	/**
	 * 发送私聊文本消息（不等待回执）。
	 * <p>
	 * 工具审核的问题和结果都通过私聊发给管理员。
	 * </p>
	 *
	 * @param userId 用户 id
	 * @param text   文本
	 * @throws Exception 发送失败
	 */
	void sendPrivateText(String userId, String text) throws Exception;

	/**
	 * 对一条收到的消息做出回应（表情回应/点赞等）。
	 * <p>
	 * 这是可选能力：协议不支持时实现应静默忽略，不要抛异常。
	 * </p>
	 *
	 * @param messageId 收到的消息 id
	 * @param emoji     回应内容（表情 id 或文本）
	 */
	default void react(String messageId, String emoji) {
	}

	/**
	 * 读取媒体（URL / data URL / 协议句柄）的原始字节，用于把入站图片转存到对象存储。
	 *
	 * @param ref 媒体引用
	 * @return 媒体字节
	 * @throws Exception 读取失败
	 */
	byte[] fetchMedia(String ref) throws Exception;
}
