package com.khjxiaogu.aiwuxia.chat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.BotCallback;
import com.khjxiaogu.aiwuxia.utils.BotCallbackPromise;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * NapCat（OneBot v11 正向 WebSocket）协议适配器。
 * <p>
 * 把全部 QQ/NapCat 协议细节收在这一层：
 * </p>
 * <ul>
 *   <li>连接、鉴权头与断线重连；</li>
 *   <li>OneBot 事件解析：群消息 / 私聊消息、消息段、回复引用、@ 列表；</li>
 *   <li>OneBot API 调用：发文本 / 图片 / 视频 / 语音、私聊、表情回应，以及 {@code echo} 回执；</li>
 *   <li>媒体读取（图片下载）。</li>
 * </ul>
 * <p>
 * 归一化后的消息通过 {@link ChatEventHandler} 交给控制器；本类不关心会话、AI 与发送顺序。
 * 发送方法的 {@code file} 参数直接对应 OneBot 的 {@code file} 字段，因此 http(s) 链接、data URL
 * 与 NapCat 本地文件名都可以传。
 * </p>
 */
public class NapcatAdapter implements ChatAdapter {
	/** OneBot API 回执超时（分钟），与 {@link BotCallbackPromise} 的计时单位一致。 */
	private static final int CALLBACK_TIMEOUT_MINUTES = 1;
	/** 机器人收到的消息上点的表情 id（与改造前的行为保持一致）。 */
	private static final String DEFAULT_EMOJI = "424";

	private final String url;
	private final WebSocketClient client;
	/** 昵称/头像缓存；没有数据目录时只存内存。 */
	private final ChatProfileCache profileCache;
	/** 串行化 WS 帧处理，保证事件按到达顺序回调。 */
	private final Object lock = new Object();
	/** echo -> 回执回调。 */
	private final Map<String, BotCallback> callBack = new ConcurrentHashMap<>();
	private volatile ChatEventHandler handler;

	/**
	 * @param hostOrUrl NapCat 正向 WS 地址，{@code host:port} 或完整的 {@code ws(s)://} 地址
	 * @param token     OneBot 访问令牌，为空表示不带鉴权头
	 */
	public NapcatAdapter(String hostOrUrl, String token) {
		this(hostOrUrl, token, null);
	}

	/**
	 * @param hostOrUrl  NapCat 正向 WS 地址
	 * @param token      OneBot 访问令牌
	 * @param dataFolder 运行数据目录（用于持久化昵称/头像缓存），可为 null
	 */
	public NapcatAdapter(String hostOrUrl, String token, File dataFolder) {
		String target = hostOrUrl == null ? "" : hostOrUrl.trim();
		if (target.isEmpty()) {
			throw new IllegalArgumentException("NapCat 地址为空：请用 -Dnapcat_url=host:port 指定");
		}
		if (!target.startsWith("ws://") && !target.startsWith("wss://")) {
			target = "ws://" + target;
		}
		this.url = target;
		Map<String, String> headers = new HashMap<>();
		if (token != null && !token.isEmpty()) {
			headers.put("Authorization", "Bearer " + token);
		}
		this.client = new NapcatClient(URI.create(target), headers);
		this.profileCache = new ChatProfileCache(dataFolder == null ? null
				: new File(new File(dataFolder, "config"), "profile-cache.json"));
	}

	@Override
	public String getName() {
		return "NapCat(OneBot v11)";
	}

	@Override
	public void setEventHandler(ChatEventHandler handler) {
		this.handler = handler;
	}

	@Override
	public void connect() throws Exception {
		if (client.isOpen()) {
			return;
		}
		if (!client.connectBlocking()) {
			throw new IOException("NapCat 连接失败：" + url);
		}
	}

	@Override
	public void close() {
		client.close();
		profileCache.close();
	}

	@Override
	public boolean isConnected() {
		return client.isOpen();
	}

	// ==================== 发送 ====================

