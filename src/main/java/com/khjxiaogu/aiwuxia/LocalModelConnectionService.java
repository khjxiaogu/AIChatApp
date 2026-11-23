package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelGateway;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.utils.SiteConfig;
import com.khjxiaogu.aiwuxia.vision.H3LocalModelHandler;
import com.khjxiaogu.aiwuxia.vision.QwenLocalModelHandler;
import com.khjxiaogu.aiwuxia.vision.SDXLLocalModelHandler;
import com.khjxiaogu.aiwuxia.vision.SvsLocalModelHandler;
import com.khjxiaogu.aiwuxia.voice.AudioLocalModelHandler;
import com.khjxiaogu.aiwuxia.voice.VoiceModelLocalServer;

/**
 * 本地模型服务：管理“怎么调用本机模型”，不关心网络细节。
 * <p>
 * 与旧实现不同，本服务不再为每种模型各建一条连接，而是：
 * </p>
 * <ul>
 *   <li>为每条部署通道创建一个 {@link LocalModelClient}（统一管理器），
 *       网络传输由 {@link com.khjxiaogu.aiwuxia.modelproxy.transport.LocalModelTransport} 负责；</li>
 *   <li>把所有模型的请求处理器（SDXL / SVS / QWEN / H3 / 可选 AUDIO）注册到同一个管理器上，
 *       由它按请求的 {@code type} 分发；</li>
 *   <li>能力集合通过连接 URL 的 {@code model_type} 初始声明，运行期可用
 *       {@link #setModelTypes(Collection)} 发送能力集合变更报文。</li>
 * </ul>
 * <p>
 * 两条部署通道：
 * </p>
 * <ul>
 *   <li><b>配置的远端</b>：{@code -D}{@value #SERVER_PROPERTY}（{@code host:port} 或完整地址），
 *       未配置时按 {@link SiteConfig}；</li>
 *   <li><b>本机聊天进程</b>：{@code ws://127.0.0.1:}{@value LocalModelGateway#DEFAULT_PORT}，
 *       由 {@code NapCatAIConnector} 启动的 {@link LocalModelGateway} 提供。</li>
 * </ul>
 * <p>
 * 生成的图片/音频等结果由各处理器回传；本服务本身只负责“调用本地模型”，
 * 资源锁也在这里统一持有，聊天进程不再加锁。
 * </p>
 */
public class LocalModelConnectionService implements AutoCloseable {

	/** 服务器侧本地模型部署通道（WebSocket 端点）。 */
	public static final String DEPLOY_PATH = "/aichat/kh$localModelDeploy";
	/** 指定服务器地址的系统属性：{@code host:port} 或带协议的完整地址；缺省时按站点配置。 */
	public static final String SERVER_PROPERTY = "localModelServer";
	/** 默认断线重连间隔：1 秒。 */
	public static final long DEFAULT_RECONNECT_INTERVAL_MS = 1000L;
	/** 单独运行时的默认数据目录，与 {@code NapCatAIConnector} 保持一致。 */
	private static final String DEFAULT_DATA_FOLDER = "save";
	/** 未传入资源锁时自建锁的资源总量，与聊天控制器的默认值保持一致。 */
	private static final int DEFAULT_RESOURCE_AMOUNT = 12;

	/**
	 * 读取“一个任务最多被插队几次”，默认 {@value ResourceLock#DEFAULT_MAX_OVERTAKES}。
	 * <p>
	 * 用 {@code -DlocalModelMaxOvertakes=N} 调整；达到上限的任务会在资源锁里转为优先调度。
	 * </p>
	 *
	 * @return 插队次数上限
	 */
	private static int readMaxOvertakes() {
		String raw = System.getProperty("localModelMaxOvertakes", "").trim();
		if (raw.isEmpty()) {
			return ResourceLock.DEFAULT_MAX_OVERTAKES;
		}
		try {
			int value = Integer.parseInt(raw);
			return value > 0 ? value : ResourceLock.DEFAULT_MAX_OVERTAKES;
		} catch (NumberFormatException ex) {
			return ResourceLock.DEFAULT_MAX_OVERTAKES;
		}
	}

