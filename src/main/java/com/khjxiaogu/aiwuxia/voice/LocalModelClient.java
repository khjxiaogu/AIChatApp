package com.khjxiaogu.aiwuxia.voice;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

/**
 * 本地模型服务的 WebSocket 客户端。
 * 连接到 {@link LocalModelHandshaker} 服务端，接收模型请求并返回结果。
 * <p>
 * 使用方式：
 * <pre>
 * LocalModelClient client = new LocalModelClient.Builder()
 *     .baseUrl("localhost:8998")
 *     .token("your-token")
 *     .modelTypes(ModelType.AUDIO)
 *     .healthCheckUrl("http://127.0.0.1:9880/health") // 可选：本地服务健康检查
 *     .handler(request -&gt; {
 *         // 处理请求，返回结果数据
 *         byte[] audioData = ...;
 *         return new LocalModelClient.Response(audioData, "mp3");
 *     })
 *     .build();
 * client.connectBlocking();
 * </pre>
 * <p>
 * 健康检查说明：连接成功建立后，客户端按固定间隔探测本地服务是否仍可用。
 * 连续失败达到阈值即判定本地服务已经掉线，此时调用 {@link #closeService()} 关闭与服务器的连接，
 * 使服务器不再向这个已经无法工作的客户端派发任务（{@code LocalModelHandshaker} 会因此认为该模型离线）。
 * </p>
 */
public class LocalModelClient extends WebSocketClient {