	@Override
	public CompletableFuture<String> sendText(String chatId, String text) {
		JsonObject action = action("send_group_msg");
		JsonObject params = action.getAsJsonObject("params");
		setGroupId(params, chatId);
		JsonArray message = new JsonArray();
		message.add(textSegment(text));
		params.add("message", message);
		return call(action);
	}

	@Override
	public CompletableFuture<String> sendImage(String chatId, String file, String caption) {
		JsonObject action = action("send_group_msg");
		JsonObject params = action.getAsJsonObject("params");
		setGroupId(params, chatId);
		JsonArray message = new JsonArray();
		message.add(mediaSegment("image", file));
		if (caption != null && !caption.isEmpty()) {
			message.add(textSegment(caption));
		}
		params.add("message", message);
		return call(action);
	}

	@Override
	public CompletableFuture<String> sendVideo(String chatId, String file) {
		JsonObject action = action("send_group_msg");
		JsonObject params = action.getAsJsonObject("params");
		setGroupId(params, chatId);
		JsonArray message = new JsonArray();
		message.add(mediaSegment("video", file));
		params.add("message", message);
		return call(action);
	}

	@Override
	public CompletableFuture<String> sendRecord(String chatId, String file) {
		JsonObject action = action("send_group_msg");
		JsonObject params = action.getAsJsonObject("params");
		setGroupId(params, chatId);
		JsonArray message = new JsonArray();
		message.add(mediaSegment("record", file));
		params.add("message", message);
		return call(action);
	}

	@Override
	public void sendPrivateText(String userId, String text) throws Exception {
		if (text == null || text.isEmpty()) {
			return;
		}
		JsonObject action = action("send_private_msg");
		JsonObject params = action.getAsJsonObject("params");
		setUserId(params, userId);
		JsonArray message = new JsonArray();
		message.add(textSegment(text));
		params.add("message", message);
		client.send(action.toString());
	}

	@Override
	public CompletableFuture<String> sendPrivateMessage(String userId, String text) {
		JsonObject action = action("send_private_msg");
		JsonObject params = action.getAsJsonObject("params");
		setUserId(params, userId);
		JsonArray message = new JsonArray();
		message.add(textSegment(text));
		params.add("message", message);
		return call(action);
	}

	@Override
	public CompletableFuture<String> sendPrivateImage(String userId, String file, String caption) {
		return sendPrivateMedia(userId, "image", file, caption);
	}
	@Override
	public CompletableFuture<String> sendPrivateVideo(String userId, String file) {
		return sendPrivateMedia(userId, "video", file, null);
	}

	@Override
	public CompletableFuture<String> sendPrivateRecord(String userId, String file) {
		return sendPrivateMedia(userId, "record", file, null);
	}

	private CompletableFuture<String> sendPrivateMedia(String userId, String type, String file, String caption) {
		JsonObject action = action("send_private_msg");
		JsonObject params = action.getAsJsonObject("params");
		setUserId(params, userId);
		JsonArray message = new JsonArray();
		message.add(mediaSegment(type, file));
		if (caption != null && !caption.isEmpty()) {
			message.add(textSegment(caption));
		}
		params.add("message", message);
		return call(action);
	}

	@Override
	public boolean supportsFileSend() {
		return true;
	}

	@Override
	public CompletableFuture<String> sendFile(String chatId, String file, String name) {
		return uploadFile(chatId, true, file, name);
	}

	@Override
	public CompletableFuture<String> sendPrivateFile(String userId, String file, String name) {
		return uploadFile(userId, false, file, name);
	}

