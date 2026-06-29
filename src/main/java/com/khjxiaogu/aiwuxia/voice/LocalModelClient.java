package com.khjxiaogu.aiwuxia.voice;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 *     .handler(request -&gt; {
 *         // 处理请求，返回结果数据
 *         byte[] audioData = ...;
 *         return new LocalModelClient.Response(audioData, "mp3");
 *     })
 *     .build();
 * client.connectBlocking();
 * </pre>
 */
public class LocalModelClient extends WebSocketClient {

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

    private volatile boolean closed = false;
    private final ScheduledExecutorService reconnectTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-model-client-reconnect");
        t.setDaemon(true);
        return t;
    });

    private LocalModelClient(URI serverUri, Map<String, String> httpHeaders,
                             RequestHandler handler, Set<ModelType> modelTypes,
                             boolean autoReconnect, long reconnectIntervalMs) {
        super(serverUri, httpHeaders);
        this.handler = handler;
        this.modelTypes = modelTypes;
        this.autoReconnect = autoReconnect;
        this.reconnectIntervalMs = reconnectIntervalMs;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("[LocalModelClient] connected to " + getURI());
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
            String reqid = jo.get("reqid").getAsString();
            System.out.println("[LocalModelClient] received request: " + reqid);

            Response response = handler.handle(jo);
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
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[LocalModelClient] disconnected: " + code + " " + reason);
        if (autoReconnect && !closed) {
            reconnectTimer.schedule(() -> {
                if (!closed) {
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
        System.err.println("[LocalModelClient] error: " + e.getMessage());
    }

    /**
     * 关闭连接并停止自动重连。
     */
    @Override
    public void close() {
        closed = true;
        reconnectTimer.shutdownNow();
        super.close();
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
            return new LocalModelClient(uri, headers, handler, modelTypes, autoReconnect, reconnectIntervalMs);
        }
    }
}
