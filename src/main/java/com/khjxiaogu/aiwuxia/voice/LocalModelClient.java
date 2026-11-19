package com.khjxiaogu.aiwuxia.voice;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.voice.protocol.LocalModelProtocol;
import com.khjxiaogu.aiwuxia.voice.transport.LocalModelTransport;
import com.khjxiaogu.aiwuxia.voice.transport.LocalModelTransportFactory;
import com.khjxiaogu.aiwuxia.voice.transport.WebSocketTransportFactory;

/**
 * 本地模型连接客户端（统一会话管理器）。
 * <p>
 * 一条 {@link LocalModelTransport} 连接 + 一张“模型类型 → 请求处理器”表：所有本地模型
 * （SDXL / SVS / QWEN / H3 / AUDIO）共用同一个连接，由本类统一分发、上报进度、做健康检查与重连，
 * 不再由每个模型各持一条连接。
 * </p>
 * <ul>
 *   <li>模型类型通过连接 URL 的 {@code model_type} 参数初始声明，连接后可随时用
 *       {@link #setModelTypes(Collection)} 发送 {@code capabilities} 报文动态改变；</li>
 *   <li>收到服务器请求时按请求里的 {@code type} 字段挑选处理器；</li>
 *   <li>请求处理在独立线程池上执行，WebSocket 读线程只负责收发报文：长任务（生图/视频）
 *       不会因为读线程无法应答 ping/pong 而被判定掉线；</li>
 *   <li>每个请求都记住来源连接，结果、进度与错误只回给该连接；即使执行期间发生过重连，
 *       也不会把结果投递给另一个接收端；</li>
 *   <li>处理期间按固定间隔向服务器上报 {@code progress} 报文（状态 + 可选进度），
 *       服务器据此延长该任务的超时时间；</li>
 *   <li>连接断开后按重连间隔自动重连，重连时会用当前最新的能力集合重建地址；</li>
 *   <li>每种模型可单独注册健康检查：连续失败只把该类型从能力集合移除，不影响连接与其它模型，
 *       恢复后自动重新加入。</li>
 * </ul>
 * <p>
 * 网络传输细节由 {@link LocalModelTransport} 负责（默认
 * {@link com.khjxiaogu.aiwuxia.voice.transport.WebSocketLocalModelTransport}），报文格式由
 * {@link LocalModelProtocol} 负责，本类不直接依赖 WebSocket 或具体报文结构。
 * </p>
 */
public class LocalModelClient {

	/** 默认健康检查间隔：30 秒。 */
	public static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 30_000L;
	/** 默认单次健康检查超时：5 秒。 */
	public static final int DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 5_000;
	/** 默认连续失败阈值：3 次（按默认间隔约 90 秒判定该模型不可用）。 */
	public static final int DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD = 3;
	/** 进度心跳默认间隔：20 秒。 */
	public static final long DEFAULT_PROGRESS_INTERVAL_MS = 20_000L;