	/**
	 * 上传文件并发送。
	 * <p>
	 * OneBot v11 的 {@code upload_group_file} / {@code upload_private_file}：{@code file}
	 * 支持 http(s) 链接、{@code base64://} 与本地路径（由 NapCat 自行下载/解码），
	 * {@code name} 是上传后显示的文件名。下载与上传都发生在 NapCat 侧，本进程只等回执，
	 * 大文件不会被先读进内存。
	 * </p>
	 */
	private CompletableFuture<String> uploadFile(String target, boolean group, String file, String name) {
		if (file == null || file.isEmpty()) {
			CompletableFuture<String> failed = new CompletableFuture<>();
			failed.completeExceptionally(new IllegalArgumentException("文件引用为空"));
			return failed;
		}
		final String fileName = name == null || name.trim().isEmpty() ? "file" : name.trim();
		return CompletableFuture.supplyAsync(() -> {
			JsonObject action = action(group ? "upload_group_file" : "upload_private_file");
			JsonObject params = action.getAsJsonObject("params");
			if (group) {
				setGroupId(params, target);
			} else {
				setUserId(params, target);
			}
			params.addProperty("file", normalizeFileRef(file));
			params.addProperty("name", fileName);
			return action;
		}).thenCompose(this::callData).thenApply(data -> (String) null);
	}

	/** data URL 转成 OneBot 的 {@code base64://}；URL、base64:// 与本地路径原样下发。 */
	private static String normalizeFileRef(String file) {
		if (file.startsWith("data:")) {
			int comma = file.indexOf(',');
			if (comma > 0) {
				return "base64://" + file.substring(comma + 1);
			}
		}
		return file;
	}

	@Override
	public boolean supportsRecall() {
		return true;
	}

	@Override
	public CompletableFuture<Void> recallMessage(String chatId, String messageId) {
		return recall(messageId);
	}

	@Override
	public CompletableFuture<Void> recallPrivateMessage(String userId, String messageId) {
		return recall(messageId);
	}

	private CompletableFuture<Void> recall(String messageId) {
		if (messageId == null || messageId.isEmpty()) {
			CompletableFuture<Void> failed = new CompletableFuture<>();
			failed.completeExceptionally(new IllegalArgumentException("messageId 为空"));
			return failed;
		}
		JsonObject action = action("delete_msg");
		action.getAsJsonObject("params").addProperty("message_id", messageId);
		return callData(action).thenApply(data -> (Void) null);
	}

	@Override
	public void react(String messageId, String emoji) {
		if (messageId == null || messageId.isEmpty()) {
			return;
		}
		JsonObject action = action("set_msg_emoji_like");
		JsonObject params = action.getAsJsonObject("params");
		params.addProperty("message_id", messageId);
		params.addProperty("emoji_id", emoji == null || emoji.isEmpty() ? DEFAULT_EMOJI : emoji);
		params.addProperty("set", true);
		try {
			client.send(action.toString());
		} catch (Throwable t) {
			System.err.println("发送表情回应失败：" + t);
		}
	}

	@Override
	public byte[] fetchMedia(String ref) throws Exception {
		if (ref == null || ref.isEmpty()) {
			throw new IOException("媒体引用为空");
		}
		if (ref.startsWith("data:")) {
			int comma = ref.indexOf(',');
			if (comma < 0) {
				throw new IOException("data URL 格式错误");
			}
			return Base64.getDecoder().decode(ref.substring(comma + 1));
		}
		if (ref.startsWith("http://") || ref.startsWith("https://")) {
			try (InputStream in = FileUtil.fetch(ref)) {
				return FileUtil.readAll(in);
			}
		}
		// 协议侧的文件句柄：本地文件能直接读；NapCat 传进来的通常是 http 链接，走上面的分支
		File file = new File(ref);
		if (file.isFile()) {
			return FileUtil.readAll(file);
		}
		throw new IOException("无法读取媒体引用：" + ref);
	}

	// ==================== 用户资料 ====================

