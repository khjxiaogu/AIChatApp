/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.voice;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
/**
 * 本地部署模型服务对象，用于管理与本地模型服务的 WebSocket 连接。
 * 该类实现了 {@link WebsocketEvents} 接口，处理 WebSocket 连接的生命周期事件，
 * 并提供向服务端发送请求并等待响应的能力。支持多种模型类型的调用。
 * 客户端连接时通过 URL 参数 {@code model_type} 声明支持的模型类型（逗号分隔），
 * 调用者指定模型类型后，系统自动选择合适的连接发送请求。
 * 内置类型包括 {@link ModelType#AUDIO}（语音合成）、{@link ModelType#SDXL}（图像生成）
 * 与 {@link ModelType#SVS}（歌声合成，参数见 {@link SvsOptions}）。
 * 内部维护按模型类型分组的连接池，支持多路复用；每个请求通过唯一的请求 ID 关联，并支持超时等待。
 * <p>
 * 连接还可以通过 URL 参数 {@code uid} 声明归属用户：带 {@code uid} 的连接只服务该用户，
 * 不进入共享连接池；调用时传入用户标识，会优先使用该用户自己的连接，用户没有可用连接时
 * 才回退到共享连接池。这样用户把自己的本地模型接入服务器后，其所有会话都会改用该模型，
 * 连接断开或模型离线时又能自动回退到服务器原有的共享模型。
 * </p>
 */
public class LocalModelHandshaker implements WebsocketEvents {

    /** Channel 属性键，用于在连接上存储当前所有活跃请求的 Future */
    private static final AttributeKey<ConcurrentLinkedQueue<CompletableFuture<ModelGenerationResult>>> RESULTS_KEY = AttributeKey.valueOf("localModelResults");