	/** 运行数据目录，用于读取站点配置。 */
	private final File dataFolder;
	/** 与其它本地模型调用共享的资源锁；只有模型服务一侧持有。 */
	private final ResourceLock resourceLock;
	/** 断线后是否自动重连。 */
	private final boolean autoReconnect;
	/** 自动重连间隔（毫秒）。 */
	private final long reconnectIntervalMs;
	/** 已托管的统一连接管理器，持有引用避免长连接被回收。 */
	private final List<LocalModelClient> clients = new ArrayList<>();
	/** 是否已经建过连接。 */
	private boolean started;

	/**
	 * 使用默认参数：自动重连、间隔 {@value #DEFAULT_RECONNECT_INTERVAL_MS} 毫秒。
	 *
	 * @param dataFolder   运行数据目录（save/）
	 * @param resourceLock 与其它本地模型调用共享的资源锁，为 null 时自建一个
	 */
	public LocalModelConnectionService(File dataFolder, ResourceLock resourceLock) {
		this(dataFolder, resourceLock, true, DEFAULT_RECONNECT_INTERVAL_MS);
	}

	/**
	 * @param dataFolder          运行数据目录（save/）
	 * @param resourceLock        与其它本地模型调用共享的资源锁，为 null 时自建一个
	 * @param autoReconnect       是否自动重连
	 * @param reconnectIntervalMs 重连间隔（毫秒），非正数时使用默认值
	 */
	public LocalModelConnectionService(File dataFolder, ResourceLock resourceLock, boolean autoReconnect,
			long reconnectIntervalMs) {
		this.dataFolder = dataFolder;
		this.resourceLock = resourceLock == null
				? new ResourceLock(DEFAULT_RESOURCE_AMOUNT, readMaxOvertakes())
				: resourceLock;
		this.autoReconnect = autoReconnect;
		this.reconnectIntervalMs = reconnectIntervalMs > 0 ? reconnectIntervalMs : DEFAULT_RECONNECT_INTERVAL_MS;
	}

	/**
	 * 创建服务并立即接入本地模型部署通道。
	 *
	 * @param dataFolder   运行数据目录（save/）
	 * @param resourceLock 与其它本地模型调用共享的资源锁，为 null 时自建一个
	 * @return 已发起连接的服务实例
	 * @throws InterruptedException 建连过程被中断
	 */
	public static LocalModelConnectionService start(File dataFolder, ResourceLock resourceLock)
			throws InterruptedException {
		LocalModelConnectionService service = new LocalModelConnectionService(dataFolder, resourceLock);
		service.connect();
		return service;
	}

	/**
	 * 要接入的全部本地模型部署通道：配置的远端 + 本机回环网关。
	 * <p>
	 * 远端优先取 {@code -D}{@value #SERVER_PROPERTY}（可写 {@code host:port}、{@code ws://...}、
	 * {@code wss://...}；未写协议时按 {@code ws} 处理）；未配置时按 {@link SiteConfig}：
	 * 默认读 {@code save/site.json}，可用 {@code --site-host} / {@code -Dsite_host} 覆盖。
	 * 远端和本机地址相同（例如显式指向本机网关）时只保留一条。
	 * </p>
	 *
	 * @return 部署通道地址列表，至少包含本机网关
	 */
	public List<URI> deployUris() {
		Set<URI> uris = new LinkedHashSet<>();
		URI remote = configuredRemoteUri();
		if (remote != null) {
			uris.add(remote);
		}
		uris.add(LocalModelGateway.deployUri());
		return new ArrayList<>(uris);
	}