	@Override
	public CompletableFuture<String> getUserName(String userId) {
		if (userId == null || userId.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String cached = profileCache.userName(userId);
		if (cached != null && !cached.isEmpty()) {
			return CompletableFuture.completedFuture(cached);
		}
		JsonObject action = action("get_stranger_info");
		JsonObject params = action.getAsJsonObject("params");
		setUserId(params, userId);
		params.addProperty("no_cache", false);
		return callData(action).thenApply(data -> {
			String nickname = JsonConfig.getStringOrEmpty(data, "nickname");
			String name = nickname.isEmpty() ? JsonConfig.getStringOrEmpty(data, "remark") : nickname;
			if (!name.isEmpty()) {
				profileCache.rememberUser(userId, null, name, null);
			}
			return name.isEmpty() ? null : name;
		}).exceptionally(t -> null);
	}

	@Override
	public CompletableFuture<String> getGroupMemberName(String groupId, String memberId) {
		if (groupId == null || groupId.isEmpty() || memberId == null || memberId.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String memberName = profileCache.memberName(groupId, memberId);
		if (memberName == null || memberName.isEmpty()) {
			memberName = profileCache.userName(memberId);
		}
		final String cached = memberName;
		if (cached != null && !cached.isEmpty()) {
			return CompletableFuture.completedFuture(cached);
		}
		JsonObject action = action("get_group_member_info");
		JsonObject params = action.getAsJsonObject("params");
		setGroupId(params, groupId);
		setUserId(params, memberId);
		params.addProperty("no_cache", false);
		return callData(action).thenApply(data -> {
			String nickname = JsonConfig.getStringOrEmpty(data, "nickname");
			String card = JsonConfig.getStringOrEmpty(data, "card");
			String name = card.isEmpty() ? nickname : card;
			if (!name.isEmpty()) {
				profileCache.rememberMemberName(groupId, memberId, name);
			}
			if (!nickname.isEmpty()) {
				profileCache.rememberUser(memberId, null, nickname, null);
			}
			return name.isEmpty() ? cached : name;
		}).exceptionally(t -> cached);
	}

	@Override
	public CompletableFuture<byte[]> getUserAvatar(String userId) {
		if (userId == null || userId.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String cached = profileCache.userAvatar(userId);
		final String avatarUrl = cached == null || cached.isEmpty()
				? "https://q1.qlogo.cn/g?b=qq&nk=" + userId + "&s=640" : cached;
		return CompletableFuture.supplyAsync(() -> {
			byte[] data = fetchAvatarBytes(avatarUrl);
			if (data != null && data.length > 0) {
				profileCache.rememberUser(userId, null, null, avatarUrl);
			}
			return data;
		});
	}

	@Override
	public CompletableFuture<byte[]> getGroupAvatar(String groupId) {
		if (groupId == null || groupId.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String cached = profileCache.groupAvatar(groupId);
		final String avatarUrl = cached == null || cached.isEmpty()
				? "https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/640" : cached;
		return CompletableFuture.supplyAsync(() -> {
			byte[] data = fetchAvatarBytes(avatarUrl);
			if (data != null && data.length > 0) {
				profileCache.rememberGroup(groupId, null, avatarUrl);
			}
			return data;
		});
	}

	private static byte[] fetchAvatarBytes(String url) {
		try (InputStream in = FileUtil.fetch(url)) {
			return FileUtil.readAll(in);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 发一条 OneBot API 调用并等待回执，返回回执里的 {@code data}。
	 *
	 * @param action 已填好 action/params 的请求
	 * @return data future（协议没给 data 时为 null）
	 */
	private CompletableFuture<JsonObject> callData(JsonObject action) {
		CompletableFuture<JsonObject> result = new CompletableFuture<>();
		final String echo = UUID.randomUUID().toString();
		BotCallbackPromise promise = new BotCallbackPromise(CALLBACK_TIMEOUT_MINUTES);
		callBack.put(echo, promise.getBotCallback());
		action.addProperty("echo", echo);
		try {
			client.send(action.toString());
		} catch (Throwable t) {
			callBack.remove(echo);
			result.completeExceptionally(t);
			return result;
		}
		promise.getFuture().whenComplete((data, error) -> {
			// 回执已到或已超时，回调槽位都不再需要，避免超时后残留
			callBack.remove(echo);
			if (error != null) {
				result.completeExceptionally(error);
			} else {
				result.complete(data);
			}
		});
		return result;
	}

	/**
	 * 发一条 OneBot API 调用并等待回执，返回消息 id。
	 *
	 * @param action 已填好 action/params 的请求
	 * @return 消息 id 的 future（协议没给 message_id 时为 null）
	 */
	private CompletableFuture<String> call(JsonObject action) {
		return callData(action).thenApply(NapcatAdapter::messageIdOf);
	}

	/** 从回执 data 里取消息 id。 */
	private static String messageIdOf(JsonObject data) {
		if (data == null || !data.has("message_id") || data.get("message_id").isJsonNull()) {
			return null;
		}
		try {
			return data.get("message_id").getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static JsonObject action(String name) {
		JsonObject action = new JsonObject();
		action.addProperty("action", name);
		action.add("params", new JsonObject());
		return action;
	}

	private static JsonObject textSegment(String text) {
		JsonObject segment = new JsonObject();
		segment.addProperty("type", "text");
		JsonObject data = new JsonObject();
		data.addProperty("text", text == null ? "" : text);
		segment.add("data", data);
		return segment;
	}

	private static JsonObject mediaSegment(String type, String file) {
		JsonObject segment = new JsonObject();
		segment.addProperty("type", type);
		JsonObject data = new JsonObject();
		data.addProperty("file", normalizeMediaFile(type, file));
		segment.add("data", data);
		return segment;
	}

	/**
	 * OneBot v11 的图片/语音/视频文件字段用 {@code base64://}；data URL 需要转换，其它形式原样下发。
	 * <p>
	 * Codex 截图等内联图片是以 data URL 形式进入附件链路的，NapCat 对 {@code data:} 前缀支持不一致，
	 * 这里统一转成协议规定的 {@code base64://}。本地 H3 视频在对象存储没有公网地址时也会以
	 * data URL 形式下发，因此视频同样需要转换。
	 * </p>
	 */
	private static String normalizeMediaFile(String type, String file) {
		if (file == null || file.isEmpty() || !file.startsWith("data:")) {
			return file == null ? "" : file;
		}
		if (!"image".equals(type) && !"record".equals(type) && !"video".equals(type)) {
			return file;
		}
		int comma = file.indexOf(',');
		if (comma < 0) {
			return file;
		}
		String meta = file.substring(5, comma);
		if (!meta.contains(";base64")) {
			return file;
		}
		return "base64://" + file.substring(comma + 1);
	}

	/** 群 id 尽量按数字下发，非数字标识才退回字符串（兼容非 QQ 协议实现）。 */
	private static void setGroupId(JsonObject params, String chatId) {
		String id = chatId == null ? "" : chatId.trim();
		try {
			params.addProperty("group_id", Long.parseLong(id));
		} catch (RuntimeException e) {
			params.addProperty("group_id", id);
		}
	}

	private static void setUserId(JsonObject params, String userId) {
		String id = userId == null ? "" : userId.trim();
		try {
			params.addProperty("user_id", Long.parseLong(id));
		} catch (RuntimeException e) {
			params.addProperty("user_id", id);
		}
	}

	// ==================== 接收 ====================

	/** 处理一帧 WS 文本消息。 */
	private void handleFrame(String frame) {
		final JsonObject message;
		try {
			message = JsonParser.parseString(frame).getAsJsonObject();
		} catch (RuntimeException e) {
			// 不是 JSON 对象：忽略，不影响连接
			return;
		}
		synchronized (lock) {
			try {
				dispatch(message, frame);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	/** 分派一帧：OneBot API 回执 / 撤回通知 / 群消息 / 私聊消息。 */
	private void dispatch(JsonObject message, String frame) {
		if (message.has("status") && message.has("echo")) {
			dispatchResponse(message);
			return;
		}
		if ("notice".equals(JsonConfig.getStringOrEmpty(message, "post_type"))) {
			dispatchNotice(message);
			return;
		}
		if (!message.has("message_type") || message.get("message_type").isJsonNull()) {
			return;
		}
		String eventType = JsonConfig.getStringOrEmpty(message, "message_type");
		ChatEventHandler current = handler;
		if (current == null) {
			return;
		}
		if ("private".equals(eventType)) {
			// 忽略机器人自己发出的私聊事件，避免把自己的消息当成管理员答复
			if (isMessageSent(message)) {
				return;
			}
			current.onPrivateMessage(parsePrivateMessage(message));
			return;
		}
		if (!"group".equals(eventType)) {
			return;
		}
		System.out.println("received group message: " + frame);
		IncomingMessage parsed = parseGroupMessage(message);
		if (parsed != null) {
			current.onGroupMessage(parsed);
		}
	}

	/**
	 * 分派通知类事件：目前处理群/私聊撤回。
	 * <p>
	 * OneBot v11 的撤回事件是 {@code post_type=notice}，{@code notice_type=group_recall/friend_recall}，
	 * 携带 {@code message_id}；拿到后交给控制器决定是否从待处理缓冲区移除。
	 * </p>
	 */
	private void dispatchNotice(JsonObject message) {
		ChatEventHandler current = handler;
		if (current == null) {
			return;
		}
		String noticeType = JsonConfig.getStringOrEmpty(message, "notice_type");
		String messageId = JsonConfig.getStringOrEmpty(message, "message_id");
		if ("group_recall".equals(noticeType)) {
			current.onGroupRecall(JsonConfig.getStringOrEmpty(message, "group_id"), messageId,
					JsonConfig.getStringOrEmpty(message, "operator_id"), JsonConfig.getStringOrEmpty(message, "user_id"));
		} else if ("friend_recall".equals(noticeType)) {
			current.onPrivateRecall(JsonConfig.getStringOrEmpty(message, "user_id"), messageId);
		}
	}

	/** 分派 OneBot API 回执。 */
	private void dispatchResponse(JsonObject message) {		String echo = JsonConfig.getStringOrEmpty(message, "echo");
		if (echo.isEmpty()) {
			return;
		}
		BotCallback callback = callBack.remove(echo);
		if (callback == null) {
			return;
		}
		JsonElement data = message.get("data");
		JsonObject dataObject = data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
		int retcode = -1;
		if (message.has("retcode") && !message.get("retcode").isJsonNull()) {
			try {
				retcode = message.get("retcode").getAsInt();
			} catch (RuntimeException e) {
				retcode = -1;
			}
		}
		callback.call(retcode, dataObject);
	}

	/** 事件是否是机器人自己发出的消息。 */
	private static boolean isMessageSent(JsonObject message) {
		return "message_sent".equals(JsonConfig.getStringOrEmpty(message, "post_type"));
	}

	/**
	 * 解析群消息事件。
	 * <p>
	 * {@code message} 段数组里有 @ 对象、回复 id 和图片链接；{@code raw.elements}（NapCat 特有）
	 * 才有按显示顺序排列的文本/图片/引用元素。两者都解析，再统一成内容块。
	 * </p>
	 */
	private IncomingMessage parseGroupMessage(JsonObject message) {
		String chatId = JsonConfig.getStringOrEmpty(message, "group_id");
		if (chatId.isEmpty()) {
			return null;
		}
		String selfId = JsonConfig.getStringOrEmpty(message, "self_id");
		JsonObject sender = message.has("sender") && message.get("sender").isJsonObject()
				? message.getAsJsonObject("sender")
				: new JsonObject();
		String card = JsonConfig.getStringOrEmpty(sender, "card");
		String nickname = JsonConfig.getStringOrEmpty(sender, "nickname");
		String senderName = card.isEmpty() ? nickname : card;
		String senderId = JsonConfig.getStringOrEmpty(sender, "user_id");
		if (senderId.isEmpty()) {
			senderId = JsonConfig.getStringOrEmpty(message, "user_id");
		}
		String messageId = JsonConfig.getStringOrEmpty(message, "message_id");
		profileCache.rememberUser(senderId, null, nickname, null);
		profileCache.rememberMemberName(chatId, senderId, senderName);

		JsonArray segments = message.has("message") && message.get("message").isJsonArray()
				? message.getAsJsonArray("message")
				: null;
		String replyTo = null;
		Set<String> mentioned = new LinkedHashSet<>();
		Map<String, String> imageUrls = new HashMap<>();
		if (segments != null) {
			for (JsonElement element : segments) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject segment = element.getAsJsonObject();
				String type = JsonConfig.getStringOrEmpty(segment, "type");
				JsonObject data = segment.has("data") && segment.get("data").isJsonObject()
						? segment.getAsJsonObject("data")
						: new JsonObject();
				if ("image".equals(type)) {
					String file = JsonConfig.getStringOrEmpty(data, "file");
					String imageUrl = JsonConfig.getStringOrEmpty(data, "url");
					if (!file.isEmpty() && !imageUrl.isEmpty()) {
						imageUrls.put(file, imageUrl);
					}
				} else if ("at".equals(type)) {
					String qq = JsonConfig.getStringOrEmpty(data, "qq");
					// @全体（qq=all）不算 @ 机器人
					if (!qq.isEmpty() && !"all".equals(qq)) {
						mentioned.add(qq);
					}
				} else if ("reply".equals(type)) {
					String id = JsonConfig.getStringOrEmpty(data, "id");
					if (!id.isEmpty()) {
						replyTo = id;
					}
				}
			}
		}

		List<IncomingContent> contents = new ArrayList<>();
		JsonArray elements = null;
		if (message.has("raw") && message.get("raw").isJsonObject()) {
			JsonObject raw = message.getAsJsonObject("raw");
			if (raw.has("elements") && raw.get("elements").isJsonArray()) {
				elements = raw.getAsJsonArray("elements");
			}
		}
		if (elements != null) {
			for (JsonElement element : elements) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject melm = element.getAsJsonObject();
				JsonElement replyElement = melm.get("replyElement");
				if (replyElement != null && replyElement.isJsonObject()) {
					JsonObject reply = replyElement.getAsJsonObject();
					contents.add(new IncomingContent.QuoteBlock(replyTo, JsonConfig.getStringOrEmpty(reply, "senderUid"),
							quoteText(reply)));
					continue;
				}
				JsonElement textElement = melm.get("textElement");
				if (textElement != null && textElement.isJsonObject()) {
					contents.add(new IncomingContent.TextBlock(JsonConfig.getStringOrEmpty(textElement.getAsJsonObject(), "content")));
					continue;
				}
				JsonElement picElement = melm.get("picElement");
				if (picElement != null && picElement.isJsonObject()) {
					JsonObject pic = picElement.getAsJsonObject();
					String fileName = JsonConfig.getStringOrEmpty(pic, "fileName");
					String imageRef = imageUrls.get(fileName);
					if (imageRef == null) {
						imageRef = fileName;
					}
					contents.add(new IncomingContent.ImageBlock(imageRef, JsonConfig.getStringOrEmpty(pic, "summary")));
				}
			}
		} else if (segments != null) {
			// 没有 NapCat 的 raw.elements 时退回标准 OneBot 消息段
			for (JsonElement element : segments) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject segment = element.getAsJsonObject();
				String type = JsonConfig.getStringOrEmpty(segment, "type");
				JsonObject data = segment.has("data") && segment.get("data").isJsonObject()
						? segment.getAsJsonObject("data")
						: new JsonObject();
				if ("text".equals(type)) {
					contents.add(new IncomingContent.TextBlock(JsonConfig.getStringOrEmpty(data, "text")));
				} else if ("image".equals(type)) {
					String imageRef = JsonConfig.getStringOrEmpty(data, "url");
					if (imageRef.isEmpty()) {
						imageRef = JsonConfig.getStringOrEmpty(data, "file");
					}
					contents.add(new IncomingContent.ImageBlock(imageRef, JsonConfig.getStringOrEmpty(data, "summary")));
				}
			}
		}

		IncomingMessage.Builder builder = IncomingMessage.builder().chatId(chatId).selfId(selfId)
				.senderId(senderId).senderName(senderName).messageId(messageId).replyToMessageId(replyTo)
				.raw(message);
		for (String qq : mentioned) {
			builder.mentionedId(qq);
		}
		for (IncomingContent content : contents) {
			builder.content(content);
		}
		return builder.build();
	}

	/** 取出被引用消息里的文本（NapCat 的 {@code sourceMsgTextElems}）。 */
	private static String quoteText(JsonObject reply) {
		StringBuilder builder = new StringBuilder();
		JsonElement textElements = reply.get("sourceMsgTextElems");
		if (textElements != null && textElements.isJsonArray()) {
			for (JsonElement element : textElements.getAsJsonArray()) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonElement text = element.getAsJsonObject().get("textElemContent");
				if (text != null && text.isJsonPrimitive()) {
					builder.append(text.getAsString());
				}
			}
		}
		return builder.toString();
	}

	/** 解析私聊消息事件（只用来收集管理员答复）。 */
	private IncomingMessage parsePrivateMessage(JsonObject message) {
		JsonObject sender = message.has("sender") && message.get("sender").isJsonObject()
				? message.getAsJsonObject("sender")
				: new JsonObject();
		String senderId = JsonConfig.getStringOrEmpty(sender, "user_id");
		if (senderId.isEmpty()) {
			senderId = JsonConfig.getStringOrEmpty(message, "user_id");
		}
		String senderName = JsonConfig.getStringOrEmpty(sender, "card");
		if (senderName.isEmpty()) {
			senderName = JsonConfig.getStringOrEmpty(sender, "nickname");
		}
		profileCache.rememberUser(senderId, null, senderName, null);
		List<IncomingContent> contents = new ArrayList<>();
		if (message.has("message") && message.get("message").isJsonArray()) {
			for (JsonElement element : message.getAsJsonArray("message")) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject segment = element.getAsJsonObject();
				if (!"text".equals(JsonConfig.getStringOrEmpty(segment, "type"))) {
					continue;
				}
				if (segment.has("data") && segment.get("data").isJsonObject()) {
					String text = JsonConfig.getStringOrEmpty(segment.getAsJsonObject("data"), "text");
					if (!text.isEmpty()) {
						contents.add(new IncomingContent.TextBlock(text));
					}
				}
			}
		}
		IncomingMessage.Builder builder = IncomingMessage.builder().chatId(senderId)
				.selfId(JsonConfig.getStringOrEmpty(message, "self_id")).senderId(senderId).senderName(senderName)
				.messageId(JsonConfig.getStringOrEmpty(message, "message_id")).raw(message);
		if (contents.isEmpty()) {
			// 没有消息段时退回 raw_message，保证管理员短答复也能识别
			builder.content(new IncomingContent.TextBlock(JsonConfig.getStringOrEmpty(message, "raw_message")));
		} else {
			for (IncomingContent content : contents) {
				builder.content(content);
			}
		}
		return builder.build();
	}

	// ==================== JSON 取值 ====================

	// ==================== WebSocket ====================

	private final class NapcatClient extends WebSocketClient {
		NapcatClient(URI serverUri, Map<String, String> headers) {
			super(serverUri, headers);
		}

		@Override
		public void onOpen(ServerHandshake handshake) {
			ChatEventHandler current = handler;
			if (current != null) {
				try {
					current.onOpen();
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
		}

		@Override
		public void onMessage(String message) {
			handleFrame(message);
		}

		@Override
		public void onMessage(ByteBuffer bytes) {
			// NapCat 只发文本帧
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			ChatEventHandler current = handler;
			if (current != null) {
				try {
					current.onClose(code, reason, remote);
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
			if (remote) {
				try {
					reconnect();
				} catch (Exception e) {
					System.err.println("NapCat 重连失败：" + e);
				}
			}
		}

		@Override
		public void onError(Exception ex) {
			ChatEventHandler current = handler;
			if (current != null) {
				try {
					current.onError(ex);
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
			close(CloseFrame.NORMAL, String.valueOf(ex));
		}
	}
}
