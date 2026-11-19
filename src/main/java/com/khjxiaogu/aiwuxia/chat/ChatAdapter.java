package com.khjxiaogu.aiwuxia.chat;

import java.util.concurrent.CompletableFuture;

/**
 * 聊天协议适配器：把某一种聊天平台的连接与收发归一化成通用消息。
 * <p>
 * 控制器 {@code NapCatAIConnector} 只依赖本接口，不接触任何协议细节；要接入别的平台，
 * 实现本接口并把它传给控制器即可。内置实现：
 * {@link NapcatAdapter}（NapCat / OneBot v11）与
 * {@link QqOfficialAdapter}（QQ 官方机器人 API v2）。
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
	 * 发私聊图片。
	 * <p>
	 * 默认退回 {@link #sendImage(String, String, String)}；支持区分群/私聊的适配器应覆盖，
	 * 否则像 {@code private:<openid>} 这样的会话 id 可能被误当成群。
	 * </p>
	 *
	 * @param userId  私聊用户 id
	 * @param file    图片引用
	 * @param caption 说明文字，可为 null
	 * @return 消息 id future
	 */
	default CompletableFuture<String> sendPrivateImage(String userId, String file, String caption) {
		return sendImage(userId, file, caption);
	}

	/** 发私聊视频；默认退回 {@link #sendVideo}。 */
	default CompletableFuture<String> sendPrivateVideo(String userId, String file) {
		return sendVideo(userId, file);
	}

	/** 发私聊语音；默认退回 {@link #sendRecord}。 */
	default CompletableFuture<String> sendPrivateRecord(String userId, String file) {
		return sendRecord(userId, file);
	}

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
	 * 发送私聊文本并返回消息 id，供撤回等后续操作使用。
	 * <p>
	 * 默认实现调用 {@link #sendPrivateText(String, String)}，返回 null（协议不回执）；
	 * 支持消息 id 的适配器应覆盖。
	 * </p>
	 *
	 * @param userId 用户 id
	 * @param text   文本
	 * @return 消息 id future
	 */
	default CompletableFuture<String> sendPrivateMessage(String userId, String text) {
		try {
			sendPrivateText(userId, text);
			return CompletableFuture.completedFuture(null);
		} catch (Throwable t) {
			CompletableFuture<String> failed = new CompletableFuture<>();
			failed.completeExceptionally(t);
			return failed;
		}
	}

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

	/**
	 * 查询用户的全局昵称。
	 * <p>
	 * 这是可选能力：协议不支持或查不到时以 {@code null} 完成，不要抛异常。
	 * </p>
	 *
	 * @param userId 用户 id（QQ 号 / openid）
	 * @return 昵称 future
	 */
	default CompletableFuture<String> getUserName(String userId) {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * 查询用户在指定群里的显示名。
	 * <p>
	 * 优先群名片/群昵称，没有时退回昵称；协议不支持或查不到时以 {@code null} 完成。
	 * </p>
	 *
	 * @param groupId  群 id
	 * @param memberId 群成员 id
	 * @return 显示名 future
	 */
	default CompletableFuture<String> getGroupMemberName(String groupId, String memberId) {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * 查询用户显示名：{@code groupId} 非空时优先群内显示名，再退回全局昵称。
	 *
	 * @param userId  用户 id
	 * @param groupId 群 id，可为 null/空
	 * @return 显示名 future；都查不到时为 null
	 */
	default CompletableFuture<String> getDisplayName(String userId, String groupId) {
		if (groupId == null || groupId.isEmpty()) {
			return getUserName(userId).exceptionally(t -> null);
		}
		return getGroupMemberName(groupId, userId).exceptionally(t -> null).thenCompose(name -> {
			if (name != null && !name.isEmpty()) {
				return CompletableFuture.completedFuture(name);
			}
			return getUserName(userId).exceptionally(t -> null);
		});
	}

	/**
	 * 获取用户头像文件。
	 * <p>
	 * 可选能力：协议不支持、没有头像或下载失败时以 {@code null} 完成。
	 * </p>
	 *
	 * @param userId 用户 id
	 * @return 头像字节 future
	 */
	default CompletableFuture<byte[]> getUserAvatar(String userId) {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * 获取群头像文件。
	 * <p>
	 * 可选能力：协议不支持、没有头像或下载失败时以 {@code null} 完成。
	 * </p>
	 *
	 * @param groupId 群 id
	 * @return 头像字节 future
	 */
	default CompletableFuture<byte[]> getGroupAvatar(String groupId) {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * 是否支持带按钮的卡片消息。
	 * <p>
	 * 调用方应先检查该值；不支持时退回普通文本。
	 * </p>
	 *
	 * @return true 表示 {@link #sendPrivateCard}/{@link #sendGroupCard} 可用
	 */
	default boolean supportsCard() {
		return false;
	}

	/**
	 * 发一张带按钮的私聊卡片。
	 * <p>
	 * 只有 {@link #supportsCard()} 为 true 时调用；失败时应退回文本。
	 * </p>
	 *
	 * @param userId   接收者 id
	 * @param markdown 卡片正文（Markdown）
	 * @param buttons  按钮列表
	 * @return 消息 id future
	 */
	default CompletableFuture<String> sendPrivateCard(String userId, String markdown,
			java.util.List<ChatButton> buttons) {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * 发一张带按钮的群聊卡片。
	 *
	 * @param groupId  群 id
	 * @param markdown 卡片正文（Markdown）
	 * @param buttons  按钮列表
	 * @return 消息 id future
	 */
	default CompletableFuture<String> sendGroupCard(String groupId, String markdown,
			java.util.List<ChatButton> buttons) {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * 是否支持撤回机器人自己发送的消息。
	 *
	 * @return true 表示 {@link #recallMessage}/{@link #recallPrivateMessage} 可用
	 */
	default boolean supportsRecall() {
		return false;
	}

	/**
	 * 撤回一条消息（群聊）。
	 * <p>
	 * 只能撤回机器人自己发送、且未超过协议时限的消息。默认不支持。
	 * </p>
	 *
	 * @param chatId    群 id
	 * @param messageId 消息 id
	 * @return 完成 future；失败时异常带协议原因
	 */
	default CompletableFuture<Void> recallMessage(String chatId, String messageId) {
		CompletableFuture<Void> failed = new CompletableFuture<>();
		failed.completeExceptionally(new UnsupportedOperationException("当前协议不支持撤回消息"));
		return failed;
	}

	/**
	 * 撤回一条私聊消息（机器人发给该用户的消息）。
	 *
	 * @param userId    用户 id
	 * @param messageId 消息 id
	 * @return 完成 future
	 */
	default CompletableFuture<Void> recallPrivateMessage(String userId, String messageId) {
		return recallMessage(userId, messageId);
	}
}
