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
 * 内部维护按模型类型分组的连接池，支持多路复用；每个请求通过唯一的请求 ID 关联，并支持超时等待。
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

    /** 按模型类型分组的连接池 */
    private final Map<ModelType, BlockingQueue<Channel>> typedPools = new ConcurrentHashMap<>();
    /** 每个模型类型的在线连接数（不因连接被占用而减少） */
    private final Map<ModelType, Integer> typedCounts = new ConcurrentHashMap<>();
    /** 每个连接支持的模型类型集合 */
    private final Map<Channel, Set<ModelType>> channelModelTypes = new ConcurrentHashMap<>();
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

    @Override
    public void onOpen(Channel conn, FullHttpRequest handshake) {
        Set<ModelType> modelTypes = parseModelTypes(handshake.uri());
        channelModelTypes.put(conn, modelTypes);
        for (ModelType type : modelTypes) {
            typedPools.computeIfAbsent(type, k -> new LinkedBlockingQueue<>()).offer(conn);
            typedCounts.merge(type, 1, Integer::sum);
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
        Set<ModelType> types = channelModelTypes.remove(conn);
        if (types != null) {
            for (ModelType type : types) {
                BlockingQueue<Channel> pool = typedPools.get(type);
                if (pool != null) pool.remove(conn);
                typedCounts.merge(type, -1, Integer::sum);
            }
        }
        totalConnections--;
    }

    @Override
    public void onMessage(Channel conn, String message) {
        JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
        CompletableFuture<ModelGenerationResult> future = ars.remove(jo.get("reqid").getAsString());
        System.out.println("received " + message + " future=" + future);
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

    public boolean hasOnlineService(ModelType modelType) {
        Integer count = typedCounts.get(modelType);
        return count != null && count > 0;
    }

    public boolean hasOnlineService() {
        return totalConnections > 0;
    }

    private Channel acquireChannel(ModelType modelType) throws InterruptedException {
        BlockingQueue<Channel> pool = typedPools.get(modelType);
        if (pool == null) return null;
        return pool.poll(5, TimeUnit.SECONDS);
    }

    private void releaseChannel(Channel ch) {
        Set<ModelType> types = channelModelTypes.get(ch);
        if (types != null) {
            for (ModelType type : types) {
                BlockingQueue<Channel> pool = typedPools.get(type);
                if (pool != null) {
                    pool.offer(ch);
                }
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
     * 通用模型请求方法。调用者指定模型类型，由系统选择支持该类型的连接发送请求。
     *
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @return 包含响应数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> require(ModelType modelType, String reqid, JsonObject request) {
        if (!hasOnlineService(modelType))
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
                    if (!hasOnlineService(modelType))
                        throw new IOException("no local model found for type: " + modelType);

                    ch = acquireChannel(modelType);
                    if (ch == null) continue;

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
}