	/**
	 * 配置的远端部署通道地址；没有显式配置站点/服务器时返回 {@code null}。
	 *
	 * @return 远端地址或 {@code null}
	 */
	private URI configuredRemoteUri() {
		String override = System.getProperty(SERVER_PROPERTY, "").trim();
		if (!override.isEmpty()) {
			return URI.create(serverUrl(override));
		}
		SiteConfig site = SiteConfig.resolve(dataFolder);
		if (!site.isConfigured()) {
			return null;
		}
		return URI.create(site.webSocketUrl(SiteConfig.DEFAULT_HOST, DEPLOY_PATH));
	}

	/**
	 * 首选部署通道地址（配置了远端时是远端，否则是本机网关）。
	 *
	 * @return 形如 {@code wss://example.com/aichat/kh$localModelDeploy} 的地址
	 */
	public URI deployUri() {
		return deployUris().get(0);
	}

	/**
	 * 把 {@value #SERVER_PROPERTY} 的值拼成部署通道地址。
	 * <p>
	 * 支持 {@code host:port}、{@code ws://host:port}、{@code wss://host:port}；{@code http://}
	 * 与 {@code https://} 分别按 {@code ws} / {@code wss} 处理。未写协议时用 {@code ws}。
	 * </p>
	 *
	 * @param raw 属性值
	 * @return 部署通道地址
	 */
	private static String serverUrl(String raw) {
		String value = raw.trim();
		String scheme = "ws";
		if (value.regionMatches(true, 0, "wss://", 0, 6)) {
			scheme = "wss";
			value = value.substring(6);
		} else if (value.regionMatches(true, 0, "ws://", 0, 5)) {
			value = value.substring(5);
		} else if (value.regionMatches(true, 0, "https://", 0, 8)) {
			scheme = "wss";
			value = value.substring(8);
		} else if (value.regionMatches(true, 0, "http://", 0, 7)) {
			value = value.substring(7);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return scheme + "://" + value + DEPLOY_PATH;
	}

	/**
	 * 接入全部本地模型部署通道。
	 * <p>
	 * 每条通道只建一个 {@link LocalModelClient}，所有模型处理器注册在它上面；重复调用直接返回，
	 * 不会重复建连。
	 * </p>
	 *
	 * @throws InterruptedException 建连过程被中断
	 */
	public synchronized void connect() throws InterruptedException {
		if (started) {
			return;
		}
		clients.clear();
		// 先置位再建连：即使建连被中断，close() 也能把这些客户端关掉
		started = true;
		Map<ModelType, LocalModelClient.RequestHandler> handlers = createHandlers();
		Map<ModelType, LocalModelClient.HealthCheck> healthChecks = createHealthChecks();
		// 打印实际注册的处理器与健康检查：用于核对服务端看到的 provider 列表为什么是某个集合
		System.out.println("[LocalModelDeploy] 注册处理器：" + names(handlers.keySet()) + "，健康检查："
				+ names(healthChecks.keySet()));
		for (URI uri : deployUris()) {
			LocalModelClient client = new LocalModelClient(uri, headers(), autoReconnect, reconnectIntervalMs);
			for (Map.Entry<ModelType, LocalModelClient.RequestHandler> entry : handlers.entrySet()) {
				client.register(entry.getKey(), entry.getValue(), healthChecks.get(entry.getKey()));
			}
			clients.add(client);
			if (client.connectBlocking()) {
				System.out.println("[LocalModelDeploy] 已接入本地模型服务：" + client.getModelTypeNames()
						+ "（" + uri + "）");
			} else {
				System.err.println("[LocalModelDeploy] 接入本地模型服务失败：" + client.getModelTypeNames()
						+ "（" + uri + "），稍后自动重连");
			}
		}
	}

	/** 模型类型集合转可读名称列表，用于日志。 */
	private static String names(Collection<ModelType> types) {
		if (types == null || types.isEmpty()) {
			return "[]";
		}
		List<String> list = new ArrayList<>(types.size());
		for (ModelType type : types) {
			list.add(type.name().toLowerCase());
		}
		return list.toString();
	}

	/**
	 * 构建模型请求处理器表：这里只关心“怎么调用本机模型”，网络与分发全部交给
	 * {@link LocalModelClient}。
	 *
	 * @return 模型类型 → 处理器
	 */
	private Map<ModelType, LocalModelClient.RequestHandler> createHandlers() {
		Map<ModelType, LocalModelClient.RequestHandler> handlers = new EnumMap<>(ModelType.class);
		handlers.put(ModelType.SDXL, SDXLLocalModelHandler.create(resourceLock));
		handlers.put(ModelType.SVS,
				SvsLocalModelHandler.create(resourceLock, System.getProperty("svsHost", "")));
		handlers.put(ModelType.QWEN, QwenLocalModelHandler.create(resourceLock, ""));
		handlers.put(ModelType.H3, H3LocalModelHandler.create(resourceLock, ""));
		if (AudioLocalModelHandler.isConfigured()) {
			handlers.put(ModelType.AUDIO,
					AudioLocalModelHandler.create(null, null, null));
		}
		return handlers;
	}

	/** 每种模型的本地服务健康检查；没有检查器的类型不放入。 */
	private Map<ModelType, LocalModelClient.HealthCheck> createHealthChecks() {
		Map<ModelType, LocalModelClient.HealthCheck> checks = new EnumMap<>(ModelType.class);
		putIfPresent(checks, ModelType.SDXL, SDXLLocalModelHandler.healthCheck());
		putIfPresent(checks, ModelType.QWEN, QwenLocalModelHandler.healthCheck(""));
		putIfPresent(checks, ModelType.H3, H3LocalModelHandler.healthCheck(""));
		putIfPresent(checks, ModelType.AUDIO, AudioLocalModelHandler.healthCheck());
		return checks;
	}

	private static void putIfPresent(Map<ModelType, LocalModelClient.HealthCheck> checks, ModelType type,
			LocalModelClient.HealthCheck check) {
		if (check != null) {
			checks.put(type, check);
		}
	}

	/** 连接头：本地模型部署通道统一用 Bearer Token 鉴权。 */
	private static Map<String, String> headers() {
		return Map.of("authorization", "Bearer " + System.getProperty("localVoiceToken", ""));
	}

	/**
	 * 运行期改变所有连接的能力集合（发送能力集合变更报文）。
	 * <p>
	 * 只有已经注册了处理器的类型才会保留；传空集合表示暂时不接任何任务（连接保留）。
	 * </p>
	 *
	 * @param types 新的能力集合
	 */
	public synchronized void setModelTypes(Collection<ModelType> types) {
		for (LocalModelClient client : clients) {
			client.setModelTypes(types);
		}
	}

	/**
	 * 是否已经发起过连接（不代表当前一定在线，运行期掉线由管理器重连）。
	 *
	 * @return {@code true} 表示连接已发起
	 */
	public synchronized boolean isStarted() {
		return started;
	}

	/**
	 * 当前托管的连接管理器快照，便于查询连接状态与能力集合。
	 *
	 * @return 只读的客户端列表
	 */
	public synchronized List<LocalModelClient> getClients() {
		return Collections.unmodifiableList(new ArrayList<>(clients));
	}

	/**
	 * 断开与服务器的连接并释放客户端；重复调用无副作用。
	 */
	@Override
	public synchronized void close() {
		for (LocalModelClient client : clients) {
			try {
				client.closeService();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		clients.clear();
		started = false;
	}

	/**
	 * 单独运行本地模型服务。
	 * <p>
	 * 启动参数与 {@code NapCatAIConnector.main} 完全相同：站点域名同样支持
	 * {@code --site-host=example.com} / {@code -Dsite_host=example.com}（缺省读
	 * {@code save/site.json}），本地模型相关配置仍是 {@code -DlocalVoiceToken}、
	 * {@code -DsdwebuiUrl}、{@code -DsvsHost}、{@code -DqwenImageUrl}、{@code -Dh3VideoUrl}。
	 * </p>
	 * <pre>
	 * java -DlocalVoiceToken=TOKEN -Dsite_host=example.com \
	 *      -cp target/AIWuxia-0.0.1-SNAPSHOT.jar:target/aiwx_lib/* \
	 *      com.khjxiaogu.aiwuxia.voice.LocalModelConnectionService
	 * </pre>
	 * <p>
	 * 会同时接入两条通道（远端 + 本机网关），每个通道一条连接，所有模型共用该连接。
	 * 进程常驻：连接断开由管理器自动重连；收到终止信号时关闭连接后退出。
	 * </p>
	 *
	 * @param args 与 {@code NapCatAIConnector.main} 相同的启动参数
	 * @throws Exception 建连或等待过程被打断
	 */
	public static void main(String[] args) throws Exception {
		SiteConfig.applyArgs(args);
		File dataFolder = new File(DEFAULT_DATA_FOLDER);
		// 本地模型服务由本进程独有：部署端点 + 回调端点，聊天入口只启回环网关
		VoiceModelLocalServer.startInBackground(dataFolder);
		LocalModelConnectionService service = LocalModelConnectionService.start(dataFolder,
				new ResourceLock(DEFAULT_RESOURCE_AMOUNT, readMaxOvertakes()));
		Runtime.getRuntime().addShutdownHook(new Thread(service::close, "local-model-deploy-shutdown"));
		System.out.println("[LocalModelDeploy] 已单独启动本地模型服务（未启动聊天适配器）");
		for (URI uri : service.deployUris()) {
			System.out.println("[LocalModelDeploy] 服务器地址：" + uri);
		}
		System.out.println("[LocalModelDeploy] 远端地址可用 -D" + SERVER_PROPERTY
				+ " 覆盖；本机网关端口可用 -D" + LocalModelGateway.PORT_PROPERTY + " 覆盖");
		System.out.println("[LocalModelDeploy] 回调服务：0.0.0.0:" + VoiceModelLocalServer.DEFAULT_PORT
				+ VoiceModelLocalServer.DEPLOY_DATA_PATH + "（localemp 的 TARGET_URL 指到这里）");
		System.out.println("[LocalModelDeploy] 本地模型由 -DlocalVoiceToken / -DsdwebuiUrl / -DsvsHost / "
				+ "-DqwenImageUrl / -Dh3VideoUrl 配置（H3 未单独配置时复用 qwenImageUrl）");
		System.out.println("[LocalModelDeploy] 语音合成（TTS）" + (AudioLocalModelHandler.isConfigured()
				? "已启用：-D" + AudioLocalModelHandler.URL_PROPERTY + "=" + AudioLocalModelHandler.resolveUrl()
				: "未启用：如需由本进程朗读，设置 -D" + AudioLocalModelHandler.URL_PROPERTY
						+ "=http://127.0.0.1:9880"));
		// 逐项打印实际生效的本地服务地址：地址配错时请求会发到错误的地方，日志里能直接看出来
		System.out.println("[LocalModelDeploy] 本地模型地址：sdwebuiUrl=" + property("sdwebuiUrl") + " svsHost="
				+ property("svsHost") + " qwenImageUrl=" + property("qwenImageUrl") + " h3VideoUrl="
				+ property("h3VideoUrl"));
		// 常驻等待：连接状态由管理器自己维护，进程只响应终止信号
		CountDownLatch stop = new CountDownLatch(1);
		stop.await();
	}

	/** 读取系统属性；未配置时返回“(未配置)”，仅用于启动日志。 */
	private static String property(String name) {
		String value = System.getProperty(name, "").trim();
		return value.isEmpty() ? "(未配置)" : value;
	}
}
