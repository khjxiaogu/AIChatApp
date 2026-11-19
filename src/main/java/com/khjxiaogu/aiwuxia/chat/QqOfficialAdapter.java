package com.khjxiaogu.aiwuxia.chat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.objectstorage.DelegatingStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * QQ 官方机器人（QQ 开放平台 API v2）协议适配器。
 * <p>
 * 按官方文档实现：
 * </p>
 * <ul>
 *   <li>鉴权：{@code POST https://api.bot.qq.com/app/getAppAccessToken} 用 AppID + ClientSecret
 *       换取 access_token，请求头 {@code Authorization: QQBot {access_token}}；</li>
 *   <li>事件：{@code GET /gateway} 获取 WSS 地址，WebSocket 上发送 OpCode 2 Identify
 *       （intents 默认 {@code GROUP_AND_C2C_EVENT | INTERACTION}），处理 Hello / Heartbeat ACK /
 *       Dispatch / Reconnect / Invalid Session，断线后用 OpCode 6 Resume 补事件；</li>
 *   <li>接收：{@code GROUP_AT_MESSAGE_CREATE}、{@code GROUP_MESSAGE_CREATE}、
 *       {@code C2C_MESSAGE_CREATE}，把正文、@ 列表、引用（{@code ref_msg_idx}）、附件统一成
 *       {@link IncomingMessage}；</li>
 *   <li>发送：{@code POST /v2/groups/{group_openid}/messages} 与
 *       {@code POST /v2/users/{user_openid}/messages}，被动回复自动带上触发消息的
 *       {@code msg_id} 并递增 {@code msg_seq}；图片/视频/语音先上传
 *       {@code /v2/groups|users/{openid}/files} 取 {@code file_info}，再用 {@code msg_type=7}
 *       发送。</li>
 * </ul>
 * <p>
 * 配置约定：
 * </p>
 * <ul>
 *   <li>{@code appId} / {@code clientSecret} 来自 QQ 开放平台；</li>
 *   <li>agent 配置里的 {@code groupId} 填群的 {@code group_openid}（32 位字符串），
 *       {@code botId} 填 AppID，{@code adminId} 填管理员的用户 openid；</li>
 *   <li>可用系统属性覆盖：{@code -Dqqbot_appid}、{@code -Dqqbot_secret}、
 *       {@code -Dqqbot_api_base}、{@code -Dqqbot_token_url}、{@code -Dqqbot_intents}。</li>
 * </ul>
 * <p>
 * 已知限制（受平台能力约束）：
 * </p>
 * <ul>
 *   <li>QQ 官方 API 没有表情回应接口，{@link #react(String, String)} 是空实现；</li>
 *   <li>群聊被动回复窗口为 5 分钟、最多 5 次；超时后只能按主动消息发送，可能被用户设置拒绝；</li>
 *   <li>二进制直传上限 5MB；超过时如果配置了远程对象存储（{@code save/config/object-storage.json}），
 *       会先转存对象存储再按公网 URL 发送，否则要求调用方直接给 http(s) 链接；</li>
 *   <li>C2C 私聊只用于工具审核等私聊通知；控制器里按数字账号工作的一对一私聊 agent 模式
 *       需要 openid 化后才能使用。</li>
 * </ul>
 */
public class QqOfficialAdapter implements ChatAdapter {
	/** 官方 OpenAPI 默认地址。 */
	private static final String DEFAULT_API_BASE = "https://api.bot.qq.com";
	/** GROUP_AND_C2C_EVENT 的 intents 位。 */
	public static final long INTENT_GROUP_AND_C2C = 1L << 25;
	/** INTERACTION 的 intents 位（消息按钮/快捷菜单回调）。 */
	public static final long INTENT_INTERACTION = 1L << 26;
	/** 默认订阅：群/单聊消息 + 互动事件。 */
	public static final long DEFAULT_INTENTS = INTENT_GROUP_AND_C2C | INTENT_INTERACTION;
	/** 群聊被动回复有效期（官方文档：5 分钟）。 */
	private static final long GROUP_PASSIVE_TTL_MS = 5 * 60_000L;
	/** 单聊被动回复有效期（官方文档：60 分钟）。 */
	private static final long C2C_PASSIVE_TTL_MS = 60 * 60_000L;
	/** 群聊同一条消息最多被动回复次数。 */
	private static final int GROUP_PASSIVE_MAX_REPLIES = 5;
	/** 单聊同一条消息最多被动回复次数。 */
	private static final int C2C_PASSIVE_MAX_REPLIES = 4;
	/** 直传二进制上限（官方文档：5MB）。 */
	private static final long MAX_INLINE_MEDIA_BYTES = 5L * 1024 * 1024;
	/** 文件类型：图片。 */
	public static final int FILE_TYPE_IMAGE = 1;
	/** 文件类型：视频。 */
	public static final int FILE_TYPE_VIDEO = 2;
	/** 文件类型：语音。 */
	public static final int FILE_TYPE_VOICE = 3;
	/** 文件类型：普通文件。 */
	public static final int FILE_TYPE_FILE = 4;
	/** 网关鉴权等待上限。 */
	private static final long READY_TIMEOUT_SECONDS = 30L;

	private final String appId;
	private final String clientSecret;
	private final String apiBase;
	private final String tokenUrl;
	private final long intents;
	/** 运行数据目录：用于按 {@code save/config/object-storage.json} 初始化对象存储；可为 null。 */
	private final File dataFolder;

	private final ExecutorService apiExecutor = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "qqbot-http");
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "qqbot-gateway");
		// 必须是非守护线程：网关断开后它负责等待并重连；如果它是 daemon，
		// 主线程返回/唯一连接线程退出时 JVM 会直接结束，2 秒后的重连不会执行。
		t.setDaemon(false);
		return t;
	});
	private final Object tokenLock = new Object();
	private final Object connectLock = new Object();
	private final Object eventLock = new Object();
	private final Object refsLock = new Object();
	private final Object storageLock = new Object();
	/**
	 * 当前线程是否是网关读线程。
	 * <p>
	 * 在网关读线程上禁止同步 HTTP：工具审核的答复会在处理私聊事件时立即回发，如果在这里同步
	 * 发请求，网络一慢就会卡住后续事件（以及关闭帧）的接收。
	 * </p>
	 */
	private final ThreadLocal<Boolean> gatewayThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
	/** 自己发出消息的引用索引（ext_info.ref_idx），用于识别“用户回复了机器人”。 */
	private final Deque<String> ownMessageRefs = new ArrayDeque<>();
	/** 收到过群消息的 group_openid。 */
	private final Set<String> groupChats = ConcurrentHashMap.newKeySet();
	/** 收到过单聊消息（或显式发过私聊）的 user_openid。 */
	private final Set<String> userChats = ConcurrentHashMap.newKeySet();
	/** 每个会话最近一条入站消息，用于被动回复。 */
	private final Map<String, PassiveReply> passiveReplies = new ConcurrentHashMap<>();
	/** 事件里出现过的用户名/头像/群头像，持久化到 save/config/profile-cache.json。 */
	private final ChatProfileCache profileCache;
	/** 群成员 -> 群，作为用户资料接口的查询提示。 */
	private final Map<String, String> memberGroupHints = new ConcurrentHashMap<>();
	/** 群成员信息接口没有权限时置位，避免反复请求。 */
	private volatile boolean memberInfoDenied;
	/** 群信息接口没有权限时置位，避免反复请求。 */
	private volatile boolean groupInfoDenied;
	/** 已处理过的消息 id：官方文档说明同一 msg_id 可能重复推送。 */
	private final Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();
	private final Deque<String> seenMessageOrder = new ArrayDeque<>();
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
	private final AtomicInteger reconnectAttempts = new AtomicInteger();

	private volatile ChatEventHandler handler;
	private volatile GatewayClient client;
	private volatile boolean manualClose;
	/** 初次 connect 的自动重试过程中为 true，此时关闭由 connect 循环处理，不再额外排重连。 */
	private volatile boolean connecting;
	/** 最近一次网关关闭码/原因，供 connect 判断是否可重试。 */
	private volatile int lastCloseCode;
	private volatile String lastCloseReason = "";
	/** 最近一次收到任意网关帧的时间，用于发现“连接还在但已经收不到数据”的死链。 */
	private volatile long lastGatewayFrameAt;
	/** 当前心跳间隔，用于计算死链超时。 */
	private volatile long heartbeatIntervalMs = 45_000L;
	private volatile boolean ready;
	private volatile CountDownLatch readyLatch = new CountDownLatch(1);
	private volatile String accessToken;
	private volatile long accessTokenExpiresAt;
	private volatile String sessionId;
	private volatile long lastSeq = -1;
	private volatile boolean hasSeq;
	private volatile ScheduledFuture<?> heartbeatTask;
	/** 可选对象存储：超过直传上限的媒体先转存，再用公网 URL 发送。 */
	private volatile ObjectStorageProvider objectStorage;
	private volatile boolean objectStorageLoaded;

	/**
	 * 使用系统属性（没有则用官方默认地址）构造。
	 *
	 * @param appId        机器人 AppID
	 * @param clientSecret 机器人 AppSecret
	 */
	public QqOfficialAdapter(String appId, String clientSecret) {
		this(appId, clientSecret, defaultApiBase(), defaultTokenUrl(), defaultIntents(), null);
	}

	/**
	 * 带运行数据目录的构造：超过直传上限的媒体会自动转存到
	 * {@code save/config/object-storage.json} 配置的远程对象存储。
	 *
	 * @param appId        机器人 AppID
	 * @param clientSecret 机器人 AppSecret
	 * @param dataFolder   运行数据目录（save/）
	 */
	public QqOfficialAdapter(String appId, String clientSecret, File dataFolder) {
		this(appId, clientSecret, defaultApiBase(), defaultTokenUrl(), defaultIntents(), dataFolder);
	}

	/**
	 * 完整构造：可替换接口地址与 intents，便于私有化部署或本地联调。
	 *
	 * @param appId        机器人 AppID
	 * @param clientSecret 机器人 AppSecret
	 * @param apiBase      OpenAPI 根地址，例如 {@code https://api.bot.qq.com}
	 * @param tokenUrl     获取 access_token 的地址
	 * @param intents      事件订阅位，默认 {@link #DEFAULT_INTENTS}（消息 + 互动）
	 */
	public QqOfficialAdapter(String appId, String clientSecret, String apiBase, String tokenUrl, long intents) {
		this(appId, clientSecret, apiBase, tokenUrl, intents, null);
	}

	/**
	 * 完整构造（可带运行数据目录）。
	 */
	public QqOfficialAdapter(String appId, String clientSecret, String apiBase, String tokenUrl, long intents,
			File dataFolder) {
		if (appId == null || appId.trim().isEmpty()) {
			throw new IllegalArgumentException("QQ 官方机器人 AppID 为空：请用 -Dqqbot_appid=... 指定");
		}
		if (clientSecret == null || clientSecret.trim().isEmpty()) {
			throw new IllegalArgumentException("QQ 官方机器人 AppSecret 为空：请用 -Dqqbot_secret=... 指定");
		}
		this.appId = appId.trim();
		this.clientSecret = clientSecret.trim();
		String base = apiBase == null || apiBase.trim().isEmpty() ? DEFAULT_API_BASE : apiBase.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		this.apiBase = base;
		this.tokenUrl = tokenUrl == null || tokenUrl.trim().isEmpty()
				? this.apiBase + "/app/getAppAccessToken"
				: tokenUrl.trim();
		this.intents = intents == 0 ? DEFAULT_INTENTS : intents;
		this.dataFolder = dataFolder;
		this.profileCache = new ChatProfileCache(dataFolder == null ? null
				: new File(new File(dataFolder, "config"), "profile-cache.json"));
	}

	private static String defaultApiBase() {
		return System.getProperty("qqbot_api_base", DEFAULT_API_BASE);
	}

	private static String defaultTokenUrl() {
		return System.getProperty("qqbot_token_url", defaultApiBase() + "/app/getAppAccessToken");
	}

	private static long defaultIntents() {
		String configured = System.getProperty("qqbot_intents", "");
		if (!configured.trim().isEmpty()) {
			try {
				return Long.parseLong(configured.trim());
			} catch (RuntimeException e) {
				System.err.println("[QQ官方] 无法解析 -Dqqbot_intents=" + configured
						+ "，使用默认 GROUP_AND_C2C_EVENT | INTERACTION");
			}
		}
		return DEFAULT_INTENTS;
	}

	@Override
	public String getName() {
		return "QQ官方机器人(API v2)";
	}

	@Override
	public void setEventHandler(ChatEventHandler handler) {
		this.handler = handler;
	}

	/**
	 * 注入对象存储（可选）。
	 * <p>
	 * 超过直传上限的媒体会先上传到对象存储，再用
	 * {@link ObjectStorageProvider#getHttpUrl(String, java.util.function.Consumer)} 返回的公网地址
	 * 发送。传 null 表示不启用。
	 * </p>
	 *
	 * @param objectStorage 对象存储实现
	 */
	public void setObjectStorage(ObjectStorageProvider objectStorage) {
		synchronized (storageLock) {
			this.objectStorage = objectStorage;
			this.objectStorageLoaded = true;
		}
	}

	@Override
	public void connect() throws Exception {
		synchronized (connectLock) {
			if (isConnected()) {
				return;
			}
			manualClose = false;
			connecting = true;
			int attempt = 0;
			try {
				while (true) {
					if (manualClose) {
						throw new IOException("QQ 官方网关连接已取消");
					}
					connecting = true;
					attempt++;
					try {
						connectOnce();
						// 连接刚建立就被网关关闭时（例如立刻 4099），继续走重试
						connecting = false;
						if (!isConnected()) {
							throw new IOException("连接刚建立就被网关关闭");
						}
						return;
					} catch (QqApiException e) {
						// AppID/AppSecret 等配置错误重试也没有意义
						throw e;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw e;
					} catch (Exception e) {
						connecting = true;
						int code = lastCloseCode;
						if (isFatalCloseCode(code)) {
							throw new IOException("QQ 官方网关关闭码 " + code + " 不可重试："
									+ lastCloseReason, e);
						}
						long delay = Math.min(30_000L, 2_000L * attempt);
						System.err.println("[QQ官方] 连接失败（第 " + attempt + " 次）："
								+ (e.getMessage() == null ? e.toString() : e.getMessage())
								+ (code == 4099 ? "（4099 连接超时）" : "")
								+ "；" + delay + "ms 后自动重试");
						Thread.sleep(delay);
					}
				}
			} finally {
				connecting = false;
			}
		}
	}

	/** 尝试建立一次连接并等待 READY；失败时由 {@link #connect()} 决定是否重试。 */
	private void connectOnce() throws Exception {
		ready = false;
		lastCloseCode = 0;
		lastCloseReason = "";
		CountDownLatch latch = new CountDownLatch(1);
		readyLatch = latch;
		String token = accessToken();
		String url = gatewayUrl(token);
		System.out.println("[QQ官方] 连接网关：" + url);
		GatewayClient gateway = new GatewayClient(URI.create(url));
		client = gateway;
		if (!gateway.connectBlocking(20, TimeUnit.SECONDS)) {
			throw new IOException("QQ 官方网关连接失败：" + url);
		}
		// 等待 READY；连接被网关关闭时提前失败，不空等 30 秒
		boolean identified = awaitReady(gateway, latch);
		if (!identified) {
			int code = lastCloseCode;
			try {
				gateway.close();
			} catch (Throwable ignored) {
				// 关闭失败不影响重试
			}
			throw new IOException("QQ 官方网关鉴权超时：" + READY_TIMEOUT_SECONDS
					+ " 秒内没有收到 READY（请检查 AppID/AppSecret/intents）"
					+ (code == 0 ? "" : "，最后关闭码 " + code));
		}
	}

	/** 等待 READY/RESUMED；连接提前关闭或超时返回 false。 */
	private boolean awaitReady(GatewayClient gateway, CountDownLatch latch) throws InterruptedException {
		long deadline = System.currentTimeMillis() + READY_TIMEOUT_SECONDS * 1000L;
		boolean identified = latch.await(200, TimeUnit.MILLISECONDS);
		while (!identified && System.currentTimeMillis() < deadline && gateway.isOpen()) {
			identified = latch.await(200, TimeUnit.MILLISECONDS);
		}
		return identified;
	}

	@Override
	public void close() {
		manualClose = true;
		ready = false;
		sessionId = null;
		hasSeq = false;
		stopHeartbeat();
		GatewayClient gateway = client;
		if (gateway != null) {
			try {
				gateway.close();
			} catch (Throwable t) {
				System.err.println("[QQ官方] 关闭网关失败：" + t);
			}
		}
		scheduler.shutdownNow();
		apiExecutor.shutdownNow();
		profileCache.close();
	}

	@Override
	public boolean isConnected() {
		GatewayClient gateway = client;
		return ready && gateway != null && gateway.isOpen();
	}

	// ==================== 发送 ====================

	@Override
	public CompletableFuture<String> sendText(String chatId, String text) {
		if (chatId == null || chatId.isEmpty()) {
			return failedFuture(new IllegalArgumentException("chatId 为空"));
		}
		final boolean group = isGroupChat(chatId);
		final String content = text == null ? "" : text;
		final PassiveTicket ticket = reservePassive(chatId, group);
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("msg_type", 0);
				body.addProperty("content", content);
				return postMessageSync(chatId, group, body, ticket);
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, apiExecutor);
	}

	@Override
	public CompletableFuture<String> sendImage(String chatId, String file, String caption) {
		return sendMedia(chatId, FILE_TYPE_IMAGE, file, caption);
	}

	@Override
	public CompletableFuture<String> sendVideo(String chatId, String file) {
		return sendMedia(chatId, FILE_TYPE_VIDEO, file, null);
	}

	@Override
	public CompletableFuture<String> sendRecord(String chatId, String file) {
		return sendMedia(chatId, FILE_TYPE_VOICE, file, null);
	}

	@Override
	public CompletableFuture<String> sendPrivateImage(String userId, String file, String caption) {
		return sendPrivateMedia(userId, FILE_TYPE_IMAGE, file, caption);
	}

	@Override
	public CompletableFuture<String> sendPrivateVideo(String userId, String file) {
		return sendPrivateMedia(userId, FILE_TYPE_VIDEO, file, null);
	}

	@Override
	public CompletableFuture<String> sendPrivateRecord(String userId, String file) {
		return sendPrivateMedia(userId, FILE_TYPE_VOICE, file, null);
	}

	private CompletableFuture<String> sendPrivateMedia(String userId, int fileType, String file,
			String caption) {
		if (userId == null || userId.isEmpty()) {
			return failedFuture(new IllegalArgumentException("userId 为空"));
		}
		userChats.add(userId);
		return sendMediaInternal(userId, false, fileType, file, caption);
	}

	/**
	 * 发送富媒体：先上传文件拿 {@code file_info}，再以 {@code msg_type=7} 发送；有说明文字时
	 * 紧跟着再发一条文本。
	 * <p>
	 * {@code msg_seq} 在调用时就预留，保证同一轮内媒体与说明文字的序号顺序稳定。
	 * </p>
	 */
	private CompletableFuture<String> sendMedia(String chatId, int fileType, String file, String caption) {
		if (chatId == null || chatId.isEmpty()) {
			return failedFuture(new IllegalArgumentException("chatId 为空"));
		}
		return sendMediaInternal(chatId, isGroupChat(chatId), fileType, file, caption);
	}

	private CompletableFuture<String> sendMediaInternal(String chatId, boolean group, int fileType, String file,
			String caption) {
		final PassiveTicket mediaTicket = reservePassive(chatId, group);
		final boolean hasCaption = caption != null && !caption.isEmpty();
		final PassiveTicket captionTicket = hasCaption ? reservePassive(chatId, group) : null;
		return CompletableFuture.supplyAsync(() -> {
			try {
				String fileInfo = uploadMediaSync(chatId, group, fileType, file);
				JsonObject body = new JsonObject();
				body.addProperty("msg_type", 7);
				JsonObject media = new JsonObject();
				media.addProperty("file_info", fileInfo);
				body.add("media", media);
				String mid = postMessageSync(chatId, group, body, mediaTicket);
				if (hasCaption) {
					JsonObject text = new JsonObject();
					text.addProperty("msg_type", 0);
					text.addProperty("content", caption);
					postMessageSync(chatId, group, text, captionTicket);
				}
				return mid;
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, apiExecutor);
	}

	@Override
	public void sendPrivateText(String userId, String text) throws Exception {
		if (text == null || text.isEmpty() || userId == null || userId.isEmpty()) {
			return;
		}
		userChats.add(userId);
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", 0);
		body.addProperty("content", text);
		PassiveTicket ticket = reservePassive(userId, false);
		if (Boolean.TRUE.equals(gatewayThread.get())) {
			// 网关读线程上只入队，不阻塞事件接收
			submitPrivateText(userId, body, ticket);
			return;
		}
		postMessageSync(userId, false, body, ticket);
	}

	@Override
	public CompletableFuture<String> sendPrivateMessage(String userId, String text) {
		if (userId == null || userId.isEmpty() || text == null || text.isEmpty()) {
			return failedFuture(new IllegalArgumentException("userId/text 为空"));
		}
		userChats.add(userId);
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", 0);
		body.addProperty("content", text);
		PassiveTicket ticket = reservePassive(userId, false);
		return CompletableFuture.supplyAsync(() -> {
			try {
				return postMessageSync(userId, false, body, ticket);
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, apiExecutor);
	}

	@Override
	public boolean supportsRecall() {
		return true;
	}

	@Override
	public CompletableFuture<Void> recallMessage(String chatId, String messageId) {
		if (chatId == null || chatId.isEmpty() || messageId == null || messageId.isEmpty()) {
			return failedVoid(new IllegalArgumentException("chatId/messageId 为空"));
		}
		return recallUrl(apiBase + "/v2/groups/" + encodePath(chatId) + "/messages/"
				+ encodePath(messageId));
	}

	@Override
	public CompletableFuture<Void> recallPrivateMessage(String userId, String messageId) {
		if (userId == null || userId.isEmpty() || messageId == null || messageId.isEmpty()) {
			return failedVoid(new IllegalArgumentException("userId/messageId 为空"));
		}
		return recallUrl(apiBase + "/v2/users/" + encodePath(userId) + "/messages/"
				+ encodePath(messageId));
	}

	private CompletableFuture<Void> recallUrl(String url) {
		return CompletableFuture.runAsync(() -> {
			try {
				deleteJson(url, accessToken());
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, apiExecutor);
	}

	private static CompletableFuture<Void> failedVoid(Throwable error) {
		CompletableFuture<Void> failed = new CompletableFuture<>();
		failed.completeExceptionally(error);
		return failed;
	}

	/**
	 * 在后台线程发私聊；失败只记录日志。
	 */
	private void submitPrivateText(String userId, JsonObject body, PassiveTicket ticket) {
		try {
			apiExecutor.execute(() -> {
				try {
					postMessageSync(userId, false, body, ticket);
				} catch (Throwable t) {
					System.err.println("[QQ官方] 私聊消息发送失败（" + userId + "）：" + t);
				}
			});
		} catch (Throwable t) {
			System.err.println("[QQ官方] 私聊消息无法提交（" + userId + "）：" + t);
		}
	}

	/**
	 * QQ 官方 API v2 没有表情回应接口，这里静默忽略。
	 */
	@Override
	public void react(String messageId, String emoji) {
	}

	@Override
	public boolean supportsCard() {
		return true;
	}

	@Override
	public CompletableFuture<String> sendPrivateCard(String userId, String markdown,
			java.util.List<ChatButton> buttons) {
		if (userId == null || userId.isEmpty()) {
			return failedFuture(new IllegalArgumentException("userId 为空"));
		}
		userChats.add(userId);
		PassiveTicket ticket = reservePassive(userId, false);
		return sendCard(userId, false, markdown, buttons, ticket);
	}

	@Override
	public CompletableFuture<String> sendGroupCard(String groupId, String markdown,
			java.util.List<ChatButton> buttons) {
		if (groupId == null || groupId.isEmpty()) {
			return failedFuture(new IllegalArgumentException("groupId 为空"));
		}
		groupChats.add(groupId);
		PassiveTicket ticket = reservePassive(groupId, true);
		return sendCard(groupId, true, markdown, buttons, ticket);
	}

	private CompletableFuture<String> sendCard(String chatId, boolean group, String markdown,
			java.util.List<ChatButton> buttons, PassiveTicket ticket) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("msg_type", 2);
				JsonObject markdownObject = new JsonObject();
				markdownObject.addProperty("content", markdown == null ? "" : markdown);
				body.add("markdown", markdownObject);
				if (buttons != null && !buttons.isEmpty()) {
					JsonArray rows = new JsonArray();
					JsonObject row = new JsonObject();
					JsonArray buttonArray = new JsonArray();
					int index = 0;
					for (ChatButton button : buttons) {
						JsonObject item = new JsonObject();
						item.addProperty("id", button.id == null || button.id.isEmpty()
								? "button_" + index : button.id);
						JsonObject render = new JsonObject();
						render.addProperty("label", shortenButton(button.label));
						render.addProperty("visited_label", shortenButton(
								button.visitedLabel == null || button.visitedLabel.isEmpty()
										? button.label : button.visitedLabel));
						render.addProperty("style", button.style);
						item.add("render_data", render);
						JsonObject action = new JsonObject();
						action.addProperty("type", 1);
						action.addProperty("data", button.data == null ? "" : button.data);
						action.addProperty("unsupport_tips", "请升级QQ版本后重试");
						JsonObject permission = new JsonObject();
						// 私聊卡片只有收件人能看到；用 type=2 避免 specify_user_ids 与 openid 命名空间不一致
						// 导致客户端点击时提示“无权限操作”。真正的管理员校验在控制器里做。
						permission.addProperty("type", 2);
						action.add("permission", permission);
						item.add("action", action);
						buttonArray.add(item);
						index++;
					}
					row.add("buttons", buttonArray);
					rows.add(row);
					JsonObject content = new JsonObject();
					content.add("rows", rows);
					JsonObject keyboard = new JsonObject();
					keyboard.add("content", content);
					body.add("keyboard", keyboard);
				}
				return postMessageSync(chatId, group, body, ticket);
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, apiExecutor);
	}

	private static String shortenButton(String value) {
		String text = value == null ? "" : value;
		return text.length() > 10 ? text.substring(0, 10) : text;
	}

	@Override
	public byte[] fetchMedia(String ref) throws Exception {
		return readMedia(ref);
	}

	// ==================== 用户资料 ====================

	@Override
	public CompletableFuture<String> getUserName(String userId) {
		return CompletableFuture.completedFuture(profileCache.userName(userId));
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
		return fetchMemberInfo(groupId, memberId).thenApply(info -> {
			if (info == null) {
				return cached;
			}
			String name = JsonConfig.getStringOrEmpty(info, "username");
			String avatar = JsonConfig.getStringOrEmpty(info, "avatar");
			if (!name.isEmpty() || !avatar.isEmpty()) {
				rememberMemberProfile(groupId, memberId, JsonConfig.getStringOrEmpty(info, "union_openid"), name, avatar);
			}
			return name.isEmpty() ? cached : name;
		}).exceptionally(t -> cached);
	}

	@Override
	public CompletableFuture<byte[]> getUserAvatar(String userId) {
		if (userId == null || userId.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String cachedUrl = profileCache.userAvatar(userId);
		if (cachedUrl != null && !cachedUrl.isEmpty()) {
			return fetchAvatarBytes(cachedUrl);
		}
		String groupId = memberGroupHints.get(userId);
		if (groupId == null || memberInfoDenied) {
			return CompletableFuture.completedFuture(null);
		}
		return fetchMemberInfo(groupId, userId).thenCompose(info -> {
			if (info == null) {
				return CompletableFuture.<byte[]>completedFuture(null);
			}
			String avatar = JsonConfig.getStringOrEmpty(info, "avatar");
			rememberMemberProfile(groupId, userId, JsonConfig.getStringOrEmpty(info, "union_openid"),
					JsonConfig.getStringOrEmpty(info, "username"), avatar);
			return fetchAvatarBytes(avatar);
		}).exceptionally(t -> null);
	}

	@Override
	public CompletableFuture<byte[]> getGroupAvatar(String groupId) {
		if (groupId == null || groupId.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String cachedUrl = profileCache.groupAvatar(groupId);
		if (cachedUrl != null && !cachedUrl.isEmpty()) {
			return fetchAvatarBytes(cachedUrl);
		}
		if (groupInfoDenied) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject info = getJson(apiBase + "/v2/groups/" + encodePath(groupId) + "/info",
						accessToken());
				String avatar = JsonConfig.getStringOrEmpty(info, "avatar");
				if (avatar.isEmpty()) {
					avatar = JsonConfig.getStringOrEmpty(info, "group_avatar");
				}
				if (avatar.isEmpty()) {
					avatar = JsonConfig.getStringOrEmpty(info, "group_avatar_url");
				}
				if (!avatar.isEmpty()) {
					profileCache.rememberGroup(groupId, JsonConfig.getStringOrEmpty(info, "group_name"), avatar);
				}
				return avatar;
			} catch (QqApiException e) {
				if (e.getCode() == 11253) {
					groupInfoDenied = true;
				}
				return "";
			} catch (Throwable t) {
				return "";
			}
		}, apiExecutor).thenCompose(this::fetchAvatarBytes);
	}

	/** 查询群成员信息；无权限/失败返回 null。 */
	private CompletableFuture<JsonObject> fetchMemberInfo(String groupId, String memberId) {
		if (memberInfoDenied) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return getJson(apiBase + "/v2/groups/" + encodePath(groupId) + "/members/"
						+ encodePath(memberId), accessToken());
			} catch (QqApiException e) {
				if (e.getCode() == 11253) {
					memberInfoDenied = true;
				}
				return null;
			} catch (Throwable t) {
				return null;
			}
		}, apiExecutor);
	}

	/** 下载头像；失败返回 null。 */
	private CompletableFuture<byte[]> fetchAvatarBytes(String url) {
		if (url == null || url.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return readMedia(url);
			} catch (Throwable t) {
				return null;
			}
		}, apiExecutor);
	}

	private void rememberMemberProfile(String groupId, String memberId, String unionId, String name,
			String avatar) {
		if (memberId == null || memberId.isEmpty()) {
			return;
		}
		profileCache.rememberUser(memberId, unionId, name, avatar);
		if (groupId != null && !groupId.isEmpty()) {
			profileCache.rememberMemberName(groupId, memberId, name);
			memberGroupHints.put(memberId, groupId);
		}
	}

	private void rememberUserProfile(String userId, String unionId, String name, String avatar) {
		if (userId == null || userId.isEmpty()) {
			return;
		}
		profileCache.rememberUser(userId, unionId, name, avatar);
	}

	// ==================== 消息发送内部实现 ====================

	/** 一条已预留的被动回复序号。 */
	private static final class PassiveTicket {
		final String messageId;
		final int seq;

		PassiveTicket(String messageId, int seq) {
			this.messageId = messageId;
			this.seq = seq;
		}
	}

	/** 某个会话最近一条入站消息与已用回复次数。 */
	private static final class PassiveReply {
		final String messageId;
		final long receivedAt;
		int used;

		PassiveReply(String messageId, long receivedAt) {
			this.messageId = messageId;
			this.receivedAt = receivedAt;
		}
	}

	private void rememberIncoming(String chatId, String messageId) {
		if (chatId == null || chatId.isEmpty() || messageId == null || messageId.isEmpty()) {
			return;
		}
		passiveReplies.put(chatId, new PassiveReply(messageId, System.currentTimeMillis()));
	}

	/** 记录消息 id；同一消息被重复推送时返回 false。 */
	private boolean markSeen(String messageId) {
		if (messageId == null || messageId.isEmpty()) {
			return true;
		}
		synchronized (refsLock) {
			if (!seenMessageIds.add(messageId)) {
				return false;
			}
			seenMessageOrder.addLast(messageId);
			while (seenMessageOrder.size() > 500) {
				String oldest = seenMessageOrder.pollFirst();
				if (oldest != null) {
					seenMessageIds.remove(oldest);
				}
			}
			return true;
		}
	}

	/**
	 * 预留一次被动回复。超出有效期或次数后返回 null，调用方按主动消息发送。
	 */
	private PassiveTicket reservePassive(String chatId, boolean group) {
		PassiveReply reply = passiveReplies.get(chatId);
		if (reply == null) {
			return null;
		}
		long ttl = group ? GROUP_PASSIVE_TTL_MS : C2C_PASSIVE_TTL_MS;
		int max = group ? GROUP_PASSIVE_MAX_REPLIES : C2C_PASSIVE_MAX_REPLIES;
		synchronized (reply) {
			if (System.currentTimeMillis() - reply.receivedAt > ttl || reply.used >= max) {
				passiveReplies.remove(chatId, reply);
				return null;
			}
			reply.used++;
			return new PassiveTicket(reply.messageId, reply.used);
		}
	}

	private static void applyPassive(JsonObject body, PassiveTicket ticket) {
		if (ticket != null) {
			body.addProperty("msg_id", ticket.messageId);
			body.addProperty("msg_seq", ticket.seq);
		}
	}

	/** 同步发一条消息（运行在 apiExecutor 上）。 */
	private String postMessageSync(String chatId, boolean group, JsonObject body, PassiveTicket ticket)
			throws IOException {
		applyPassive(body, ticket);
		String url = apiBase + (group ? "/v2/groups/" : "/v2/users/") + encodePath(chatId) + "/messages";
		JsonObject response = postJson(url, accessToken(), body);
		recordOwnRef(response);
		return JsonConfig.getStringOrEmpty(response, "id");
	}

	/**
	 * 上传媒体，返回 {@code file_info}。
	 * <p>
	 * 优先使用原始类型；类型不匹配或超过软限制时退回普通文件（file_type=4）。
	 * </p>
	 */
	private String uploadMediaSync(String chatId, boolean group, int fileType, String file) throws IOException {
		try {
			return uploadOnce(chatId, group, fileType, file);
		} catch (IOException e) {
			if (fileType == FILE_TYPE_FILE) {
				throw e;
			}
			System.out.println("[QQ官方] 媒体按类型 " + fileType + " 上传失败，改用文件类型重试：" + e.getMessage());
			return uploadOnce(chatId, group, FILE_TYPE_FILE, file);
		}
	}

	private String uploadOnce(String chatId, boolean group, int fileType, String file) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("file_type", fileType);
		body.addProperty("srv_send_msg", false);
		boolean isUrl = file != null && (file.startsWith("http://") || file.startsWith("https://"));
		if (isUrl) {
			body.addProperty("url", file);
		} else {
			byte[] data = readMedia(file);
			String hostedUrl = null;
			if (data.length > MAX_INLINE_MEDIA_BYTES) {
				// 超过 5MB 直传上限：如果配置了远程对象存储，先转存再传公网 URL
				hostedUrl = hostMedia(data);
				if (hostedUrl == null) {
					throw new IOException("媒体超过 5MB 直传上限，且没有可用的远程对象存储；"
							+ "请提供 http(s) 链接或配置 save/config/object-storage.json：" + describe(file));
				}
			}
			if (hostedUrl != null) {
				body.addProperty("url", hostedUrl);
			} else {
				body.addProperty("file_data", Base64.getEncoder().encodeToString(data));
				body.addProperty("file_name", fileNameFor(file, fileType));
			}
		}
		String url = apiBase + (group ? "/v2/groups/" : "/v2/users/") + encodePath(chatId) + "/files";
		JsonObject response = postJson(url, accessToken(), body);
		String fileInfo = JsonConfig.getStringOrEmpty(response, "file_info");
		if (fileInfo.isEmpty()) {
			throw new IOException("上传媒体没有返回 file_info：" + response);
		}
		return fileInfo;
	}

	/**
	 * 把超过直传上限的媒体转存对象存储，返回公网 http(s) 地址；不可用时返回 null。
	 */
	private String hostMedia(byte[] data) {
		ObjectStorageProvider provider = objectStorage();
		if (provider == null) {
			return null;
		}
		try {
			String ref = provider.uploadIfNotExists(data, null);
			if (ref == null || ref.isEmpty()) {
				return null;
			}
			String url = provider.getHttpUrl(ref, null);
			if (url == null || url.isEmpty()) {
				System.out.println("[QQ官方] 对象存储没有公网 http 地址，无法用于 QQ 媒体上传");
				return null;
			}
			System.out.println("[QQ官方] 超限媒体已转存对象存储：" + describe(url));
			return url;
		} catch (Throwable t) {
			System.err.println("[QQ官方] 媒体转存对象存储失败：" + t);
			return null;
		}
	}

	/** 懒加载对象存储：没有配置远程存储时返回 null。 */
	private ObjectStorageProvider objectStorage() {
		if (objectStorageLoaded) {
			return objectStorage;
		}
		synchronized (storageLock) {
			if (objectStorageLoaded) {
				return objectStorage;
			}
			objectStorageLoaded = true;
			if (dataFolder == null) {
				return null;
			}
			try {
				File configFile = ConfigPaths.objectStorage(dataFolder);
				if (!configFile.exists()) {
					System.out.println("[QQ官方] 未配置对象存储：超过 5MB 的媒体请提供 http(s) 链接");
					return null;
				}
				JsonObject config = JsonParser.parseString(FileUtil.readString(configFile)).getAsJsonObject();
				objectStorage = new DelegatingStorage(new File(dataFolder, "ircimages"), new TOStorage(config));
				System.out.println("[QQ官方] 媒体可转存远程对象存储");
			} catch (Throwable t) {
				System.err.println("[QQ官方] 对象存储初始化失败：" + t);
			}
			return objectStorage;
		}
	}

	/** 记录自己发出消息的引用索引，用于识别用户“回复机器人”。 */
	private void recordOwnRef(JsonObject response) {
		if (response == null) {
			return;
		}
		JsonObject ext = JsonConfig.getObjectOrEmpty(response, "ext_info");
		String ref = JsonConfig.getStringOrEmpty(ext, "ref_idx");
		if (ref.isEmpty()) {
			return;
		}
		synchronized (refsLock) {
			ownMessageRefs.addLast(ref);
			while (ownMessageRefs.size() > 200) {
				ownMessageRefs.removeFirst();
			}
		}
	}

	private boolean isOwnMessageRef(String ref) {
		if (ref == null || ref.isEmpty()) {
			return false;
		}
		synchronized (refsLock) {
			return ownMessageRefs.contains(ref);
		}
	}

	private boolean isGroupChat(String chatId) {
		if (groupChats.contains(chatId)) {
			return true;
		}
		if (userChats.contains(chatId)) {
			return false;
		}
		// 未知会话按群处理：控制器主要用于群聊；单聊请走 sendPrivateText
		groupChats.add(chatId);
		return true;
	}

	// ==================== 接收 ====================

	private void handleFrame(String frame) {
		lastGatewayFrameAt = System.currentTimeMillis();
		final JsonObject payload;
		try {
			payload = JsonParser.parseString(frame).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		int op = JsonConfig.getInt(payload, "op", -1);
		switch (op) {
		case 10:
			onHello(payload);
			break;
		case 0:
			onDispatch(payload);
			break;
		case 7:
			forceReconnect("网关要求重连");
			break;
		case 9:
			System.out.println("[QQ官方] 会话失效，重新鉴权");
			sessionId = null;
			hasSeq = false;
			lastSeq = -1;
			forceReconnect("会话失效");
			break;
		case 11:
			break;
		default:
			break;
		}
	}

	private void onHello(JsonObject payload) {
		JsonObject data = JsonConfig.getObjectOrEmpty(payload, "d");
		int interval = JsonConfig.getInt(data, "heartbeat_interval", 45000);
		startHeartbeat(interval);
		if (sessionId != null && hasSeq) {
			sendResume();
		} else {
			sendIdentify();
		}
	}

	private void onDispatch(JsonObject payload) {
		if (payload.has("s") && !payload.get("s").isJsonNull()) {
			try {
				lastSeq = payload.get("s").getAsLong();
				hasSeq = true;
			} catch (RuntimeException e) {
				// 忽略异常序号
			}
		}
		String event = JsonConfig.getStringOrEmpty(payload, "t");
		JsonObject data = JsonConfig.getObjectOrEmpty(payload, "d");
		if ("READY".equals(event)) {
			sessionId = JsonConfig.getStringOrEmpty(data, "session_id");
			ready = true;
			reconnectAttempts.set(0);
			readyLatch.countDown();
			JsonObject user = JsonConfig.getObjectOrEmpty(data, "user");
			System.out.println("[QQ官方] 鉴权成功：机器人「" + JsonConfig.getStringOrEmpty(user, "username") + "」，AppID " + appId);
			return;
		}
		if ("RESUMED".equals(event)) {
			ready = true;
			reconnectAttempts.set(0);
			readyLatch.countDown();
			System.out.println("[QQ官方] 会话已恢复");
			return;
		}
		synchronized (eventLock) {
			try {
				dispatchEvent(event, data);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	private void dispatchEvent(String event, JsonObject data) {
		if ("INTERACTION_CREATE".equals(event)) {
			handleInteraction(data);
			return;
		}
		ChatEventHandler current = handler;
		if (current == null) {
			return;
		}
		if ("GROUP_AT_MESSAGE_CREATE".equals(event) || "GROUP_MESSAGE_CREATE".equals(event)) {
			JsonObject author = JsonConfig.getObjectOrEmpty(data, "author");
			if (JsonConfig.getBool(author, "bot", false)) {
				// 忽略其它机器人（含自己）的消息，避免自问自答
				return;
			}
			if (!markSeen(JsonConfig.getStringOrEmpty(data, "id"))) {
				System.out.println("[QQ官方] 忽略重复推送的群消息：" + JsonConfig.getStringOrEmpty(data, "id"));
				return;
			}
			IncomingMessage message = parseGroupEvent(data, "GROUP_AT_MESSAGE_CREATE".equals(event));
			if (message != null) {
				groupChats.add(message.getChatId());
				rememberIncoming(message.getChatId(), message.getMessageId());
				System.out.println("[QQ官方] 收到群消息：" + message.getChatId()
						+ " / 发送者 " + message.getSenderId() + " / " + message.getPlainText());
				current.onGroupMessage(message);
			}
			return;
		}
		if ("C2C_MESSAGE_CREATE".equals(event)) {
			JsonObject author = JsonConfig.getObjectOrEmpty(data, "author");
			if (JsonConfig.getBool(author, "bot", false)) {
				return;
			}
			if (!markSeen(JsonConfig.getStringOrEmpty(data, "id"))) {
				System.out.println("[QQ官方] 忽略重复推送的私聊消息：" + JsonConfig.getStringOrEmpty(data, "id"));
				return;
			}
			IncomingMessage message = parseC2CEvent(data);
			if (message != null) {
				userChats.add(message.getChatId());
				rememberIncoming(message.getChatId(), message.getMessageId());
				System.out.println("[QQ官方] 收到私聊消息：" + message.getChatId() + " / " + message.getPlainText());
				current.onPrivateMessage(message);
			}
			return;
		}
		current.onSystemEvent(event, data);
		if ("GROUP_ADD_ROBOT".equals(event)) {
			System.out.println("[QQ官方] 机器人被加入群：" + JsonConfig.getStringOrEmpty(data, "group_openid"));
		} else if ("GROUP_DEL_ROBOT".equals(event)) {
			System.out.println("[QQ官方] 机器人被移出群：" + JsonConfig.getStringOrEmpty(data, "group_openid"));
		} else if ("GROUP_MSG_REJECT".equals(event) || "GROUP_MSG_RECEIVE".equals(event)) {
			System.out.println("[QQ官方] 群消息推送开关变化：" + event + " / " + JsonConfig.getStringOrEmpty(data, "group_openid"));
		} else if ("FRIEND_ADD".equals(event) || "FRIEND_DEL".equals(event)
				|| "C2C_MSG_REJECT".equals(event) || "C2C_MSG_RECEIVE".equals(event)) {
			JsonObject author = JsonConfig.getObjectOrEmpty(data, "author");
			String openid = JsonConfig.getStringOrEmpty(author, "user_openid");
			if (openid.isEmpty()) {
				openid = JsonConfig.getStringOrEmpty(author, "id");
			}
			if ("FRIEND_ADD".equals(event)) {
				rememberUserProfile(openid, JsonConfig.getStringOrEmpty(author, "union_openid"),
						JsonConfig.getStringOrEmpty(author, "username"), JsonConfig.getStringOrEmpty(author, "avatar"));
			}
			System.out.println("[QQ官方] 好友关系变化：" + event + " / " + openid);
		}
	}

	private void handleInteraction(JsonObject data) {
		int type = JsonConfig.getInt(data, "type", 0);
		String interactionId = JsonConfig.getStringOrEmpty(data, "id");
		// 消息按钮/快捷菜单必须在 3 秒内回应，否则客户端一直 loading
		if (type == ChatInteraction.TYPE_BUTTON || type == ChatInteraction.TYPE_MENU) {
			ackInteraction(interactionId);
		}
		if (type != ChatInteraction.TYPE_BUTTON) {
			return;
		}
		JsonObject resolved = JsonConfig.getObjectOrEmpty(JsonConfig.getObjectOrEmpty(data, "data"), "resolved");
		ChatInteraction interaction = new ChatInteraction(
				interactionId,
				JsonConfig.getStringOrEmpty(data, "application_id").isEmpty() ? appId : JsonConfig.getStringOrEmpty(data, "application_id"),
				type,
				JsonConfig.getStringOrEmpty(data, "scene"),
				JsonConfig.getStringOrEmpty(data, "user_openid"),
				JsonConfig.getStringOrEmpty(data, "group_openid"),
				JsonConfig.getStringOrEmpty(data, "group_member_openid"),
				JsonConfig.getStringOrEmpty(resolved, "message_id"),
				JsonConfig.getStringOrEmpty(resolved, "button_id"),
				JsonConfig.getStringOrEmpty(resolved, "button_data"));
		System.out.println("[QQ官方] 收到按钮互动：" + interaction.getButtonId() + " / "
				+ interaction.getButtonData());
		ChatEventHandler current = handler;
		if (current != null) {
			try {
				current.onInteraction(interaction);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	/** 异步回应互动事件，避免阻塞网关读线程。 */
	private void ackInteraction(String interactionId) {
		if (interactionId == null || interactionId.isEmpty()) {
			return;
		}
		try {
			apiExecutor.execute(() -> {
				try {
					JsonObject body = new JsonObject();
					body.addProperty("code", 0);
					putJson(apiBase + "/interactions/" + encodePath(interactionId), accessToken(), body);
				} catch (Throwable t) {
					System.err.println("[QQ官方] 回应互动事件失败（" + interactionId + "）：" + t);
				}
			});
		} catch (Throwable t) {
			System.err.println("[QQ官方] 无法提交互动回应：" + t);
		}
	}

	private IncomingMessage parseGroupEvent(JsonObject data, boolean mentionedBot) {
		String chatId = JsonConfig.getStringOrEmpty(data, "group_openid");
		if (chatId.isEmpty()) {
			return null;
		}
		JsonObject author = JsonConfig.getObjectOrEmpty(data, "author");
		String senderId = JsonConfig.getStringOrEmpty(author, "member_openid");
		if (senderId.isEmpty()) {
			senderId = JsonConfig.getStringOrEmpty(author, "id");
		}
		String senderName = JsonConfig.getStringOrEmpty(author, "username");
		rememberMemberProfile(chatId, senderId, JsonConfig.getStringOrEmpty(author, "union_openid"), senderName,
				JsonConfig.getStringOrEmpty(author, "avatar"));
		String refIdx = sceneRefIdx(data);
		IncomingMessage.Builder builder = IncomingMessage.builder()
				.chatId(chatId)
				.selfId(appId)
				.senderId(senderId)
				.senderName(senderName)
				.messageId(JsonConfig.getStringOrEmpty(data, "id"))
				.replyToMessageId(refIdx.isEmpty() ? null : refIdx)
				.raw(data);
		// 全量模式下 @ 列表里可能出现机器人自身（bot=true），也据此认定为 @ 了机器人
		boolean botMentioned = mentionedBot;
		JsonArray mentions = JsonConfig.getArray(data, "mentions");
		if (mentions != null) {
			for (JsonElement element : mentions) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject mentioned = element.getAsJsonObject();
				String id = JsonConfig.getStringOrEmpty(mentioned, "member_openid");
				if (id.isEmpty()) {
					id = JsonConfig.getStringOrEmpty(mentioned, "id");
				}
				if (JsonConfig.getBool(mentioned, "bot", false)) {
					// 官方接口里机器人条目的 id 是 openid，不一定等于 AppID，无法在这里断言
					// “是不是自己”；先标记“@ 了机器人”，交给连接器按事件的 self_id 归属，
					// 再由各会话的 botId 决定谁响应。
					botMentioned = true;
				}
				builder.mentionedId(id);
			}
		}
		for (IncomingContent content : parseContents(data, refIdx, mentions)) {
			builder.content(content);
		}
		return builder.mentionsBot(botMentioned || isOwnMessageRef(refIdx)).build();
	}

	private IncomingMessage parseC2CEvent(JsonObject data) {
		JsonObject author = JsonConfig.getObjectOrEmpty(data, "author");
		String chatId = JsonConfig.getStringOrEmpty(author, "user_openid");
		if (chatId.isEmpty()) {
			chatId = JsonConfig.getStringOrEmpty(author, "id");
		}
		if (chatId.isEmpty()) {
			return null;
		}
		String refIdx = sceneRefIdx(data);
		String senderName = JsonConfig.getStringOrEmpty(author, "username");
		rememberUserProfile(chatId, JsonConfig.getStringOrEmpty(author, "union_openid"), senderName,
				JsonConfig.getStringOrEmpty(author, "avatar"));
		IncomingMessage.Builder builder = IncomingMessage.builder()
				.chatId(chatId)
				.selfId(appId)
				.senderId(chatId)
				.senderName(senderName)
				.messageId(JsonConfig.getStringOrEmpty(data, "id"))
				.replyToMessageId(refIdx.isEmpty() ? null : refIdx)
				.raw(data);
		for (IncomingContent content : parseContents(data, refIdx, null)) {
			builder.content(content);
		}
		return builder.build();
	}

	/**
	 * 把事件正文、消息元素、卡片与附件统一成内容块。
	 * <p>
	 * {@code message_type=103}（引用消息）的 {@code msg_elements} 是被引用内容，转成
	 * {@link IncomingContent.QuoteBlock}；其它元素按文本拼接。
	 * </p>
	 */
	private List<IncomingContent> parseContents(JsonObject data, String refIdx, JsonArray mentions) {
		List<IncomingContent> contents = new ArrayList<>();
		JsonArray elements = JsonConfig.getArray(data, "msg_elements");
		if (elements != null) {
			int count = 0;
			for (JsonElement element : elements) {
				if (element == null || !element.isJsonObject() || count++ >= 20) {
					continue;
				}
				JsonObject item = element.getAsJsonObject();
				String itemContent = JsonConfig.getStringOrEmpty(item, "content");
				int itemType = JsonConfig.getInt(item, "message_type", 0);
				if (itemType == 103) {
					JsonObject itemAuthor = JsonConfig.getObjectOrEmpty(item, "author");
					String quotedSender = JsonConfig.getStringOrEmpty(itemAuthor, "member_openid");
					if (quotedSender.isEmpty()) {
						quotedSender = JsonConfig.getStringOrEmpty(itemAuthor, "id");
					}
					String quoteId = JsonConfig.getStringOrEmpty(item, "msg_idx");
					if (quoteId.isEmpty()) {
						quoteId = refIdx;
					}
					contents.add(new IncomingContent.QuoteBlock(quoteId, quotedSender, itemContent));
				} else if (!itemContent.isEmpty()) {
					contents.add(new IncomingContent.TextBlock(itemContent));
				}
			}
		}
		String content = JsonConfig.getStringOrEmpty(data, "content");
		if (!content.isEmpty()) {
			contents.add(new IncomingContent.TextBlock(normalizeMentions(content, mentions)));
		}
		JsonArray attachments = JsonConfig.getArray(data, "attachments");
		if (attachments != null) {
			for (JsonElement element : attachments) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				IncomingContent attachment = attachmentContent(element.getAsJsonObject());
				if (attachment != null) {
					contents.add(attachment);
				}
			}
		}
		if (contents.isEmpty()) {
			// 卡片消息等没有正文时，退回卡片提示文本，避免整条消息被丢掉
			JsonObject ark = JsonConfig.getObjectOrEmpty(data, "ark_data");
			String prompt = JsonConfig.getStringOrEmpty(ark, "prompt");
			if (!prompt.isEmpty()) {
				contents.add(new IncomingContent.TextBlock("（卡片消息：" + prompt + "）"));
			}
		}
		return contents;
	}

	/** 附件 → 内容块：图片进入图片块，语音/视频/文件用占位文本保留信息。 */
	private static IncomingContent attachmentContent(JsonObject attachment) {
		String contentType = JsonConfig.getStringOrEmpty(attachment, "content_type");
		String url = JsonConfig.getStringOrEmpty(attachment, "url");
		String filename = JsonConfig.getStringOrEmpty(attachment, "filename");
		if (contentType.startsWith("image/") || "image".equals(contentType) || "picture".equals(contentType)) {
			return new IncomingContent.ImageBlock(url, filename);
		}
		if ("voice".equals(contentType)) {
			String asr = JsonConfig.getStringOrEmpty(attachment, "asr_refer_text");
			StringBuilder text = new StringBuilder("（语音消息");
			if (!asr.isEmpty()) {
				text.append("，转写：").append(asr);
			}
			text.append("）");
			return new IncomingContent.TextBlock(text.toString());
		}
		if (contentType.startsWith("video/")) {
			return new IncomingContent.TextBlock("（视频：" + (filename.isEmpty() ? "视频" : filename) + "）");
		}
		return new IncomingContent.TextBlock("（文件：" + (filename.isEmpty() ? "文件" : filename) + "）");
	}

	/**
	 * 全量模式下正文里的 @ 会写成 {@code <@openid>}；按 mentions 换回可读的 @昵称，
	 * 避免模型看到一串 openid。
	 */
	private static String normalizeMentions(String content, JsonArray mentions) {
		if (content == null || content.isEmpty() || mentions == null) {
			return content;
		}
		String result = content;
		for (JsonElement element : mentions) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject user = element.getAsJsonObject();
			String id = JsonConfig.getStringOrEmpty(user, "member_openid");
			if (id.isEmpty()) {
				id = JsonConfig.getStringOrEmpty(user, "user_openid");
			}
			if (id.isEmpty()) {
				id = JsonConfig.getStringOrEmpty(user, "id");
			}
			if (id.isEmpty()) {
				continue;
			}
			String name = JsonConfig.getStringOrEmpty(user, "username");
			result = result.replace("<@" + id + ">", "@" + (name.isEmpty() ? "用户" : name));
		}
		return result.replace("<@all>", "@全体成员");
	}

	/** 取 message_scene.ext 里的 ref_msg_idx（被引用的消息索引）。 */
	private static String sceneRefIdx(JsonObject data) {
		JsonObject scene = JsonConfig.getObjectOrEmpty(data, "message_scene");
		JsonArray ext = JsonConfig.getArray(scene, "ext");
		if (ext == null) {
			return "";
		}
		for (JsonElement element : ext) {
			if (element == null || !element.isJsonPrimitive()) {
				continue;
			}
			String value;
			try {
				value = element.getAsString();
			} catch (RuntimeException e) {
				continue;
			}
			if (value.startsWith("ref_msg_idx=")) {
				return value.substring("ref_msg_idx=".length());
			}
		}
		return "";
	}

	// ==================== 网关 ====================

	private void sendIdentify() {
		try {
			JsonObject payload = new JsonObject();
			payload.addProperty("op", 2);
			JsonObject data = new JsonObject();
			data.addProperty("token", "QQBot " + accessToken());
			data.addProperty("intents", intents);
			JsonArray shard = new JsonArray();
			shard.add(0);
			shard.add(1);
			data.add("shard", shard);
			JsonObject properties = new JsonObject();
			properties.addProperty("$os", System.getProperty("os.name", "linux"));
			properties.addProperty("$browser", "aiwuxia");
			properties.addProperty("$device", "aiwuxia");
			data.add("properties", properties);
			payload.add("d", data);
			sendToGateway(payload);
		} catch (Throwable t) {
			System.err.println("[QQ官方] 发送 Identify 失败：" + t);
		}
	}

	private void sendResume() {
		GatewayClient gateway = client;
		if (gateway == null || !gateway.isOpen() || sessionId == null) {
			sendIdentify();
			return;
		}
		try {
			JsonObject payload = new JsonObject();
			payload.addProperty("op", 6);
			JsonObject data = new JsonObject();
			data.addProperty("token", "QQBot " + accessToken());
			data.addProperty("session_id", sessionId);
			data.addProperty("seq", lastSeq);
			payload.add("d", data);
			sendToGateway(payload);
			System.out.println("[QQ官方] 尝试恢复会话，seq=" + lastSeq);
		} catch (Throwable t) {
			System.err.println("[QQ官方] 发送 Resume 失败：" + t);
		}
	}

	private void startHeartbeat(int intervalMs) {
		stopHeartbeat();
		final long interval = Math.max(1000L, intervalMs);
		heartbeatIntervalMs = interval;
		lastGatewayFrameAt = System.currentTimeMillis();
		heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, interval, interval,
				TimeUnit.MILLISECONDS);
	}

	private void stopHeartbeat() {
		ScheduledFuture<?> task = heartbeatTask;
		heartbeatTask = null;
		if (task != null) {
			task.cancel(false);
		}
	}

	private void sendHeartbeat() {
		GatewayClient gateway = client;
		if (gateway == null || !gateway.isOpen()) {
			return;
		}
		long now = System.currentTimeMillis();
		long defaultTimeout = Math.max(90_000L, heartbeatIntervalMs * 2 + 10_000L);
		long deadTimeout = Long.getLong("qqbot_dead_timeout_ms", defaultTimeout);
		if (deadTimeout > 0 && now - lastGatewayFrameAt > deadTimeout) {
			forceReconnect("超过 " + deadTimeout + "ms 未收到网关数据");
			return;
		}
		try {
			JsonObject payload = new JsonObject();
			payload.addProperty("op", 1);
			if (hasSeq) {
				payload.addProperty("d", lastSeq);
			} else {
				payload.add("d", JsonNull.INSTANCE);
			}
			gateway.send(payload.toString());
		} catch (Throwable t) {
			System.err.println("[QQ官方] 发送心跳失败：" + t);
		}
	}

	private void sendToGateway(JsonObject payload) {
		GatewayClient gateway = client;
		if (gateway == null || !gateway.isOpen()) {
			throw new IllegalStateException("网关未连接");
		}
		gateway.send(payload.toString());
	}

	/** 主动关闭当前连接；onClose 会安排重连（manualClose 为 false 时）。 */
	private void closeCurrentClient() {
		GatewayClient gateway = client;
		if (gateway != null) {
			try {
				gateway.close();
			} catch (Throwable t) {
				System.err.println("[QQ官方] 关闭网关失败：" + t);
			}
		}
	}

	/**
	 * 不等 onClose 回调，直接标记断开并安排重连。
	 * <p>
	 * 半开连接/错误回调里 close() 不一定会触发 onClose；这里兜底，避免永远等不到重连。
	 * </p>
	 */
	private void forceReconnect(String reason) {
		if (manualClose) {
			return;
		}
		ready = false;
		stopHeartbeat();
		System.err.println("[QQ官方] " + reason + "，主动断开并安排重连");
		closeCurrentClient();
		if (!connecting) {
			scheduleReconnect();
		}
	}

	/** 这些关闭码表示配置/权限问题，重连也没有意义。 */
	private static boolean isFatalCloseCode(int code) {
		switch (code) {
		case 4001:
		case 4002:
		case 4010:
		case 4011:
		case 4012:
		case 4013:
		case 4014:
		case 4914:
		case 4915:
			return true;
		default:
			return false;
		}
	}

	private void scheduleReconnect() {
		if (manualClose) {
			return;
		}
		if (!reconnectScheduled.compareAndSet(false, true)) {
			return;
		}
		int attempt = reconnectAttempts.incrementAndGet();
		long delay = Math.min(30_000L, 2_000L * attempt);
		System.out.println("[QQ官方] " + delay + "ms 后重连（第 " + attempt + " 次）");
		try {
			scheduler.schedule(() -> {
				reconnectScheduled.set(false);
				doReconnect();
			}, delay, TimeUnit.MILLISECONDS);
		} catch (Throwable t) {
			reconnectScheduled.set(false);
			System.err.println("[QQ官方] 安排重连失败：" + t);
		}
	}

	private void doReconnect() {
		System.out.println("[QQ官方] 执行重连任务");
		synchronized (connectLock) {
			if (manualClose || isConnected()) {
				return;
			}
			try {
				ready = false;
				stopHeartbeat();
				lastCloseCode = 0;
				lastCloseReason = "";
				String token = accessToken();
				String url = gatewayUrl(token);
				System.out.println("[QQ官方] 重连网关：" + url);
				CountDownLatch latch = new CountDownLatch(1);
				readyLatch = latch;
				GatewayClient gateway = new GatewayClient(URI.create(url));
				client = gateway;
				if (!gateway.connectBlocking(20, TimeUnit.SECONDS)) {
					throw new IOException("连接失败：" + url);
				}
				if (!awaitReady(gateway, latch)) {
					int code = lastCloseCode;
					try {
						gateway.close();
					} catch (Throwable ignored) {
						// 关闭失败不影响后续重试
					}
					throw new IOException("重连鉴权超时" + (code == 0 ? "" : "，最后关闭码 " + code));
				}
			} catch (Throwable t) {
				System.err.println("[QQ官方] 重连失败：" + t);
				scheduleReconnect();
			}
		}
	}

	// ==================== 鉴权与 HTTP ====================

	/** 取 access_token；临近过期（60 秒内）时刷新。 */
	private String accessToken() throws IOException {
		synchronized (tokenLock) {
			long now = System.currentTimeMillis();
			if (accessToken != null && now < accessTokenExpiresAt - 60_000L) {
				return accessToken;
			}
			JsonObject request = new JsonObject();
			request.addProperty("appId", appId);
			request.addProperty("clientSecret", clientSecret);
			JsonObject response = postJson(tokenUrl, null, request);
			String token = JsonConfig.getStringOrEmpty(response, "access_token");
			if (token.isEmpty()) {
				throw new IOException("获取 access_token 失败：" + response);
			}
			long expiresIn = JsonConfig.getLong(response, "expires_in", 7200L);
			accessToken = token;
			accessTokenExpiresAt = now + Math.max(60L, expiresIn) * 1000L;
			System.out.println("[QQ官方] access_token 已获取，有效期 " + expiresIn + " 秒");
			return token;
		}
	}

	private String gatewayUrl(String token) throws IOException {
		JsonObject response = getJson(apiBase + "/gateway", token);
		String url = JsonConfig.getStringOrEmpty(response, "url");
		if (url.isEmpty()) {
			throw new IOException("获取网关地址失败：" + response);
		}
		return url;
	}

	private JsonObject getJson(String url, String token) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(10000);
		connection.setReadTimeout(30000);
		connection.setRequestProperty("Accept", "application/json");
		if (token != null) {
			connection.setRequestProperty("Authorization", "QQBot " + token);
		}
		return readResponse(connection, url);
	}

	private JsonObject putJson(String url, String token, JsonObject body) throws IOException {
		return writeJson("PUT", url, token, body);
	}

	private JsonObject deleteJson(String url, String token) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setRequestMethod("DELETE");
		connection.setConnectTimeout(10000);
		connection.setReadTimeout(30000);
		connection.setRequestProperty("Accept", "application/json");
		if (token != null) {
			connection.setRequestProperty("Authorization", "QQBot " + token);
		}
		return readResponse(connection, url);
	}

	private JsonObject postJson(String url, String token, JsonObject body) throws IOException {
		return writeJson("POST", url, token, body);
	}

	private JsonObject writeJson(String method, String url, String token, JsonObject body) throws IOException {
		byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setRequestMethod(method);
		connection.setDoOutput(true);
		connection.setConnectTimeout(10000);
		connection.setReadTimeout(60000);
		connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		connection.setRequestProperty("Accept", "application/json");
		if (token != null) {
			connection.setRequestProperty("Authorization", "QQBot " + token);
		}
		try (OutputStream out = connection.getOutputStream()) {
			out.write(payload);
		}
		return readResponse(connection, url);
	}

	/** 读响应并按官方错误结构（err_code / code）判定失败。 */
	private static JsonObject readResponse(HttpURLConnection connection, String url) throws IOException {
		int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		String text;
		if (stream == null) {
			text = "";
		} else {
			try (InputStream in = stream) {
				text = new String(FileUtil.readAll(in), StandardCharsets.UTF_8);
			}
		}
		JsonObject json = null;
		if (!text.isEmpty()) {
			try {
				json = JsonParser.parseString(text).getAsJsonObject();
			} catch (RuntimeException e) {
				// 非 JSON 响应：交由下面的状态码判断
			}
		}
		if (status < 200 || status >= 300) {
			throw new QqApiException(status, json == null ? -1 : JsonConfig.getInt(json, "err_code", -1),
					json == null ? text : JsonConfig.getStringOrEmpty(json, "message"), url);
		}
		if (json == null) {
			return new JsonObject();
		}
		int errorCode = JsonConfig.getInt(json, "err_code", 0);
		if (errorCode != 0) {
			throw new QqApiException(status, errorCode, JsonConfig.getStringOrEmpty(json, "message"), url);
		}
		// 获取 access_token 的失败用 code 表示，成功响应没有 code
		if (json.has("code") && !json.get("code").isJsonNull()) {
			int code = JsonConfig.getInt(json, "code", 0);
			if (code != 0) {
				throw new QqApiException(status, code, JsonConfig.getStringOrEmpty(json, "message"), url);
			}
		}
		return json;
	}

	/** QQ 官方 OpenAPI 业务错误。 */
	public static class QqApiException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int httpStatus;
		private final int code;

		public QqApiException(int httpStatus, int code, String message, String url) {
			super("QQ 官方接口调用失败：HTTP " + httpStatus + "，错误码 " + code
					+ (message == null || message.isEmpty() ? "" : "，原因 " + message) + "，URL " + url);
			this.httpStatus = httpStatus;
			this.code = code;
		}

		public int getHttpStatus() {
			return httpStatus;
		}

		public int getCode() {
			return code;
		}
	}

	// ==================== 工具方法 ====================

	private static String encodePath(String value) {
		try {
			return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return value;
		}
	}

	private static byte[] readMedia(String ref) throws IOException {
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
		File file = new File(ref);
		if (file.isFile()) {
			return FileUtil.readAll(file);
		}
		throw new IOException("无法读取媒体：" + ref);
	}

	private static String describe(String ref) {
		if (ref == null) {
			return "null";
		}
		return ref.length() > 80 ? ref.substring(0, 80) + "..." : ref;
	}

	/** 为二进制上传生成文件名：data URL 按 MIME 推断扩展名，本地文件沿用原名。 */
	private static String fileNameFor(String ref, int fileType) {
		if (ref != null && ref.startsWith("data:")) {
			int colon = ref.indexOf(':');
			int end = ref.indexOf(';');
			if (end < 0) {
				end = ref.indexOf(',');
			}
			String mime = end > colon ? ref.substring(colon + 1, end) : "";
			return "file." + extensionFor(mime, fileType);
		}
		if (ref != null) {
			String name = new File(ref).getName();
			if (!name.isEmpty()) {
				return name;
			}
		}
		return "file." + extensionFor("", fileType);
	}

	private static String extensionFor(String mime, int fileType) {
		if (mime != null) {
			if (mime.contains("png")) {
				return "png";
			}
			if (mime.contains("jpeg") || mime.contains("jpg")) {
				return "jpg";
			}
			if (mime.contains("gif")) {
				return "gif";
			}
			if (mime.contains("webp")) {
				return "webp";
			}
			if (mime.contains("mp4")) {
				return "mp4";
			}
			if (mime.contains("mpeg") || mime.contains("mp3")) {
				return "mp3";
			}
			if (mime.contains("wav")) {
				return "wav";
			}
			if (mime.contains("ogg")) {
				return "ogg";
			}
			if (mime.contains("silk")) {
				return "silk";
			}
		}
		switch (fileType) {
		case FILE_TYPE_IMAGE:
			return "jpg";
		case FILE_TYPE_VIDEO:
			return "mp4";
		case FILE_TYPE_VOICE:
			return "silk";
		default:
			return "bin";
		}
	}

	private static CompletableFuture<String> failedFuture(Throwable error) {
		CompletableFuture<String> future = new CompletableFuture<>();
		future.completeExceptionally(error);
		return future;
	}

	// ==================== WebSocket ====================

	private final class GatewayClient extends WebSocketClient {
		GatewayClient(URI serverUri) {
			super(serverUri);
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
			gatewayThread.set(Boolean.TRUE);
			try {
				handleFrame(message);
			} catch (Throwable t) {
				t.printStackTrace();
			} finally {
				gatewayThread.set(Boolean.FALSE);
			}
		}

		@Override
		public void onMessage(ByteBuffer bytes) {
			// 网关只发文本帧
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			if (this != client) {
				// 旧连接延迟到达的关闭事件，不要影响新连接
				return;
			}
			ready = false;
			lastCloseCode = code;
			lastCloseReason = reason == null ? "" : reason;
			// 4006/4007/4008/4009 都表示当前会话/序号已不可继续使用，
			// 下次必须重新 Identify；否则会一直 Resume 一个过期会话，反复 4009。
			if (code == 4006 || code == 4007 || code == 4008 || code == 4009) {
				sessionId = null;
				hasSeq = false;
				lastSeq = -1;
				System.out.println("[QQ官方] 会话已失效（关闭码 " + code + "），下次改为重新 Identify");
			}
			stopHeartbeat();
			ChatEventHandler current = handler;
			if (current != null) {
				try {
					current.onClose(code, reason, remote);
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
			if (!manualClose) {
				if (isFatalCloseCode(code)) {
					System.err.println("[QQ官方] 网关关闭码 " + code + " 不可重试，已停止自动重连：" + reason);
				} else if (!connecting) {
					if (code == 4099) {
						System.out.println("[QQ官方] 网关 4099 连接超时，自动重连");
					}
					scheduleReconnect();
				}
			}
		}

		@Override
		public void onError(Exception ex) {
			if (this != client) {
				return;
			}
			ChatEventHandler current = handler;
			if (current != null) {
				try {
					current.onError(ex);
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
			System.err.println("[QQ官方] 网关异常：" + ex);
			forceReconnect("网关异常");
		}
	}
}