    /** 默认健康检查间隔：30 秒 */
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 30_000L;
    /** 默认单次健康检查超时：5 秒 */
    public static final int DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 5_000;
    /** 默认连续失败阈值：3 次（按默认间隔约 90 秒判定掉线） */
    public static final int DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD = 3;

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
    }

    /**
     * 请求处理结果。
     */
    public static class Response {
        public final byte[] data;
        public final String format;

        public Response(byte[] data, String format) {
            this.data = data;
            this.format = format;
        }
    }

    private final RequestHandler handler;
    private final Set<ModelType> modelTypes;
    private final boolean autoReconnect;
    private final long reconnectIntervalMs;

    /** 本地服务健康检查，为 null 表示不启用 */
    private final HealthCheck healthCheck;
    /** 健康检查间隔（毫秒） */
    private final long healthCheckIntervalMs;
    /** 连续失败多少次判定本地服务已掉线 */
    private final int healthCheckFailureThreshold;
    /** 连续失败计数，检查成功时清零 */
    private final AtomicInteger healthCheckFailures = new AtomicInteger();
    /** 保证健康检查只启动一次（重连后不重复启动） */
    private final AtomicBoolean healthMonitorStarted = new AtomicBoolean(false);
    /** 正在处理中的请求数：本地服务忙时跳过本轮检查，避免长任务被误判成掉线 */
    private final AtomicInteger activeRequests = new AtomicInteger();

    private volatile boolean closed = false;
    private final ScheduledExecutorService reconnectTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-model-client-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService healthCheckTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-model-client-health");
        t.setDaemon(true);
        return t;
    });
    public static URI addModelType(String uri, Set<ModelType> modelTypes) {
        if (uri == null) {
            return null;
        }
        // 对参数值进行 URL 编码，避免特殊字符导致 URI 无效
        String value="";
        for(ModelType mt:modelTypes) {
        	value+=mt.name().toLowerCase()+",";
        }
        String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8);
        String param = "model_type=" + encodedValue;

        // 1. 分离 fragment（# 之后的部分）
        String fragment = "";
        int fragmentIndex = uri.indexOf('#');
        if (fragmentIndex != -1) {
            fragment = uri.substring(fragmentIndex);
            uri = uri.substring(0, fragmentIndex);
        }

        // 2. 判断是否已包含查询字符串（?）
        int queryIndex = uri.indexOf('?');
        if (queryIndex != -1) {
            // 已有查询字符串
            if (queryIndex == uri.length() - 1) {
                // 以 ? 结尾，直接追加参数
                uri += param;
            } else {
                // 已有其他参数，用 & 连接
                uri += "&" + param;
            }
        } else {
            // 没有查询字符串，用 ? 连接
            uri += "?" + param;
        }

        // 3. 重新附加 fragment
        return URI.create(uri + fragment);
    }

    protected LocalModelClient(URI serverUri, Map<String, String> httpHeaders,
                             RequestHandler handler, Set<ModelType> modelTypes,
                             boolean autoReconnect, long reconnectIntervalMs) {
        this(serverUri, httpHeaders, handler, modelTypes, autoReconnect, reconnectIntervalMs, null);
    }

    /**
     * @param healthCheck 本地服务健康检查，为 null 表示不启用
     */
    protected LocalModelClient(URI serverUri, Map<String, String> httpHeaders,
                             RequestHandler handler, Set<ModelType> modelTypes,
                             boolean autoReconnect, long reconnectIntervalMs,
                             HealthCheck healthCheck) {
        this(serverUri, httpHeaders, handler, modelTypes, autoReconnect, reconnectIntervalMs,
                healthCheck, DEFAULT_HEALTH_CHECK_INTERVAL_MS, DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD);
    }

    /**
     * @param healthCheck                 本地服务健康检查，为 null 表示不启用
     * @param healthCheckIntervalMs       健康检查间隔，非正数时使用默认值
     * @param healthCheckFailureThreshold 连续失败阈值，非正数时使用默认值
     */
    protected LocalModelClient(URI serverUri, Map<String, String> httpHeaders,
                             RequestHandler handler, Set<ModelType> modelTypes,
                             boolean autoReconnect, long reconnectIntervalMs,
                             HealthCheck healthCheck, long healthCheckIntervalMs,
                             int healthCheckFailureThreshold) {
        super(addModelType(serverUri.toString(),modelTypes), httpHeaders);
        this.handler = handler;
        this.modelTypes = modelTypes;
        this.autoReconnect = autoReconnect;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.healthCheck = healthCheck;
        this.healthCheckIntervalMs = healthCheckIntervalMs > 0
                ? healthCheckIntervalMs : DEFAULT_HEALTH_CHECK_INTERVAL_MS;
        this.healthCheckFailureThreshold = healthCheckFailureThreshold > 0
                ? healthCheckFailureThreshold : DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD;
    }
    boolean opened;
    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("[LocalModelClient] connected to " + getURI());
        opened=true;
        startHealthMonitor();
    }

    @Override
    public void onMessage(String message) {
    	try {
	    	JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
	        String reqid = jo.get("reqid").getAsString();
	        try {
	            
	            System.out.println("[LocalModelClient] received request: " + reqid);
	
	            Response response;
	            // 本地服务正忙时不计入健康检查，避免长任务被误判成掉线
	            activeRequests.incrementAndGet();
	            try {
	                response = handler.handle(jo);
	            } finally {
	                activeRequests.decrementAndGet();
	            }
	            if (response != null && response.data != null) {
	                JsonObject reply = JsonBuilder.object()
	                        .add("reqid", reqid)
	                        .add("data", Base64.getEncoder().encodeToString(response.data))
	                        .add("format", response.format != null ? response.format : "mp3")
	                        .end();
	                send(reply.toString());
	            } else {
	                JsonObject reply = JsonBuilder.object()
	                        .add("reqid", reqid)
	                        .add("error", "handler returned null")
	                        .end();
	                send(reply.toString());
	            }
	        } catch (Exception e) {
	            System.err.println("[LocalModelClient] error processing message: " + e.getMessage());
	            e.printStackTrace();
	            JsonObject reply = JsonBuilder.object()
	                    .add("reqid", reqid)
	                    .add("error", "handler error")
	                    .end();
	            send(reply.toString());
	        }
    	} catch (Exception e) {
            System.err.println("[LocalModelClient] error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
    	opened=false;
        System.out.println("[LocalModelClient] disconnected: " + code + " " + reason);
        if (autoReconnect && !closed) {
            reconnectTimer.schedule(() -> {
                if (!closed&&!opened) {
                    System.out.println("[LocalModelClient] reconnecting...");
                    try {
                        reconnectBlocking();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, reconnectIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onError(Exception e) {
    	opened=false;
        System.err.println("[LocalModelClient] error: " + e.getMessage());
        if (autoReconnect && !closed) {
            reconnectTimer.schedule(() -> {
                if (!closed&&!opened) {
                    System.out.println("[LocalModelClient] reconnecting...");
                    try {
                        reconnectBlocking();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, reconnectIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 关闭与服务端的连接，停止自动重连与健康检查。
     * <p>
     * 该方法幂等：重复调用不会重复关闭。本地服务健康检查判定掉线后也会调用它，
     * 连接关闭后服务端会把该客户端从模型池中移除，不再派发任务。
     * </p>
     */
    public void closeService() {
        synchronized (this) {
            if (closed)
                return;
            closed = true;
        }
        System.err.println("[LocalModelClient] closed ");
        reconnectTimer.shutdownNow();
        healthCheckTimer.shutdownNow();
        super.close();
    }

    /**
     * 连接建立后启动本地服务健康检查。重复调用只有第一次生效。
     */
    private void startHealthMonitor() {
        if (healthCheck == null || closed)
            return;
        if (!healthMonitorStarted.compareAndSet(false, true))
            return;
        System.out.println("[LocalModelClient] 本地服务健康检查已启动：每 " + healthCheckIntervalMs
                + "ms 检查一次，连续失败 " + healthCheckFailureThreshold + " 次判定为掉线");
        healthCheckTimer.scheduleWithFixedDelay(this::runHealthCheck, healthCheckIntervalMs,
                healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 执行一次健康检查。连续失败达到阈值时关闭与服务端的连接。
     * <p>
     * 这里吞掉所有异常：调度任务一旦抛出异常就不会再执行，健康检查必须一直存活到连接被关闭。
     * </p>
     */
    private void runHealthCheck() {
        if (closed)
            return;
        // 本地服务正在处理任务（例如正在生成图片/语音），跳过本轮，避免把长任务当成掉线
        if (activeRequests.get() > 0)
            return;
        boolean healthy;
        String failureReason = null;
        try {
            healthy = healthCheck.isHealthy();
        } catch (Throwable t) {
            healthy = false;
            failureReason = t.toString();
        }
        if (healthy) {
            if (healthCheckFailures.getAndSet(0) > 0)
                System.out.println("[LocalModelClient] 本地服务已恢复正常");
            return;
        }
        // 检查过程中连接可能已被手动关闭，此时不再计入失败
        if (closed)
            return;
        int failures = healthCheckFailures.incrementAndGet();
        System.err.println("[LocalModelClient] 本地服务健康检查失败（" + failures + "/"
                + healthCheckFailureThreshold + "）" + (failureReason != null ? "：" + failureReason : ""));
        if (failures >= healthCheckFailureThreshold) {
            System.err.println("[LocalModelClient] 本地服务已掉线，关闭与服务器的连接以注销本地模型服务");
            closeService();
        }
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
        if (url == null || url.trim().isEmpty())
            throw new IllegalArgumentException("健康检查地址不能为空");
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
                if (conn != null)
                    conn.disconnect();
            }
        };
    }

    public Set<ModelType> getModelTypes() {
		return modelTypes;
	}

	/**
     * 构建器，用于配置和创建 {@link LocalModelClient} 实例。
     */
    public static class Builder {
        private String baseUrl = "localhost:8998";
        private String token = "";
        private Set<ModelType> modelTypes = Set.of(ModelType.AUDIO);
        private RequestHandler handler;
        private boolean useSsl = false;
        private boolean autoReconnect = true;
        private long reconnectIntervalMs = 3000;
        private HealthCheck healthCheck;
        private long healthCheckIntervalMs = DEFAULT_HEALTH_CHECK_INTERVAL_MS;
        private int healthCheckFailureThreshold = DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder modelTypes(ModelType... types) {
            this.modelTypes = Set.of(types);
            return this;
        }

        public Builder modelTypes(Set<ModelType> types) {
            this.modelTypes = types;
            return this;
        }

        public Builder handler(RequestHandler handler) {
            this.handler = handler;
            return this;
        }

        public Builder useSsl(boolean useSsl) {
            this.useSsl = useSsl;
            return this;
        }

        public Builder autoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        public Builder reconnectIntervalMs(long ms) {
            this.reconnectIntervalMs = ms;
            return this;
        }

        /**
         * 设置本地服务健康检查（自定义实现）。连接建立后按间隔调用，接连失败达到阈值即关闭连接。
         *
         * @param healthCheck 健康检查，返回 false 表示本地服务不可用；传 null 表示不启用
         */
        public Builder healthCheck(HealthCheck healthCheck) {
            this.healthCheck = healthCheck;
            return this;
        }

        /**
         * 用一条 HTTP GET 地址作为本地服务健康检查（默认 5 秒超时）。
         *
         * @param url 健康检查地址，例如 {@code http://127.0.0.1:7860/internal/ping}
         */
        public Builder healthCheckUrl(String url) {
            return healthCheckUrl(url, DEFAULT_HEALTH_CHECK_TIMEOUT_MS);
        }

        /**
         * 用一条 HTTP GET 地址作为本地服务健康检查。
         *
         * @param url       健康检查地址
         * @param timeoutMs 单次检查的连接与读取超时（毫秒）
         */
        public Builder healthCheckUrl(String url, int timeoutMs) {
            this.healthCheck = httpHealthCheck(url, timeoutMs);
            return this;
        }

        /**
         * 设置健康检查间隔。
         *
         * @param ms 间隔毫秒数，必须大于 0
         */
        public Builder healthCheckIntervalMs(long ms) {
            if (ms <= 0)
                throw new IllegalArgumentException("健康检查间隔必须大于 0");
            this.healthCheckIntervalMs = ms;
            return this;
        }

        /**
         * 设置连续失败多少次后判定本地服务掉线并关闭与服务端的连接。
         *
         * @param threshold 失败次数，必须大于 0
         */
        public Builder healthCheckFailureThreshold(int threshold) {
            if (threshold <= 0)
                throw new IllegalArgumentException("健康检查失败阈值必须大于 0");
            this.healthCheckFailureThreshold = threshold;
            return this;
        }

        public LocalModelClient build() {
            if (handler == null) {
                throw new IllegalStateException("handler is required");
            }
            String scheme = useSsl ? "wss" : "ws";
            String typeParam = modelTypes.stream()
                    .map(ModelType::toString)
                    .collect(Collectors.joining(","));
            URI uri = URI.create(scheme + "://" + baseUrl + "/kh$localModelDeploy?model_type=" + typeParam);
            Map<String, String> headers = Map.of("Authorization", "Bearer " + token);
            return new LocalModelClient(uri, headers, handler, modelTypes, autoReconnect, reconnectIntervalMs,
                    healthCheck, healthCheckIntervalMs, healthCheckFailureThreshold);
        }
    }
}