    /** 请求超时调度线程池 */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-model-handshaker-timer");
        t.setDaemon(true);
        return t;
    });

    /** 按模型类型分组的共享连接池（未声明 {@code uid} 的连接） */
    private final Map<ModelType, BlockingQueue<Channel>> typedPools = new ConcurrentHashMap<>();
    /** 每个模型类型的共享在线连接数（不因连接被占用而减少） */
    private final Map<ModelType, Integer> typedCounts = new ConcurrentHashMap<>();
    /** 按用户分组的连接池：用户标识 → 模型类型 → 连接池 */
    private final Map<String, Map<ModelType, BlockingQueue<Channel>>> userPools = new ConcurrentHashMap<>();
    /** 按用户分组的在线连接数：用户标识 → 模型类型 → 连接数 */
    private final Map<String, Map<ModelType, Integer>> userCounts = new ConcurrentHashMap<>();
    /** 每个连接支持的模型类型集合 */
    private final Map<Channel, Set<ModelType>> channelModelTypes = new ConcurrentHashMap<>();
    /** 每个连接归属的用户，共享连接不在该表中 */
    private final Map<Channel, String> channelUsers = new ConcurrentHashMap<>();
    /** 请求 ID 到 Future 的映射，用于在收到响应时完成 Future */
    private final Map<String, CompletableFuture<ModelGenerationResult>> ars = new ConcurrentHashMap<>();
    /** 全局在线连接计数 */
    private volatile int totalConnections = 0;

    public LocalModelHandshaker() {
        super();
    }

    /**
     * 从 WebSocket URL 中解析客户端支持的模型类型。
     * URL 参数格式: {@code ?model_type=audio,tts} 或 {@code ?model_type=audio}
     * 未指定时默认为 {@link ModelType#AUDIO}。
     */
    private static Set<ModelType> parseModelTypes(String uri) {
        try {
            QueryStringDecoder decoder = new QueryStringDecoder(uri);
            Map<String, List<String>> params = decoder.parameters();
            List<String> modelTypeValues = params.get("model_type");
            if (modelTypeValues != null && !modelTypeValues.isEmpty()) {
                String value = modelTypeValues.get(0);
                Set<ModelType> types = new HashSet<>();
                for (String t : value.split(",")) {
                    ModelType mt = ModelType.fromString(t.trim());
                    if (mt != null) {
                        types.add(mt);
                    }
                }
                if (!types.isEmpty()) {
                    return types;
                }
            }
        } catch (Exception e) {
            // 解析失败时使用默认类型
        }
        return Collections.singleton(ModelType.AUDIO);
    }

    /**
     * 未绑定用户的占位标识。
     * <p>
     * 用户的接入页在服务器注入真实 uid 之前固定发送 {@code uid=undefined}。它不代表任何真实用户，
     * 因此这类连接按共享连接处理：任何会话没有自己的本地模型时都能回退使用它。
     * 服务器注入真实 uid 后，连接才会进入该用户的专属连接池。
     * </p>
     */
    public static final String UNBOUND_UID = "undefined";

    /**
     * 读取 WebSocket URL 中客户端发送的原始 uid 参数（不做过虑，占位符原样返回）。
     * <p>
     * 参数名兼容 {@code uid} 与 {@code userId} 两种写法；未提供时返回 {@code null}。
     * </p>
     */
    private static String parseRawUid(String uri) {
        try {
            QueryStringDecoder decoder = new QueryStringDecoder(uri);
            Map<String, List<String>> params = decoder.parameters();
            List<String> values = params.get("uid");
            if (values == null || values.isEmpty()) {
                values = params.get("userId");
            }
            if (values != null && !values.isEmpty()) {
                String uid = values.get(0);
                if (uid != null) {
                    uid = uid.trim();
                    if (!uid.isEmpty()) {
                        return uid;
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时视为没有提供 uid
        }
        return null;
    }

    /**
     * 把原始 uid 归一化为连接归属用户：占位符 {@link #UNBOUND_UID}、{@code null} 或空值
     * 都视为未绑定用户（共享连接），其它值原样返回。
     */
    private static String normalizeUid(String rawUid) {
        if (rawUid == null || rawUid.isEmpty()) {
            return null;
        }
        if (UNBOUND_UID.equalsIgnoreCase(rawUid) || "null".equalsIgnoreCase(rawUid)) {
            return null;
        }
        return rawUid;
    }

    /**
     * 创建一个把连接绑定到指定 uid 的 WebSocket 事件处理器。
     * <p>
     * 服务端注入的 uid 只存在于 {@code AIChatService} 入口拿到的 {@code Request} 上，
     * WebSocket 握手请求里可能仍是占位值。入口从 {@code Request} 取出 uid 后通过本方法传入，
     * 连接就会按入口的 uid 登记，而不是重新解析握手 URL。
     * </p>
     *
     * @param uid 入口取到的 uid，可为 null 或占位值（仍按共享连接处理）
     * @return 绑定该 uid 的 WebSocket 事件处理器
     */
    public WebsocketEvents boundTo(String uid) {
        final String boundUid = uid;
        return new WebsocketEvents() {
            @Override
            public void onOpen(Channel conn, FullHttpRequest handshake) {
                LocalModelHandshaker.this.onOpen(conn, handshake, boundUid);
            }

            @Override
            public void onClose(Channel conn) {
                LocalModelHandshaker.this.onClose(conn);
            }

            @Override
            public void onMessage(Channel conn, String message) {
                LocalModelHandshaker.this.onMessage(conn, message);
            }
        };
    }

    @Override
    public void onOpen(Channel conn, FullHttpRequest handshake) {
        onOpen(conn, handshake, null);
    }

    /**
     * 建立连接。
     *
     * @param conn        连接
     * @param handshake   握手请求
     * @param injectedUid {@code AIChatService} 入口从 {@code Request} 取到的 uid；非空时优先使用，
     *                    为空时回退到解析握手 URL 里的 uid
     */
    public void onOpen(Channel conn, FullHttpRequest handshake, String injectedUid) {
        Set<ModelType> modelTypes = parseModelTypes(handshake.uri());
        String rawUid = injectedUid == null ? null : injectedUid.trim();
        if (rawUid == null || rawUid.isEmpty()) {
            rawUid = parseRawUid(handshake.uri());
        }
        String uid = normalizeUid(rawUid);
        // 打印实际接入的 uid，便于核对接入用户与 session 用户是否一致
        System.err.println("[LocalModel] connected from " + conn.remoteAddress().toString()
                + " uid=" + (rawUid == null ? "(none)" : rawUid)
                + (uid == null ? " (shared)" : " (user-bound)")
                + " provider: " + Arrays.toString(modelTypes.toArray()));
        channelModelTypes.put(conn, modelTypes);
        if (uid == null) {
            for (ModelType type : modelTypes) {
                typedPools.computeIfAbsent(type, k -> new LinkedBlockingQueue<>()).offer(conn);
                typedCounts.merge(type, 1, Integer::sum);
            }
        } else {
            channelUsers.put(conn, uid);
            Map<ModelType, BlockingQueue<Channel>> pools = userPools.computeIfAbsent(uid,
                    k -> new ConcurrentHashMap<>());
            Map<ModelType, Integer> counts = userCounts.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
            for (ModelType type : modelTypes) {
                pools.computeIfAbsent(type, k -> new LinkedBlockingQueue<>()).offer(conn);
                counts.merge(type, 1, Integer::sum);
            }
        }
        totalConnections++;
    }

    @Override
    public void onClose(Channel conn) {
        ConcurrentLinkedQueue<CompletableFuture<ModelGenerationResult>> futures = conn.attr(RESULTS_KEY).getAndSet(null);
        if (futures != null) {
            for (CompletableFuture<ModelGenerationResult> f : futures) {
                f.completeExceptionally(new IOException("connection closed"));
            }
        }
        String uid = channelUsers.remove(conn);
        Set<ModelType> types = channelModelTypes.remove(conn);
        if (types != null) {
            if (uid == null) {
                for (ModelType type : types) {
                    BlockingQueue<Channel> pool = typedPools.get(type);
                    if (pool != null) pool.remove(conn);
                    typedCounts.merge(type, -1, Integer::sum);
                }
            } else {
                Map<ModelType, BlockingQueue<Channel>> pools = userPools.get(uid);
                Map<ModelType, Integer> counts = userCounts.get(uid);
                for (ModelType type : types) {
                    if (pools != null) {
                        BlockingQueue<Channel> pool = pools.get(type);
                        if (pool != null) pool.remove(conn);
                    }
                    if (counts != null) counts.merge(type, -1, Integer::sum);
                }
            }
        }
        totalConnections--;
    }

    @Override
    public void onMessage(Channel conn, String message) {
        JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
        CompletableFuture<ModelGenerationResult> future = ars.remove(jo.get("reqid").getAsString());
        if (future != null) {
            if (jo.has("error")) {
                future.completeExceptionally(new IOException(jo.get("error").getAsString()));
            } else {
                byte[] data = Base64.getDecoder().decode(jo.get("data").getAsString());
                String format = jo.has("format") ? jo.get("format").getAsString() : "mp3";
                future.complete(new ModelGenerationResult(data, format));
            }
        }
    }

    /**
     * 处理直接传入的字节数据（可能来自本地或其他来源）。
     *
     * @param reqid  请求 ID
     * @param data   响应的字节数据
     * @param format 数据格式（如 "mp3"、"wav" 等）
     */
    public boolean onMessage(String reqid, byte[] data, String format) {
        CompletableFuture<ModelGenerationResult> future = ars.remove(reqid);
        if (future != null) {
            future.complete(new ModelGenerationResult(data, format != null ? format : "mp3"));
            return true;
        }
        return false;
    }

    /**
     * 注册一个外部任务，返回 Future 等待后续通过 {@link #onMessage(String, byte[], String)} 完成。
     * 5 分钟无响应自动超时。
     */
    public CompletableFuture<ModelGenerationResult> registerTask(String reqid) {
        CompletableFuture<ModelGenerationResult> future = new CompletableFuture<>();
        ars.put(reqid, future);
        TIMER.schedule(() -> {
            if (future.completeExceptionally(new IOException("Task registration timeout"))) {
                ars.remove(reqid);
            }
        }, 5, TimeUnit.MINUTES);
        return future;
    }

    /**
     * 判断共享连接池里是否有支持指定模型类型的在线服务。
     * <p>
     * 用户专属连接不计入共享连接池，因此这里只反映“服务器自己提供的共享模型”。
     * </p>
     *
     * @param modelType 模型类型
     * @return 共享连接池中存在该类型的在线连接时返回 true
     */
    public boolean hasOnlineService(ModelType modelType) {
        Integer count = typedCounts.get(modelType);
        return count != null && count > 0;
    }

    /**
     * 判断指定用户是否能用上支持该模型类型的本地服务。
     * <p>
     * 用户自己的连接优先：用户接入了本地模型就用用户的；用户没有可用连接时回退到共享连接池。
     * 这与 {@link #require(String, ModelType, String, JsonObject)} 的取用顺序一致。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（此时只检查共享连接池）
     * @param modelType 模型类型
     * @return 用户专属连接或共享连接任一在线时返回 true
     */
    public boolean hasOnlineService(String uid, ModelType modelType) {
        return hasUserService(uid, modelType) || hasOnlineService(modelType);
    }

    /**
     * 判断指定用户是否有自己接入的、支持指定模型类型的在线连接。
     *
     * @param uid       用户标识
     * @param modelType 模型类型
     * @return 用户专属连接在线时返回 true
     */
    public boolean hasUserService(String uid, ModelType modelType) {
        if (uid == null || uid.isEmpty())
            return false;
        Map<ModelType, Integer> counts = userCounts.get(uid);
        if (counts == null)
            return false;
        Integer count = counts.get(modelType);
        return count != null && count > 0;
    }

    public boolean hasOnlineService() {
        return totalConnections > 0;
    }

    /**
     * 取一条连接：用户专属优先，用户没有可用连接时回退到共享连接池。
     *
     * @param uid       用户标识，可为 null 或空
     * @param modelType 模型类型
     * @return 取到的连接，等待超时或没有可用连接时返回 null
     */
    private Channel acquireChannel(String uid, ModelType modelType) throws InterruptedException {
        BlockingQueue<Channel> pool;
        if (hasUserService(uid, modelType)) {
            Map<ModelType, BlockingQueue<Channel>> pools = userPools.get(uid);
            pool = pools == null ? null : pools.get(modelType);
        } else {
            pool = typedPools.get(modelType);
        }
        if (pool == null) return null;
        return pool.poll(5, TimeUnit.SECONDS);
    }

    /**
     * 归还连接：按连接自身的归属放回对应用户的连接池或共享连接池。
     * <p>
     * 连接在请求期间可能已经关闭：关闭时 {@link #onClose(Channel)} 会把它从池中移除并清空归属，
     * 这里必须先确认连接仍然活跃且仍登记在池中，避免把已关闭的连接塞回池里（尤其是不能把
     * 用户的连接误放回共享池）。
     * </p>
     *
     * @param ch 连接
     */
    private void releaseChannel(Channel ch) {
        if (ch == null || !ch.isOpen() || !ch.isActive())
            return;
        Set<ModelType> types = channelModelTypes.get(ch);
        if (types == null)
            return;
        String uid = channelUsers.get(ch);
        if (uid == null) {
            for (ModelType type : types) {
                BlockingQueue<Channel> pool = typedPools.get(type);
                if (pool != null) {
                    pool.offer(ch);
                }
            }
            return;
        }
        Map<ModelType, BlockingQueue<Channel>> pools = userPools.get(uid);
        if (pools == null) return;
        for (ModelType type : types) {
            BlockingQueue<Channel> pool = pools.get(type);
            if (pool != null) {
                pool.offer(ch);
            }
        }
    }

    private static void addFuture(Channel ch, CompletableFuture<ModelGenerationResult> future) {
        ConcurrentLinkedQueue<CompletableFuture<ModelGenerationResult>> q = ch.attr(RESULTS_KEY).get();
        if (q == null) {
            q = new ConcurrentLinkedQueue<>();
            ch.attr(RESULTS_KEY).set(q);
        }
        q.add(future);
    }

    private static void removeFuture(Channel ch, CompletableFuture<ModelGenerationResult> future) {
        ConcurrentLinkedQueue<CompletableFuture<ModelGenerationResult>> q = ch.attr(RESULTS_KEY).get();
        if (q != null) q.remove(future);
    }

    /**
     * 通用模型请求方法。调用者指定模型类型，由系统选择支持该类型的共享连接发送请求。
     * <p>
     * 这是不带用户的旧入口，只使用共享连接池；需要优先使用某个用户自己的本地模型时，
     * 使用 {@link #require(String, ModelType, String, JsonObject)}。
     * </p>
     *
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @return 包含响应数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> require(ModelType modelType, String reqid, JsonObject request) {
        return require(null, modelType, reqid, request);
    }

    /**
     * 通用模型请求方法，支持按用户取用本地模型。
     * <p>
     * 取用顺序：该用户自己的在线连接优先；用户没有在线连接（例如没接入、已关闭页面或本地服务离线）
     * 时回退到共享连接池。用户在请求过程中接入或断开本地模型时，下一次取用就会按最新状态选择，
     * 因此已打开的会话也会立即改用用户的模型，无需重连。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（只使用共享连接池）
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @return 包含响应数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> require(String uid, ModelType modelType, String reqid,
            JsonObject request) {
        final String targetUser = uid == null || uid.isEmpty() ? null : uid;
        if (!hasOnlineService(targetUser, modelType))
            return CompletableFuture.failedFuture(new IOException("no local model found for type: " + modelType));

        request.addProperty("reqid", reqid);
        if (!request.has("type")) {
            request.addProperty("type", modelType.toString());
        }

        return CompletableFuture.supplyAsync(() -> {
            while (true) {
                Channel ch = null;
                CompletableFuture<ModelGenerationResult> future = null;
                try {
                    if (!hasOnlineService(targetUser, modelType))
                        throw new IOException("no local model found for type: " + modelType);

                    ch = acquireChannel(targetUser, modelType);
                    if (ch == null) continue;
                    // 取到的连接可能已经关闭而尚未从池中移除，跳过它重新取
                    if (!ch.isOpen() || !ch.isActive()) continue;

                    future = new CompletableFuture<>();
                    ars.put(reqid, future);
                    addFuture(ch, future);
                    ch.writeAndFlush(new TextWebSocketFrame(request.toString()));

                    long endTime = System.currentTimeMillis() + 1000 * 60 * 5;
                    while (true) {
                        try {
                            return future.get(endTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException e) {
                            if (!ch.isOpen() || !ch.isActive() || System.currentTimeMillis() >= endTime)
                                throw new IOException("Connection timeout");
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    ars.remove(reqid);
                    if (ch != null) {
                        if (future != null) removeFuture(ch, future);
                        releaseChannel(ch);
                    }
                }
            }
        });
    }

    private static int countTextLength(JsonArray content) {
        int len = 0;
        for (JsonElement je : content) {
            if (je.isJsonObject()) {
                JsonObject jo = je.getAsJsonObject();
                if (jo.has("text"))
                    len += jo.get("text").getAsString().length();
            }
        }
        return len;
    }

    /**
     * 请求音频数据（便捷方法，内部委托给 {@link #require}）。
     */
    public CompletableFuture<ModelGenerationResult> requireAudio(String chara, String reqid, JsonArray content, Consumer<UsageIntf<?>> usageListener) {
        if (content.size() == 0)
            return CompletableFuture.failedFuture(new IOException("Empty input"));
        int len = countTextLength(content);
        JsonObject request = JsonBuilder.object()
                .add("chara", chara)
                .add("content", content)
                .end();
        return require(ModelType.AUDIO, reqid, request).thenApply(result -> {
            usageListener.accept(new LocalVoiceUsage(len));
            return result;
        });
    }

    /** SVS 滤波强度允许的最小值 */
    public static final int SVS_MIN_FILTER = 0;
    /** SVS 滤波强度允许的最大值 */
    public static final int SVS_MAX_FILTER = 7;
    /** SVS 合成强度允许的最小值 */
    public static final double SVS_MIN_STRENGTH = 0.00;
    /** SVS 合成强度允许的最大值 */
    public static final double SVS_MAX_STRENGTH = 1.00;

    /**
     * SVS（歌声合成）请求参数。
     * 除音源（{@code url}、{@code name}）外，SVS 模型还需要以下五个参数：
     * <ul>
     *   <li>{@code pitch} - 音高，整数</li>
     *   <li>{@code separate} - 是否分离人声</li>
     *   <li>{@code timbre} - 音色标识</li>
     *   <li>{@code filter} - 滤波强度，取值 {@value #SVS_MIN_FILTER}-{@value #SVS_MAX_FILTER}</li>
     *   <li>{@code strength} - 合成强度，取值 0.00-1.00</li>
     * </ul>
     * 通过 {@link #builder()} 构建：
     * <pre>
     * SvsOptions options = SvsOptions.builder()
     *     .pitch(2).separate(true).timbre("chara").filter(3).strength(0.75);
     * </pre>
     */
    public static class SvsOptions {
        private int pitch = 0;
        private boolean separate = false;
        private String timbre;
        private int filter = SVS_MIN_FILTER;
        private double strength = SVS_MAX_STRENGTH;

        private SvsOptions() {
        }

        /** @return 一个新的参数构建器 */
        public static SvsOptions builder() {
            return new SvsOptions();
        }

        /**
         * 设置音高。
         *
         * @param pitch 音高，整数
         */
        public SvsOptions pitch(int pitch) {
            this.pitch = pitch;
            return this;
        }

        /**
         * 设置是否分离人声。
         *
         * @param separate true 表示先分离人声后再合成
         */
        public SvsOptions separate(boolean separate) {
            this.separate = separate;
            return this;
        }

        /**
         * 设置音色标识。
         *
         * @param timbre 音色标识，不可为 null 或空字符串
         * @throws IllegalArgumentException 传入 null 或空字符串时抛出
         */
        public SvsOptions timbre(String timbre) {
            if (timbre == null || timbre.isEmpty())
                throw new IllegalArgumentException("timbre must not be empty");
            this.timbre = timbre;
            return this;
        }

        /**
         * 设置滤波强度。
         *
         * @param filter 取值范围 {@value #SVS_MIN_FILTER}-{@value #SVS_MAX_FILTER}
         * @throws IllegalArgumentException 取值超出范围时抛出
         */
        public SvsOptions filter(int filter) {
            if (filter < SVS_MIN_FILTER || filter > SVS_MAX_FILTER)
                throw new IllegalArgumentException(
                        "filter out of range [" + SVS_MIN_FILTER + ", " + SVS_MAX_FILTER + "]: " + filter);
            this.filter = filter;
            return this;
        }

        /**
         * 设置合成强度。
         *
         * @param strength 取值范围 0.00-1.00
         * @throws IllegalArgumentException 取值超出范围时抛出
         */
        public SvsOptions strength(double strength) {
            if (strength < SVS_MIN_STRENGTH || strength > SVS_MAX_STRENGTH)
                throw new IllegalArgumentException(
                        "strength out of range [" + SVS_MIN_STRENGTH + ", " + SVS_MAX_STRENGTH + "]: " + strength);
            this.strength = strength;
            return this;
        }

        /** @return 音高 */
        public int getPitch() {
            return pitch;
        }

        /** @return 是否分离人声 */
        public boolean isSeparate() {
            return separate;
        }

        /** @return 音色标识 */
        public String getTimbre() {
            return timbre;
        }

        /** @return 滤波强度 */
        public int getFilter() {
            return filter;
        }

        /** @return 合成强度 */
        public double getStrength() {
            return strength;
        }

        /**
         * 将五个 SVS 参数写入指定的请求 JSON 对象。
         *
         * @param request 目标请求对象
         * @return 传入的请求对象本身，便于链式调用
         * @throws IllegalArgumentException 未设置音色时抛出
         */
        public JsonObject applyTo(JsonObject request) {
            if (timbre == null || timbre.isEmpty())
                throw new IllegalArgumentException("timbre must not be empty");
            request.addProperty("pitch", pitch);
            request.addProperty("separate", separate);
            request.addProperty("timbre", timbre);
            request.addProperty("filter", filter);
            request.addProperty("strength", strength);
            return request;
        }

        /** @return 仅包含五个 SVS 参数的请求 JSON 对象 */
        public JsonObject toJson() {
            return applyTo(new JsonObject());
        }

        @Override
        public String toString() {
            return toJson().toString();
        }
    }

    /**
     * 请求 SVS（歌声合成）音频数据，不带输出名。
     *
     * @param reqid   请求唯一 ID
     * @param url     音源地址（待转换/演唱的歌曲）
     * @param options SVS 参数，见 {@link SvsOptions}
     * @return 包含合成音频数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> requireSvs(String reqid, String url, SvsOptions options) {
        return requireSvs(reqid, url, null, options);
    }

    /**
     * 请求 SVS（歌声合成）音频数据（便捷方法，内部委托给 {@link #require}）。
     * 请求 JSON 形如：
     * <pre>
     * {
     *   "url": "音源地址",
     *   "name": "音源名称",
     *   "pitch": 0,
     *   "separate": true,
     *   "timbre": "音色",
     *   "filter": 3,
     *   "strength": 0.75,
     *   "reqid": "请求ID",
     *   "type": "svs"
     * }
     * </pre>
     *
     * @param reqid   请求唯一 ID
     * @param url     音源地址（待转换/演唱的歌曲），不可为空
     * @param name    音源名称，可为 null（为空时不写入请求）
     * @param options SVS 参数，见 {@link SvsOptions}
     * @return 包含合成音频数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> requireSvs(String reqid, String url, String name, SvsOptions options) {
        if (url == null || url.isEmpty())
            return CompletableFuture.failedFuture(new IOException("Empty input url"));
        if (options == null)
            return CompletableFuture.failedFuture(new IOException("Empty svs options"));
        JsonObject request = new JsonObject();
        request.addProperty("url", url);
        if (name != null && !name.isEmpty())
            request.addProperty("name", name);
        try {
            options.applyTo(request);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return require(ModelType.SVS, reqid, request);
    }
}