	/**
	 * 详细调试开关：{@code -DlocalModelDebug=true} 时额外打印心跳、进度等高频日志。
	 * <p>
	 * 生命周期、请求分发、结果回传、连接断开这些关键节点无论开关都会打印，
	 * 用于定位“请求收到了、生成完了、结果却没回来”这类无日志的静默失败。
	 * </p>
	 */
	private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("localModelDebug", "false"));
	/** 单次处理超过该时长时打印一次“仍在生成”的提示。 */
	private static final long LONG_TASK_WARN_MS = 60_000L;
	/** 掉线提示的最小间隔：连接反复抖动时把多条掉线合并成一条。 */
	private static final long OUTAGE_LOG_INTERVAL_MS = 60_000L;
	/** 断线期间最多暂存多少个结果。 */
	private static final int MAX_BUFFERED_RESULTS = 8;
	/** 断线期间暂存结果的总字节上限。 */
	private static final long MAX_BUFFERED_BYTES = 256L * 1024 * 1024;

	/**
	 * 本地服务健康检查接口。实现方在本地服务可用时返回 {@code true}。
	 */
	@FunctionalInterface
	public interface HealthCheck {
		/**
		 * 检查本地服务是否仍可用。
		 *
		 * @return {@code true} 表示本地服务可用；返回 {@code false} 或抛出异常都按失败计
		 * @throws Exception 探测过程中的任何异常都视为本次检查失败
		 */
		boolean isHealthy() throws Exception;
	}

	/**
	 * 请求处理器接口。收到服务端请求时回调。
	 */
	@FunctionalInterface
	public interface RequestHandler {
		/**
		 * 处理模型请求。
		 *
		 * @param request 完整的请求 JSON 对象
		 * @return 处理结果，包含数据和格式；返回 null 表示处理失败
		 */
		Response handle(JsonObject request);

		/**
		 * 处理模型请求，并可上报任务进度。
		 * <p>
		 * 默认忽略进度上报、退回 {@link #handle(JsonObject)}；需要“排队/执行中”协商的处理器
		 * 覆盖本方法即可（见 {@link ProgressReporter}）。
		 * </p>
		 *
		 * @param request  完整的请求 JSON 对象
		 * @param progress 进度上报入口，永不为 null
		 * @return 处理结果，包含数据和格式；返回 null 表示处理失败
		 */
		default Response handle(JsonObject request, ProgressReporter progress) {
			return handle(request);
		}
	}

	/**
	 * 进度上报入口。
	 * <p>
	 * 处理器在处理任务期间可以用它向服务器汇报状态，服务器据此把该任务的超时时间延后。
	 * 状态语义是固定的：
	 * </p>
	 * <ul>
	 *   <li>{@link #queued(String)}：<b>排队</b>，即正在等待本地 {@link com.khjxiaogu.aiwuxia.tools.ResourceLock}；
	 *       不占资源锁的任务（例如本机 TTS）不会上报这个状态；</li>
	 *   <li>{@link #running(String)}：<b>执行中</b>，即请求已经正式下发给对应的本地软件
	 *       （SD WebUI / localemp / ComfyUI / TTS）；</li>
	 *   <li>{@link #progress(double, String)}：执行中并附带可选进度。</li>
	 * </ul>
	 * <p>
	 * 此外本类会按固定间隔自动发送心跳，即使处理器没有主动上报，长任务也不会被服务器判超时。
	 * </p>
	 */
	public interface ProgressReporter {
		/** 什么都不做的上报器，用于不需要协商的调用路径。 */
		ProgressReporter NOOP = new ProgressReporter() {
			@Override
			public void queued(String detail) {
			}

			@Override
			public void running(String detail) {
			}

			@Override
			public void progress(double ratio, String detail) {
			}
		};

		/**
		 * 汇报“排队中”：只表示正在等待本地资源锁，不包含生成时间。
		 *
		 * @param detail 说明，可为 null
		 */
		void queued(String detail);

		/**
		 * 汇报“执行中”：请求已经正式下发给对应的本地软件（SD WebUI / localemp / ComfyUI / TTS）。
		 *
		 * @param detail 说明，可为 null
		 */
		void running(String detail);

		/**
		 * 汇报“执行中”并附带进度。
		 *
		 * @param ratio  进度比例，0~1
		 * @param detail 说明，可为 null
		 */
		void progress(double ratio, String detail);
	}

	/**
	 * 请求处理结果。
	 */
	public static class Response {
		public final byte[] data;
		public final String format;
		/**
		 * true 表示结果由本地服务通过其它通道（例如 HTTP 回调）完成，本客户端不再回发响应。
		 * SVS 的 localemp.py 就是这种模式：它把音频直接 POST 回服务器，而不是回给本客户端。
		 */
		public final boolean external;

		public Response(byte[] data, String format) {
			this(data, format, false);
		}

		private Response(byte[] data, String format, boolean external) {
			this.data = data;
			this.format = format;
			this.external = external;
		}

		/**
		 * 创建一个“由外部通道回传结果”的响应：不向服务器发送回复。
		 *
		 * @return 外部完成响应
		 */
		public static Response external() {
			return new Response(null, null, true);
		}
	}

	/** 传输工厂：替换实现即可切换协议。 */
	private final LocalModelTransportFactory transportFactory;
	/** 服务器地址（不含 {@code model_type}，重连时按当前能力集合重新拼接）。 */
	private final URI serverUri;
	/** 连接头等传输元数据。 */
	private final Map<String, String> headers;
	/** 断线后是否自动重连。 */
	private final boolean autoReconnect;
	/** 重连间隔（毫秒）。 */
	private final long reconnectIntervalMs;
	/** 进度心跳间隔（毫秒）。 */
	private final long progressIntervalMs = readProgressInterval();

	/** 模型类型 → 请求处理器。 */
	private final Map<ModelType, RequestHandler> handlers = new ConcurrentHashMap<>();
	/** 模型类型 → 本地服务健康检查（可选）。 */
	private final Map<ModelType, HealthCheck> healthChecks = new ConcurrentHashMap<>();
	/** 模型类型 → 连续健康检查失败次数。 */
	private final Map<ModelType, AtomicInteger> healthFailures = new ConcurrentHashMap<>();
	/** 当前连接声明的能力集合。 */
	private final Set<ModelType> modelTypes = ConcurrentHashMap.newKeySet();

	/** 当前网络传输；重连时会被替换。 */
	private volatile LocalModelTransport transport;
	/** 是否已连接。 */
	private volatile boolean opened = false;
	/** 是否已被显式关闭。 */
	private volatile boolean closed = false;

	/** 保证重连任务只排一次。 */
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
	/** 本次掉线是否已经提示过：断线期间的重连循环里只提示一次，避免每秒刷屏。 */
	private final AtomicBoolean outageLogged = new AtomicBoolean(false);
	/** 是否成功连上过：用于区分首次连接与重连。 */
	private volatile boolean everConnected = false;
	/** 上次打印掉线提示的时间：频繁抖动时把多条掉线合并成一条。 */
	private volatile long lastOutageLogAt = 0L;
	/** 是否已经启动过健康检查（重连后不重复启动）。 */
	private final AtomicBoolean healthMonitorStarted = new AtomicBoolean(false);
	/** 正在处理中的请求数：本地服务忙时跳过本轮检查，避免长任务被误判成掉线。 */
	private final AtomicInteger activeRequests = new AtomicInteger();

	private final ScheduledExecutorService reconnectTimer = daemonScheduler("local-model-client-reconnect");
	private final ScheduledExecutorService healthCheckTimer = daemonScheduler("local-model-client-health");
	private final ScheduledExecutorService progressTimer = daemonScheduler("local-model-client-progress");
	/** 请求处理线程池。
	 * <p>
	 * 处理器可能阻塞几分钟（SD 生图、视频合成），必须在线程池里跑：占住 WebSocket 读线程会让
	 * 连接无法应答 ping/pong，被 Java-WebSocket 的掉线检测以 1006 断开。
	 * </p>
	 */
	private final ExecutorService handlerExecutor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "local-model-client-handler");
		t.setDaemon(true);
		return t;
	});

	/**
	 * 客户端标识：同一进程内稳定，随连接握手带给服务器。
	 * <p>
	 * 服务器用它认出“重连后的还是同一个客户端”，从而接受断线期间暂存、重连后补发的结果。
	 * </p>
	 */
	private final String clientId = UUID.randomUUID().toString();
	/** 等待重连后补发的结果/错误报文；本对象只对应一个接收地址，所以缓冲区天然按地址隔离。 */
	private final Deque<PendingResult> outbox = new ArrayDeque<>();
	/** 保护 {@link #outbox} 与 {@link #outboxBytes}。 */
	private final Object outboxLock = new Object();
	/** 缓冲区当前占用字节数。 */
	private final AtomicLong outboxBytes = new AtomicLong();

	private static ScheduledExecutorService daemonScheduler(String name) {
		return Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * 使用默认 WebSocket 传输。
	 *
	 * @param serverUri           部署通道地址
	 * @param httpHeaders         连接元数据（HTTP 头），可为 null
	 * @param autoReconnect       是否自动重连
	 * @param reconnectIntervalMs 重连间隔
	 */
	public LocalModelClient(URI serverUri, Map<String, String> httpHeaders, boolean autoReconnect,
			long reconnectIntervalMs) {
		this(WebSocketTransportFactory.INSTANCE, serverUri, httpHeaders, autoReconnect, reconnectIntervalMs);
	}

	/**
	 * 使用指定传输实现。
	 *
	 * @param transportFactory    传输工厂
	 * @param serverUri           部署通道地址
	 * @param headers             连接元数据，可为 null
	 * @param autoReconnect       是否自动重连
	 * @param reconnectIntervalMs 重连间隔
	 */
	public LocalModelClient(LocalModelTransportFactory transportFactory, URI serverUri,
			Map<String, String> headers, boolean autoReconnect, long reconnectIntervalMs) {
		if (transportFactory == null) {
			throw new IllegalArgumentException("transportFactory 不能为空");
		}
		this.transportFactory = transportFactory;
		this.serverUri = serverUri;
		this.headers = headers == null ? Collections.emptyMap() : headers;
		this.autoReconnect = autoReconnect;
		this.reconnectIntervalMs = reconnectIntervalMs;
	}

	/** 读取进度心跳间隔，非法值回退到默认值。 */
	private static long readProgressInterval() {
		String raw = System.getProperty("localModelProgressIntervalMs", "").trim();
		if (raw.isEmpty()) {
			return DEFAULT_PROGRESS_INTERVAL_MS;
		}
		try {
			long value = Long.parseLong(raw);
			return value >= 1000L ? value : DEFAULT_PROGRESS_INTERVAL_MS;
		} catch (NumberFormatException ex) {
			return DEFAULT_PROGRESS_INTERVAL_MS;
		}
	}

	// ==================== 能力集合 / 处理器 ====================

	/**
	 * 注册一个模型类型的请求处理器（不带健康检查）。
	 *
	 * @param type    模型类型
	 * @param handler 处理器
	 * @return 本管理器
	 */
	public LocalModelClient register(ModelType type, RequestHandler handler) {
		return register(type, handler, null);
	}

	/**
	 * 注册一个模型类型的请求处理器与健康检查。
	 * <p>
	 * 注册即把该类型加入当前能力集合。健康检查连续失败达到阈值时只把该类型从能力集合移除
	 * （发送 {@code capabilities} 报文），连接与其它模型不受影响；恢复后自动重新加入。
	 * </p>
	 *
	 * @param type        模型类型
	 * @param handler     处理器
	 * @param healthCheck 健康检查，可为 null
	 * @return 本管理器
	 */
	public LocalModelClient register(ModelType type, RequestHandler handler, HealthCheck healthCheck) {
		if (type == null || handler == null) {
			return this;
		}
		handlers.put(type, handler);
		if (healthCheck != null) {
			healthChecks.put(type, healthCheck);
			healthFailures.put(type, new AtomicInteger());
		}
		modelTypes.add(type);
		return this;
	}

	/** 当前能力集合（只读快照，保持注册顺序）。 */
	public Set<ModelType> getModelTypes() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(modelTypes));
	}

	/** 当前能力集合的字符串形式（小写），用于日志与报文。 */
	public List<String> getModelTypeNames() {
		List<String> names = new ArrayList<>();
		for (ModelType type : new LinkedHashSet<>(modelTypes)) {
			names.add(type.name().toLowerCase());
		}
		return names;
	}

	/**
	 * 改变当前连接的能力集合。
	 * <p>
	 * 已连接时会立即发送能力集合报文，服务器据此更新该连接在各模型类型连接池里的登记；
	 * 重连时新的能力集合会写进 {@code model_type} 参数，因此不需要主动断开重连。
	 * </p>
	 *
	 * @param types 新的能力集合；只有已注册处理器的类型会被保留
	 */
	public void setModelTypes(Collection<ModelType> types) {
		Set<ModelType> updated = new LinkedHashSet<>();
		if (types != null) {
			for (ModelType type : types) {
				if (type != null && handlers.containsKey(type)) {
					updated.add(type);
				}
			}
		}
		modelTypes.clear();
		modelTypes.addAll(updated);
		System.out.println("[LocalModelClient] 能力集合更新：" + getModelTypeNames());
		sendCapabilitiesIfOpen();
	}

	/** 已连接时发送一次能力集合报文。 */
	private void sendCapabilitiesIfOpen() {
		LocalModelTransport current = transport;
		if (current == null || !current.isOpen()) {
			return;
		}
		current.send(LocalModelProtocol.capabilities(getModelTypeNames()).toString());
	}

	// ==================== 连接生命周期 ====================

	/**
	 * 阻塞建立连接。
	 *
	 * @return 是否连接成功
	 * @throws InterruptedException 建连过程被中断
	 */
	public boolean connectBlocking() throws InterruptedException {
		if (closed) {
			return false;
		}
		boolean connected = createAndConnect();
		opened = connected;
		if (!connected && reportOutage()) {
			// onError 可能已经提示过同一次失败；这里保证一次连接失败只输出一条
			System.err.println("[LocalModelClient] 连接失败：" + serverUri + " types=" + getModelTypeNames()
					+ (autoReconnect ? "，稍后自动重连（本次掉线只提示这一条）" : ""));
		}
		return connected;
	}

	/** 按当前能力集合创建传输并连接。 */
	private boolean createAndConnect() throws InterruptedException {
		// client_id 让服务器把重连后的连接认成同一个客户端，断线期间暂存的结果才能在重连后补交
		String base = addQueryParam(serverUri.toString(), "client_id", clientId);
		LocalModelTransport created = transportFactory.create(
				addModelType(base, new LinkedHashSet<>(modelTypes)), headers);
		created.setListener(new LocalModelTransport.Listener() {
			@Override
			public void onOpen() {
				opened = true;
				// 掉线后恢复：只在这里提示一次；重连过程中的反复失败不逐次输出
				if (outageLogged.getAndSet(false)) {
					System.out.println("[LocalModelClient] 连接已恢复：" + created.getUri() + " types="
							+ getModelTypeNames());
				} else if (!everConnected) {
					System.out.println("[LocalModelClient] connected to " + created.getUri() + " types="
							+ getModelTypeNames());
				} else if (DEBUG) {
					System.out.println("[LocalModelClient] 已重新连接：" + created.getUri() + " types="
							+ getModelTypeNames());
				}
				everConnected = true;
				startHealthMonitor();
				// 断线期间暂存的结果在这里补发（只发给本客户端对应的接收地址）
				flushOutbox();
			}

			@Override
			public void onMessage(String message) {
				dispatch(message);
			}

			@Override
			public void onBinary(byte[] message) {
				// 服务器不向连接器发送文件，忽略二进制报文
			}

			@Override
			public void onClose(int code, String reason, boolean remote) {
				opened = false;
				if (closed) {
					// 主动关闭：已经由 closeService() 提示过，不再重复
				} else if (reportOutage()) {
					System.err.println("[LocalModelClient] 连接断开：code=" + code + " reason=" + reason
							+ (remote ? "（远端关闭）" : "（本地关闭）")
							+ (autoReconnect ? "，将自动重连（本次掉线只提示这一条）" : "，不会自动重连") + "："
							+ serverUri);
				} else if (DEBUG) {
					System.err.println("[LocalModelClient] 连接再次断开：code=" + code + " reason=" + reason);
				}
				scheduleReconnect();
			}

			@Override
			public void onError(Throwable error) {
				opened = false;
				if (closed) {
					// 主动关闭：忽略随之而来的异常
				} else if (reportOutage()) {
					System.err.println("[LocalModelClient] 连接异常：" + error
							+ (autoReconnect ? "，将自动重连（本次掉线只提示这一条）" : "") + "：" + serverUri);
				} else if (DEBUG) {
					System.err.println("[LocalModelClient] 连接再次异常：" + error);
				}
				scheduleReconnect();
			}
		});
		transport = created;
		boolean connected = created.connectBlocking();
		if (connected) {
			opened = true;
			startHealthMonitor();
		}
		return connected;
	}

	/** 当前是否已连接。 */
	public boolean isOpen() {
		LocalModelTransport current = transport;
		return opened && current != null && current.isOpen();
	}

	/** 连接地址（不含能力集合参数）。 */
	public URI getUri() {
		return serverUri;
	}

	/**
	 * 安排一次重连。
	 * <p>
	 * 使用去重标志保证同一时刻只有一个重连任务；重连失败会在任务内再次安排，
	 * 直到连接成功或客户端被显式关闭。
	 * </p>
	 */
	private void scheduleReconnect() {
		if (!autoReconnect || closed || opened) {
			return;
		}
		if (!reconnectScheduled.compareAndSet(false, true)) {
			return;
		}
		reconnectTimer.schedule(() -> {
			reconnectScheduled.set(false);
			if (closed || opened) {
				return;
			}
			if (DEBUG) {
				System.out.println("[LocalModelClient] 尝试重连：" + serverUri + " types=" + getModelTypeNames());
			}
			try {
				boolean connected = createAndConnect();
				opened = connected;
				if (!connected) {
					// 掉线提示已经在断开时打过一次，重连周期性失败不再重复输出
					if (DEBUG) {
						System.err.println("[LocalModelClient] 重连失败，" + reconnectIntervalMs + "ms 后重试："
								+ serverUri);
					}
					scheduleReconnect();
				}
			} catch (Throwable t) {
				if (DEBUG) {
					System.err.println("[LocalModelClient] 重连异常：" + t + "：" + serverUri);
				}
				scheduleReconnect();
			}
		}, reconnectIntervalMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * 判断本次掉线是否值得打印提示。
	 * <p>
	 * 同一次掉线只提示一次；断线期间每秒一次的重连失败不再输出。连接抖动（刚恢复又断开）时
	 * 以 {@value #OUTAGE_LOG_INTERVAL_MS} 毫秒为最小间隔合并，避免日志被反复刷屏。
	 * </p>
	 *
	 * @return true 表示应该打印这一条掉线提示
	 */
	private boolean reportOutage() {
		long now = System.currentTimeMillis();
		if (now - lastOutageLogAt < OUTAGE_LOG_INTERVAL_MS) {
			return false;
		}
		lastOutageLogAt = now;
		outageLogged.set(true);
		return true;
	}

	/**
	 * 关闭连接，停止自动重连与健康检查；重复调用无副作用。
	 */
	public void closeService() {
		synchronized (this) {
			if (closed) {
				return;
			}
			closed = true;
		}
		System.err.println("[LocalModelClient] closed");
		reconnectTimer.shutdownNow();
		healthCheckTimer.shutdownNow();
		progressTimer.shutdownNow();
		handlerExecutor.shutdownNow();
		clearOutbox();
		LocalModelTransport current = transport;
		if (current != null) {
			try {
				current.close();
			} catch (Throwable ignored) {
				// 关闭失败无需处理
			}
		}
	}

	// ==================== 健康检查（按模型类型） ====================

	/** 连接建立后启动健康检查；只检查注册了检查的类型。 */
	private void startHealthMonitor() {
		if (healthChecks.isEmpty() || closed) {
			return;
		}
		if (!healthMonitorStarted.compareAndSet(false, true)) {
			return;
		}
		System.out.println("[LocalModelClient] 本地服务健康检查已启动：每 " + DEFAULT_HEALTH_CHECK_INTERVAL_MS
				+ "ms 检查一次，连续失败 " + DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD + " 次把该类型移出能力集合");
		healthCheckTimer.scheduleWithFixedDelay(this::runHealthCheck, DEFAULT_HEALTH_CHECK_INTERVAL_MS,
				DEFAULT_HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	/** 执行一轮健康检查：按模型类型分别计数，不影响连接本身。 */
	private void runHealthCheck() {
		if (closed) {
			return;
		}
		// 本地服务正在处理任务时跳过本轮，避免把长任务当成掉线
		if (activeRequests.get() > 0) {
			return;
		}
		for (Map.Entry<ModelType, HealthCheck> entry : healthChecks.entrySet()) {
			ModelType type = entry.getKey();
			boolean healthy;
			String failureReason = null;
			try {
				healthy = entry.getValue().isHealthy();
			} catch (Throwable t) {
				healthy = false;
				failureReason = t.toString();
			}
			AtomicInteger failures = healthFailures.computeIfAbsent(type, k -> new AtomicInteger());
			if (healthy) {
				boolean recovered = failures.getAndSet(0) > 0;
				if (recovered && !modelTypes.contains(type)) {
					modelTypes.add(type);
					System.out.println("[LocalModelClient] " + type + " 本地服务已恢复，重新加入能力集合");
					sendCapabilitiesIfOpen();
				}
				continue;
			}
			int count = failures.incrementAndGet();
			// 只在第一次失败时提示：之后每 30 秒一轮的重复失败不再刷屏，达到阈值再报一次“已移出”
			if (count == 1) {
				System.err.println("[LocalModelClient] " + type + " 健康检查失败，连续 "
						+ DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD + " 次后移出能力集合"
						+ (failureReason != null ? "：" + failureReason : ""));
			} else if (DEBUG) {
				System.err.println("[LocalModelClient] " + type + " 健康检查仍失败（" + count + "/"
						+ DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD + "）"
						+ (failureReason != null ? "：" + failureReason : ""));
			}
			if (count >= DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD && modelTypes.remove(type)) {
				System.err.println("[LocalModelClient] " + type + " 本地服务不可用，已从能力集合移除："
						+ getModelTypeNames());
				sendCapabilitiesIfOpen();
			}
		}
	}

	// ==================== 报文处理 ====================

	/** 处理一条服务器报文（应用协议见 {@link LocalModelProtocol}）。 */
	private void dispatch(String message) {
		JsonObject request = LocalModelProtocol.parse(message);
		if (request == null) {
			if (DEBUG) {
				System.err.println("[LocalModelClient] 收到无法解析的报文："
						+ (message == null ? "null"
								: (message.length() > 300 ? message.substring(0, 300) + "…" : message)));
			}
			return;
		}
		if (LocalModelProtocol.isCapabilities(request)) {
			System.out.println("[LocalModelClient] 服务器已确认能力集合：" + LocalModelProtocol.modelTypes(request));
			return;
		}
		String reqid = LocalModelProtocol.reqid(request);
		if (reqid.isEmpty()) {
			return;
		}
		String typeName = LocalModelProtocol.requestModelType(request);
		ModelType modelType = ModelType.fromString(typeName);
		RequestHandler handler = modelType == null ? null : handlers.get(modelType);
		// 报文长度、path、来源服务器一并打印：连接器可能同时接入多个服务器，
		// 排查“结果投错接收端”时必须能看出这条任务是从哪来的
		System.out.println("[LocalModelClient] received request: " + reqid + " type=" + typeName
				+ " 报文=" + (message == null ? 0 : message.length()) + "B 来源=" + serverUri
				+ (request.has("path") && !request.get("path").isJsonNull()
						? " path=" + request.get("path").getAsString() : ""));
		if (handler == null) {
			System.err.println("[LocalModelClient] 没有该模型类型的处理器，回错误：reqid=" + reqid + " type="
					+ typeName + " 已注册=" + getModelTypeNames());
			sendReply(LocalModelProtocol.error(reqid, "no handler for model type: " + typeName));
			return;
		}
		// 处理请求可能阻塞几分钟（SD 生图、视频合成）：必须离开 WebSocket 读线程，
		// 否则读线程无法应答 ping/pong，会被 Java-WebSocket 的掉线检测以 1006 断开。
		// 同时记住请求来自哪条连接：结果、进度、错误都必须回给同一个接收端，
		// 不能因为期间发生了重连就发到新连接上。
		final LocalModelTransport source = transport;
		try {
			handlerExecutor.execute(() -> handleRequest(handler, reqid, typeName, request, source));
		} catch (RejectedExecutionException ex) {
			// 客户端已关闭：把失败回传给服务器，避免它白等到超时
			System.err.println("[LocalModelClient] 处理线程已关闭，拒绝任务：reqid=" + reqid + " type=" + typeName);
			sendReply(source, LocalModelProtocol.error(reqid, "client is closing"));
		}
	}

	/**
	 * 在处理器线程上执行一条模型请求，并回传结果或错误。
	 *
	 * @param handler  模型类型的处理器
	 * @param reqid    请求 ID
	 * @param typeName 模型类型名（日志用）
	 * @param request  完整请求
	 * @param source   收到该请求的连接；结果只回传给这条连接
	 */
	private void handleRequest(RequestHandler handler, String reqid, String typeName, JsonObject request,
			LocalModelTransport source) {
		try {
			activeRequests.incrementAndGet();
			ProgressState state = new ProgressState();
			final String heartbeatReqid = reqid;
			final String taskTypeName = typeName;
			final LocalModelTransport replyTo = source;
			ScheduledFuture<?> heartbeat = progressTimer.scheduleWithFixedDelay(
					() -> heartbeatProgress(replyTo, heartbeatReqid, state), progressIntervalMs, progressIntervalMs,
					TimeUnit.MILLISECONDS);
			// 长任务提示：请求已经离开读线程，这里只用于说明本地软件仍在生成
			ScheduledFuture<?> longTaskWarning = progressTimer.schedule(
					() -> System.out.println("[LocalModelClient] 任务已执行 " + (LONG_TASK_WARN_MS / 1000)
							+ "s 仍未返回，本地软件仍在生成：reqid=" + heartbeatReqid + " type=" + taskTypeName),
					LONG_TASK_WARN_MS, TimeUnit.MILLISECONDS);
			long startedAt = System.currentTimeMillis();
			Response response;
			try {
				response = handler.handle(request, new Reporter(replyTo, reqid, state));
			} finally {
				longTaskWarning.cancel(false);
				heartbeat.cancel(false);
				activeRequests.decrementAndGet();
			}
			long elapsed = System.currentTimeMillis() - startedAt;
			System.out.println("[LocalModelClient] 处理完成：reqid=" + reqid + " type=" + typeName + " 用时="
					+ elapsed + "ms 结果=" + (response == null ? "null"
							: (response.external ? "external"
									: (response.data == null ? "null" : response.data.length + "B/" + response.format))));
			if (response != null && response.external) {
				// 结果由本地服务经其它通道回传（例如 localemp.py 的 HTTP 回调），这里不能回复
				return;
			}
			if (response != null && response.data != null) {
				// 文件按二进制分块发送，每块内容体最大 5 MiB，不再塞进文本 JSON 的 Base64
				sendFileChunks(replyTo, reqid, response.format, response.data);
			} else {
				System.err.println("[LocalModelClient] 处理器没有返回结果，回错误：reqid=" + reqid);
				sendReply(replyTo, LocalModelProtocol.error(reqid, "handler returned null"));
			}
		} catch (Exception e) {
			System.err.println("[LocalModelClient] 处理请求出错：reqid=" + reqid + " 错误=" + e);
			e.printStackTrace();
			if (!reqid.isEmpty()) {
				// 把真实原因回传给服务端，避免只看到笼统的 "handler error"；错误同样回到请求来源连接
				sendReply(source, LocalModelProtocol.error(reqid, rootMessage(e)));
			}
		}
	}

	/** 发送一条报文到当前连接（只用于不绑定具体任务的报文）。 */
	private void sendReply(JsonObject reply) {
		sendReply(transport, reply);
	}

	/**
	 * 把一条报文发回它所属的连接；连接已断开时暂存，等重连后补发。
	 *
	 * @param target 目标连接；{@code null} 表示当时没有连接
	 * @param reply  报文
	 */
	private void sendReply(LocalModelTransport target, JsonObject reply) {
		if (target == null || !target.isOpen()) {
			String reqid = LocalModelProtocol.reqid(reply);
			if (!reqid.isEmpty()) {
				enqueueReply(reqid, reply);
			} else {
				System.err.println("[LocalModelClient] 接收端已断开，报文未发送：" + abbreviate(reply));
			}
			return;
		}
		if (DEBUG) {
			System.out.println("[LocalModelClient] 发送报文：" + abbreviate(reply));
		}
		try {
			target.send(reply.toString());
		} catch (Throwable t) {
			System.err.println("[LocalModelClient] 报文发送失败：" + t + " " + abbreviate(reply));
			String reqid = LocalModelProtocol.reqid(reply);
			if (!reqid.isEmpty()) {
				enqueueReply(reqid, reply);
			}
		}
	}

	/** 截断过长的报文，避免把大段数据写进日志。 */
	private static String abbreviate(JsonObject message) {
		if (message == null) {
			return "null";
		}
		String text = message.toString();
		return text.length() > 300 ? text.substring(0, 300) + "…" : text;
	}

	/**
	 * 把结果文件发回请求来源连接；连接已断开时暂存，等重连后补发。
	 *
	 * @param target 请求来源连接
	 * @param reqid  请求 ID
	 * @param format 结果格式
	 * @param data   完整文件字节
	 */
	private void sendFileChunks(LocalModelTransport target, String reqid, String format, byte[] data) {
		String resolvedFormat = format == null || format.trim().isEmpty() ? "bin" : format.trim();
		byte[] payload = data == null ? new byte[0] : data;
		if (target == null || !target.isOpen()) {
			enqueueResult(reqid, resolvedFormat, payload);
			return;
		}
		if (!writeChunks(target, reqid, resolvedFormat, payload)) {
			// 发送中途断开：整份结果重新入队，重连后从头再发（服务端会丢弃旧的不完整分块）
			enqueueResult(reqid, resolvedFormat, payload);
		}
	}

	/**
	 * 真正把分块写到连接上。
	 *
	 * @param target 目标连接
	 * @param reqid  请求 ID
	 * @param format 结果格式
	 * @param payload 结果字节
	 * @return 全部写完返回 true；连接在写入过程中失效返回 false
	 */
	private boolean writeChunks(LocalModelTransport target, String reqid, String format, byte[] payload) {
		int chunkSize = LocalModelProtocol.DEFAULT_FILE_CHUNK_SIZE;
		int total = LocalModelProtocol.fileChunkCount(payload.length, chunkSize);
		System.out.println("[LocalModelClient] 开始回传结果：reqid=" + reqid + " format=" + format
				+ " 大小=" + payload.length + "B 分块=" + total + " 接收端=" + serverUri);
		try {
			// 逐块构造并立即发送，避免一次性把整个文件复制成多份分块
			for (int index = 1; index <= total; index++) {
				int offset = (index - 1) * chunkSize;
				int length = Math.min(chunkSize, payload.length - offset);
				byte[] frame = LocalModelProtocol.encodeFileChunk(reqid, format, payload, offset,
						Math.max(0, length), index, total);
				target.sendBinary(frame);
				if (DEBUG) {
					System.out.println("[LocalModelClient] 已发送分块 " + index + "/" + total + "（内容 "
							+ Math.max(0, length) + "B，帧 " + frame.length + "B）：reqid=" + reqid);
				}
			}
			System.out.println("[LocalModelClient] 结果回传完成：reqid=" + reqid);
			return true;
		} catch (IllegalArgumentException ex) {
			System.err.println("[LocalModelClient] 结果分块失败：reqid=" + reqid + " 错误=" + ex.getMessage());
			sendReply(target, LocalModelProtocol.error(reqid, "文件分块失败：" + ex.getMessage()));
			return true;
		} catch (Throwable t) {
			System.err.println("[LocalModelClient] 结果发送中断：reqid=" + reqid + " 错误=" + t);
			return false;
		}
	}

	// ==================== 断线结果缓冲 ====================

	/** 一条等待重连补发的结果或错误报文。 */
	private static final class PendingResult {
		final String reqid;
		final String format;
		final byte[] data;
		final JsonObject reply;

		private PendingResult(String reqid, String format, byte[] data, JsonObject reply) {
			this.reqid = reqid;
			this.format = format;
			this.data = data;
			this.reply = reply;
		}

		long size() {
			return data == null ? 0 : data.length;
		}
	}

	/** 暂存一份结果，等本地址重连后补发。 */
	private void enqueueResult(String reqid, String format, byte[] data) {
		byte[] payload = data == null ? new byte[0] : data;
		synchronized (outboxLock) {
			trimOutboxLocked(payload.length);
			outbox.addLast(new PendingResult(reqid, format, payload, null));
			outboxBytes.addAndGet(payload.length);
		}
		System.err.println("[LocalModelClient] 接收端已断开，结果已暂存（" + payload.length + "B），重连后补发：reqid="
				+ reqid + " 接收端=" + serverUri);
	}

	/** 暂存一份错误/结束报文，等本地址重连后补发。 */
	private void enqueueReply(String reqid, JsonObject reply) {
		synchronized (outboxLock) {
			trimOutboxLocked(0);
			outbox.addLast(new PendingResult(reqid, null, null, reply));
		}
		System.err.println("[LocalModelClient] 接收端已断开，报文已暂存，重连后补发：reqid=" + reqid + " 接收端="
				+ serverUri);
	}

	/** 按条数与字节上限丢弃最早的结果，避免断线期间无限占用内存。 */
	private void trimOutboxLocked(long incomingBytes) {
		while (!outbox.isEmpty()
				&& (outbox.size() >= MAX_BUFFERED_RESULTS
						|| outboxBytes.get() + incomingBytes > MAX_BUFFERED_BYTES)) {
			PendingResult dropped = outbox.pollFirst();
			outboxBytes.addAndGet(-dropped.size());
			System.err.println("[LocalModelClient] 结果缓冲区已满，丢弃最早的结果：reqid=" + dropped.reqid
					+ " 接收端=" + serverUri);
		}
	}

	/** 把缓冲区里的结果按顺序补发到当前连接；中途再次断开就停下，剩下的等下次重连。 */
	private void flushOutbox() {
		LocalModelTransport current = transport;
		if (current == null || !current.isOpen()) {
			return;
		}
		int sent = 0;
		while (true) {
			PendingResult pending;
			synchronized (outboxLock) {
				pending = outbox.pollFirst();
				if (pending == null) {
					break;
				}
				outboxBytes.addAndGet(-pending.size());
			}
			boolean ok;
			try {
				if (pending.reply != null) {
					current.send(pending.reply.toString());
					ok = true;
				} else {
					ok = writeChunks(current, pending.reqid, pending.format, pending.data);
				}
			} catch (Throwable t) {
				ok = false;
			}
			if (!ok) {
				synchronized (outboxLock) {
					outbox.addFirst(pending);
					outboxBytes.addAndGet(pending.size());
				}
				System.err.println("[LocalModelClient] 补发中断，剩余结果继续等待重连：reqid=" + pending.reqid
						+ " 接收端=" + serverUri);
				return;
			}
			sent++;
		}
		if (sent > 0) {
			System.out.println("[LocalModelClient] 已补发 " + sent + " 条暂存结果：接收端=" + serverUri);
		}
	}

	/** 清空缓冲区（进程关闭时调用）。 */
	private void clearOutbox() {
		synchronized (outboxLock) {
			if (!outbox.isEmpty()) {
				System.err.println("[LocalModelClient] 连接已关闭，丢弃 " + outbox.size() + " 条暂存结果：接收端="
						+ serverUri);
			}
			outbox.clear();
			outboxBytes.set(0);
		}
	}

	/**
	 * 当前缓冲中等待补发的结果条数（诊断与自检用）。
	 *
	 * @return 暂存条数
	 */
	public int getBufferedResultCount() {
		synchronized (outboxLock) {
			return outbox.size();
		}
	}

	/**
	 * 把进度上报到请求来源连接。
	 *
	 * @param target 请求来源连接
	 * @param reqid  请求 ID
	 * @param status 状态
	 * @param progress 进度比例，可为 null
	 * @param detail 说明，可为 null
	 */
	private void reportProgress(LocalModelTransport target, String reqid, String status, Double progress,
			String detail) {
		if (target == null) {
			if (DEBUG) {
				System.err.println("[LocalModelClient] 无连接，进度未上报：reqid=" + reqid + " status=" + status);
			}
			return;
		}
		if (!target.isOpen()) {
			if (DEBUG) {
				System.err.println("[LocalModelClient] 来源连接已断开，进度未上报：reqid=" + reqid + " status=" + status);
			}
			return;
		}
		try {
			target.send(LocalModelProtocol.progress(reqid, status, progress, detail).toString());
			if (DEBUG) {
				System.out.println("[LocalModelClient] 进度上报：reqid=" + reqid + " status=" + status
						+ (progress == null ? "" : " " + Math.round(progress * 100) + "%")
						+ (detail == null ? "" : "（" + detail + "）"));
			}
		} catch (Throwable t) {
			// 进度只是协商信息，发送失败不影响任务本身，但失败会让服务端提前判超时，必须能看到
			System.err.println("[LocalModelClient] 进度上报失败：reqid=" + reqid + " status=" + status + " " + t);
		}
	}

	/** 定时心跳：用最近一次上报的状态续期，处理器没有主动上报时按“执行中”续期。 */
	private void heartbeatProgress(LocalModelTransport target, String reqid, ProgressState state) {
		if (DEBUG) {
			System.out.println("[LocalModelClient] 心跳续期：reqid=" + reqid + " status=" + state.status
					+ (state.detail == null ? "" : "（" + state.detail + "）"));
		}
		reportProgress(target, reqid, state.status, state.progress, state.detail);
	}

	/** 一次任务期间最近一次上报的状态，供心跳复用。 */
	private static final class ProgressState {
		private volatile String status = LocalModelProtocol.STATUS_RUNNING;
		private volatile Double progress;
		private volatile String detail;
	}

	/** 绑定 reqid 与来源连接的进度上报器。 */
	private final class Reporter implements ProgressReporter {
		private final LocalModelTransport target;
		private final String reqid;
		private final ProgressState state;

		private Reporter(LocalModelTransport target, String reqid, ProgressState state) {
			this.target = target;
			this.reqid = reqid;
			this.state = state;
		}

		@Override
		public void queued(String detail) {
			state.status = LocalModelProtocol.STATUS_QUEUED;
			state.progress = null;
			state.detail = detail;
			reportProgress(target, reqid, LocalModelProtocol.STATUS_QUEUED, null, detail);
		}

		@Override
		public void running(String detail) {
			state.status = LocalModelProtocol.STATUS_RUNNING;
			state.progress = null;
			state.detail = detail;
			reportProgress(target, reqid, LocalModelProtocol.STATUS_RUNNING, null, detail);
		}

		@Override
		public void progress(double ratio, String detail) {
			state.status = LocalModelProtocol.STATUS_RUNNING;
			state.progress = ratio;
			state.detail = detail;
			reportProgress(target, reqid, LocalModelProtocol.STATUS_RUNNING, ratio, detail);
		}
	}

	/** 取最内层异常的描述，便于回传给服务端。 */
	private static String rootMessage(Throwable ex) {
		Throwable cause = ex;
		while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		if (cause == null) {
			return "unknown error";
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}

	// ==================== 工具方法 ====================

	/**
	 * 在地址后追加一个查询参数。
	 *
	 * @param uri   原地址
	 * @param name  参数名
	 * @param value 参数值（会做 URL 编码）
	 * @return 追加参数后的地址
	 */
	static String addQueryParam(String uri, String name, String value) {
		if (uri == null || name == null || value == null) {
			return uri;
		}
		String fragment = "";
		int fragmentIndex = uri.indexOf('#');
		if (fragmentIndex != -1) {
			fragment = uri.substring(fragmentIndex);
			uri = uri.substring(0, fragmentIndex);
		}
		String param = name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
		int queryIndex = uri.indexOf('?');
		if (queryIndex == -1) {
			uri = uri + "?" + param;
		} else if (queryIndex == uri.length() - 1) {
			uri = uri + param;
		} else {
			uri = uri + "&" + param;
		}
		return uri + fragment;
	}

	/**
	 * 把一个能力集合写进连接地址的 {@code model_type} 参数。
	 *
	 * @param uri        服务器地址
	 * @param modelTypes 能力集合
	 * @return 带 {@code model_type} 的地址
	 */
	public static URI addModelType(String uri, Set<ModelType> modelTypes) {
		if (uri == null) {
			return null;
		}
		StringBuilder value = new StringBuilder();
		if (modelTypes != null) {
			for (ModelType type : modelTypes) {
				value.append(type.name().toLowerCase()).append(',');
			}
		}
		String encodedValue = URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
		String param = "model_type=" + encodedValue;

		String fragment = "";
		int fragmentIndex = uri.indexOf('#');
		if (fragmentIndex != -1) {
			fragment = uri.substring(fragmentIndex);
			uri = uri.substring(0, fragmentIndex);
		}
		int queryIndex = uri.indexOf('?');
		if (queryIndex != -1) {
			if (queryIndex == uri.length() - 1) {
				uri += param;
			} else {
				uri += "&" + param;
			}
		} else {
			uri += "?" + param;
		}
		return URI.create(uri + fragment);
	}

	/**
	 * 创建一个基于 HTTP GET 的健康检查。
	 * <p>
	 * 只要能收到状态码小于 500 的 HTTP 响应就认为本地服务还在：404/401 说明地址或鉴权配错了，
	 * 但服务本身并没有掉线；连接被拒绝、超时、5xx 等才算失败。
	 * </p>
	 *
	 * @param url       健康检查地址，例如 {@code http://127.0.0.1:7860/internal/ping}
	 * @param timeoutMs 连接与读取超时（毫秒），非正数时使用默认值
	 * @return 健康检查实现
	 */
	public static HealthCheck httpHealthCheck(String url, int timeoutMs) {
		if (url == null || url.trim().isEmpty()) {
			throw new IllegalArgumentException("健康检查地址不能为空");
		}
		final URL target;
		try {
			target = new URL(url.trim());
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException("健康检查地址不合法：" + url, e);
		}
		final int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_HEALTH_CHECK_TIMEOUT_MS;
		return () -> {
			HttpURLConnection conn = null;
			try {
				conn = (HttpURLConnection) target.openConnection();
				conn.setRequestMethod("GET");
				conn.setConnectTimeout(timeout);
				conn.setReadTimeout(timeout);
				conn.setInstanceFollowRedirects(false);
				conn.setUseCaches(false);
				int code = conn.getResponseCode();
				return code > 0 && code < 500;
			} finally {
				if (conn != null) {
					conn.disconnect();
				}
			}
		};
	}
}
