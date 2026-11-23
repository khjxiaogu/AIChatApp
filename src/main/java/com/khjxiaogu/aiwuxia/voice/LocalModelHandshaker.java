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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.ToolProgress;
import com.khjxiaogu.aiwuxia.utils.ToolProgressContext;
import com.khjxiaogu.aiwuxia.voice.protocol.FileChunkAssembler;
import com.khjxiaogu.aiwuxia.voice.protocol.LocalModelProtocol;
import com.khjxiaogu.aiwuxia.voice.protocol.LocalModelProtocol.FileChunk;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
/**
 * 本地部署模型服务对象，用于管理与本地模型服务的 WebSocket 连接。
 * 该类实现了 {@link WebsocketEvents} 接口，处理 WebSocket 连接的生命周期事件，
 * 并提供向服务端发送请求并等待响应的能力。支持多种模型类型的调用。
 * 客户端连接时通过 URL 参数 {@code model_type} 声明支持的模型类型（逗号分隔），
 * 调用者指定模型类型后，系统自动选择合适的连接发送请求。
 * 内置类型包括 {@link ModelType#AUDIO}（语音合成）、{@link ModelType#SDXL}（图像生成）、
 * {@link ModelType#SVS}（歌声合成，参数见 {@link SvsOptions}）、{@link ModelType#QWEN}（Qwen 生图）
 * 与 {@link ModelType#H3}（MiniMax H3 视频生成）。
 * 内部维护按模型类型分组的连接池，支持多路复用；每个请求通过唯一的请求 ID 关联，并支持超时等待。
 * <p>
 * 连接还可以通过 URL 参数 {@code uid} 声明归属用户：带 {@code uid} 的连接只服务该用户，
 * 不进入共享连接池；调用时传入用户标识，会优先使用该用户自己的连接，用户没有可用连接时
 * 才回退到共享连接池。这样用户把自己的本地模型接入服务器后，其所有会话都会改用该模型，
 * 连接断开或模型离线时又能自动回退到服务器原有的共享模型。
 * </p>
 */
public class LocalModelHandshaker implements WebsocketEvents {

    /**
     * 通用模型请求的默认等待上限：5 分钟。
     * <p>
     * 图片、语音这类任务足够；视频生成等长任务需要通过带 {@code timeoutMs} 的重载放大上限。
     * </p>
     */
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 5 * 60 * 1000L;

    /** 进度协商：收到一次进度上报后默认把任务超时延长到 3 分钟之后。 */
    public static final long DEFAULT_PROGRESS_TIMEOUT_MS = 3 * 60 * 1000L;

    /** 二进制文件分块默认空闲超时：60 秒没有新分块就判定失败。 */
    public static final long DEFAULT_FILE_TIMEOUT_MS = 60 * 1000L;

    /** 读取进度上报对应的时间延长，可用 {@code -DlocalModelProgressTimeoutMs} 覆盖。 */
    private static long readProgressTimeout() {
        String raw = System.getProperty("localModelProgressTimeoutMs", "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_PROGRESS_TIMEOUT_MS;
        }
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : DEFAULT_PROGRESS_TIMEOUT_MS;
        } catch (NumberFormatException ex) {
            return DEFAULT_PROGRESS_TIMEOUT_MS;
        }
    }

    /** 读取文件分块空闲超时，可用 {@code -DlocalModelFileTimeoutMs} 覆盖。 */
    private static long readFileTimeout() {
        String raw = System.getProperty("localModelFileTimeoutMs", "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_FILE_TIMEOUT_MS;
        }
        try {
            long value = Long.parseLong(raw);
            return value >= 5000L ? value : DEFAULT_FILE_TIMEOUT_MS;
        } catch (NumberFormatException ex) {
            return DEFAULT_FILE_TIMEOUT_MS;
        }
    }

    /**
     * 读取请求分块大小，可用 {@code -DlocalModelRequestChunkSize} 覆盖。
     * <p>
     * 请求超过这个大小、且客户端声明了 {@code flags=chunked} 时按分块下发；否则仍然一个文本帧发完。
     * 取值范围 4 KiB ~ 5 MiB。
     * </p>
     */
    private static int readRequestChunkSize() {
        String raw = System.getProperty("localModelRequestChunkSize", "").trim();
        if (raw.isEmpty()) {
            return LocalModelProtocol.DEFAULT_REQUEST_CHUNK_SIZE;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 4096) {
                return LocalModelProtocol.DEFAULT_REQUEST_CHUNK_SIZE;
            }
            return Math.min(value, LocalModelProtocol.DEFAULT_FILE_CHUNK_SIZE);
        } catch (NumberFormatException ex) {
            return LocalModelProtocol.DEFAULT_REQUEST_CHUNK_SIZE;
        }
    }

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
    /**
     * 请求 ID → 下发该请求的连接。
     * <p>
     * 结果必须回到发来请求的那条连接：同一个连接器可以同时接入多个服务器，服务器也可能有
     * 多条连接；只按 reqid 匹配会让其它连接投来的结果完成本连接的任务。这里记录来源，
     * 收到文本响应或二进制分块时校验，不一致就丢弃并记日志。
     * </p>
     */
    private final Map<String, Channel> requestChannels = new ConcurrentHashMap<>();
    /** 连接 → 客户端标识（{@code client_id}）；重连后仍是同一个值，用于接续断线前的任务。 */
    private final Map<Channel, String> channelClients = new ConcurrentHashMap<>();
    /** 连接声明支持的协议能力标记；{@link LocalModelProtocol#FLAG_CHUNKED} 表示可以收发分块大报文。 */
    private final Map<Channel, Set<String>> channelFlags = new ConcurrentHashMap<>();
    /** 请求 ID → 下发该请求的客户端标识；为重连后补发的结果提供来源校验。 */
    private final Map<String, String> requestClients = new ConcurrentHashMap<>();
    /** 每个进行中请求的可延长截止时间；收到进度上报时由 {@link #handleProgress(JsonObject)} 推后。 */
    private final Map<String, AtomicLong> requestDeadlines = new ConcurrentHashMap<>();
    /** 通过 {@link #registerTask(String)} 注册的外部任务截止时间，同样可被进度上报延长。 */
    private final Map<String, AtomicLong> registrationDeadlines = new ConcurrentHashMap<>();
    /** 最近一次上报的任务状态（queued / running），用于诊断与日志。 */
    private final Map<String, String> taskStatus = new ConcurrentHashMap<>();
    /**
     * 请求 ID → 进度接收方（工具调用状态）。
     * <p>
     * 工具线程调用 {@code LocalModel.require(...)} 时，从 {@link ToolProgressContext} 取到当前
     * 工具调用的 sink 并登记在这里；连接器上报的 queued/running/进度/预计时间会转给它。
     * </p>
     */
    private final Map<String, Consumer<ToolProgress>> progressListeners = new ConcurrentHashMap<>();
    /** 正在接收的二进制文件分块：reqid → 组装器 + 来源连接。 */
    private final Map<String, PendingFile> pendingFiles = new ConcurrentHashMap<>();
    /** 收到一次进度上报后，把任务超时延长到这个时间之后（毫秒）。 */
    private final long progressTimeoutMs = readProgressTimeout();
    /** 文件分块接收的空闲超时：超过这个时间没有新分块就失败并丢弃已收到的块（毫秒）。 */
    private final long fileTimeoutMs = readFileTimeout();
    /** 请求超过该大小且客户端支持二进制时，按分块下发（字节）。 */
    private final int requestChunkSize = readRequestChunkSize();
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

            @Override
            public void onMessage(Channel conn, ByteBuf content) {
                // 结果文件的二进制分块必须继续转发：默认实现是空方法，漏掉会让结果被静默丢弃
                LocalModelHandshaker.this.onMessage(conn, content);
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
        // client_id 是连接器自己生成的稳定标识：重连后仍是同一个值，用于接续断线前的任务
        String clientId = parseClientId(handshake.uri());
        // flags 声明连接支持协议的哪些可选部分（例如 chunked = 支持分块/大报文）：
        // 有该标记时服务器才把过大的请求按分块下发，也才把多媒体文件直接放进报文
        Set<String> flags = parseFlags(handshake.uri());
        // 打印实际接入的 uid 与 client，便于核对接入用户、以及重连后是否认回同一个客户端
        System.err.println("[LocalModel] connected from " + conn.remoteAddress().toString()
                + " uid=" + (rawUid == null ? "(none)" : rawUid)
                + (uid == null ? " (shared)" : " (user-bound)")
                + " client=" + (clientId == null ? "(none)" : clientId)
                + " flags=" + flags
                + " provider: " + Arrays.toString(modelTypes.toArray()));
        channelModelTypes.put(conn, modelTypes);
        if (!flags.isEmpty()) {
            channelFlags.put(conn, flags);
        }
        if (clientId != null) {
            channelClients.put(conn, clientId);
        }
        if (uid != null) {
            channelUsers.put(conn, uid);
        }
        registerTypes(conn, uid, modelTypes);
        totalConnections++;
        // 同一客户端重连：把断线时保留的任务续上，等待连接器补发暂存的结果
        revivePendingTasks(conn, clientId);
    }

    /**
     * 从握手 URL 解析客户端的稳定标识；没有时返回 {@code null}。
     *
     * @param uri 握手地址
     * @return 客户端标识或 null
     */
    private static String parseClientId(String uri) {
        try {
            QueryStringDecoder decoder = new QueryStringDecoder(uri);
            List<String> values = decoder.parameters().get("client_id");
            if (values != null && !values.isEmpty()) {
                String value = values.get(0);
                if (value != null) {
                    value = value.trim();
                    if (!value.isEmpty() && value.length() <= 256) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时视为没有 client_id
        }
        return null;
    }

    /**
     * 解析客户端声明的协议能力标记（握手 URL 的 {@code flags} 参数）。
     * <p>
     * 支持两种写法：一次给多个（{@code flags=chunked,stream}）或重复参数
     * （{@code flags=chunked&flags=stream}）。标记会原样保存（转小写），便于将来扩展新能力；
     * 目前已知的是 {@link LocalModelProtocol#FLAG_CHUNKED}，表示支持分块收发大报文。
     * </p>
     *
     * @param uri 握手地址
     * @return 能力标记集合（不为 null）
     */
    private static Set<String> parseFlags(String uri) {
        Set<String> flags = new LinkedHashSet<>();
        try {
            QueryStringDecoder decoder = new QueryStringDecoder(uri);
            List<String> values = decoder.parameters().get("flags");
            if (values == null) {
                values = decoder.parameters().get("flag");
            }
            if (values != null) {
                for (String value : values) {
                    if (value == null) {
                        continue;
                    }
                    for (String part : value.split(",")) {
                        String flag = part.trim().toLowerCase(java.util.Locale.ROOT);
                        if (!flag.isEmpty() && flag.length() <= 64) {
                            flags.add(flag);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时按“没有声明”处理（退回文本帧，保证兼容）
        }
        return flags;
    }

    /**
     * 同一客户端重连后，为它断线前仍在等待的任务延长超时。
     * <p>
     * 连接器在断线期间把结果放在缓冲区，重连后会立即补发；这里延长截止时间，避免补发前
     * 任务先被判定超时。只有声明了 {@code client_id} 的连接才有这个待遇，浏览器页面等
     * 一次性连接仍然在断开时立即失败。
     * </p>
     *
     * @param conn     新连接
     * @param clientId 客户端标识，可为 null
     */
    private void revivePendingTasks(Channel conn, String clientId) {
        if (clientId == null) {
            return;
        }
        int revived = 0;
        long target = System.currentTimeMillis() + progressTimeoutMs;
        for (Map.Entry<String, String> entry : requestClients.entrySet()) {
            if (clientId.equals(entry.getValue())) {
                extendDeadline(requestDeadlines.get(entry.getKey()), target);
                revived++;
            }
        }
        if (revived > 0) {
            System.out.println("[LocalModelDeploy] 客户端重连，为 " + revived + " 个等待中的任务延长超时：client="
                    + clientId);
        }
    }

    @Override
    public void onClose(Channel conn) {
        String clientId = channelClients.remove(conn);
        ConcurrentLinkedQueue<CompletableFuture<ModelGenerationResult>> futures = conn.attr(RESULTS_KEY).getAndSet(null);
        if (futures != null) {
            if (!futures.isEmpty()) {
                if (clientId != null) {
                    // 连接器声明了 client_id：它会在重连后补发暂存结果，任务先保留，等超时自然失败
                    System.err.println("[LocalModelDeploy] 连接断开：" + conn.remoteAddress() + " client=" + clientId
                            + "，保留 " + futures.size() + " 个任务等待重连补发");
                } else {
                    System.err.println("[LocalModelDeploy] 连接断开：" + conn.remoteAddress() + "，正在等待的 "
                            + futures.size() + " 个任务将失败");
                    for (CompletableFuture<ModelGenerationResult> f : futures) {
                        f.completeExceptionally(new IOException("connection closed"));
                    }
                }
            }
        }
        String uid = channelUsers.remove(conn);
        channelFlags.remove(conn);
        Set<ModelType> types = channelModelTypes.remove(conn);
        unregisterTypes(conn, uid, types);
        // 连接断了：该连接上未收全的文件分块全部丢弃
        pendingFiles.entrySet().removeIf(entry -> entry.getValue().channel == conn);
        totalConnections--;
    }

    @Override
    public void onMessage(Channel conn, String message) {
        JsonObject jo = LocalModelProtocol.parse(message);
        if (jo == null) {
            return;
        }
        if (LocalModelProtocol.isProgress(jo)) {
            // 进度协商报文不结束任务，只延长对应任务的超时
            handleProgress(jo);
            return;
        }
        if (LocalModelProtocol.isCapabilities(jo)) {
            // 运行期能力集合变更报文：更新该连接在各模型类型连接池中的登记
            handleCapabilities(conn, jo);
            return;
        }
        String reqid = LocalModelProtocol.reqid(jo);
        if (reqid.isEmpty()) {
            return;
        }
        CompletableFuture<ModelGenerationResult> future = ars.get(reqid);
        if (future != null && !isExpectedSource(reqid, conn)) {
            // 结果来自另一条连接：丢弃，避免把 A 服务器/连接的结果交给 B 的任务
            System.err.println("[LocalModelDeploy] 忽略来自其它连接的文本响应：reqid=" + reqid + " 来源="
                    + conn.remoteAddress() + " 期望=" + describeChannel(requestChannels.get(reqid)));
            return;
        }
        if (future != null) {
            ars.remove(reqid);
            clearProgressState(reqid);
            if (jo.has(LocalModelProtocol.FIELD_ERROR)) {
                String error = jo.get(LocalModelProtocol.FIELD_ERROR).getAsString();
                System.err.println("[LocalModelDeploy] 收到错误响应：reqid=" + reqid + " 错误=" + error);
                future.completeExceptionally(new IOException(error));
            } else {
                System.out.println("[LocalModelDeploy] 收到文本响应：reqid=" + reqid + " 大小=" + message.length() + "B");
                byte[] data = Base64.getDecoder().decode(jo.get(LocalModelProtocol.FIELD_DATA).getAsString());
                String format = jo.has(LocalModelProtocol.FIELD_FORMAT)
                        ? jo.get(LocalModelProtocol.FIELD_FORMAT).getAsString() : "mp3";
                future.complete(new ModelGenerationResult(data, format));
            }
        } else {
            // 任务已超时/已完成，或响应发到了别的连接上；以前这里完全静默，排查时看不出结果已经回来
            System.err.println("[LocalModelDeploy] 收到文本响应但没有等待中的任务（可能已超时或已完成）：reqid="
                    + reqid + " 大小=" + message.length() + "B");
        }
    }

    /**
     * 响应是否来自下发请求的接收端。
     * <p>
     * 先按连接精确匹配；连接断开重连后 Channel 会变，此时只要新连接声明的 {@code client_id}
     * 与请求下发给的客户端一致，也认为来源正确（这正对应连接器缓冲后补发的结果）。
     * 没有登记来源的任务（HTTP 回调、{@link #registerTask(String)}）只按 reqid 匹配。
     * </p>
     */
    private boolean isExpectedSource(String reqid, Channel conn) {
        Channel expected = requestChannels.get(reqid);
        if (expected == conn) {
            return true;
        }
        String expectedClient = requestClients.get(reqid);
        if (expectedClient != null) {
            return expectedClient.equals(channelClients.get(conn));
        }
        return expected == null;
    }

    /** 日志用的连接描述。 */
    private static String describeChannel(Channel conn) {
        if (conn == null) {
            return "(unknown)";
        }
        try {
            return conn.remoteAddress() == null ? String.valueOf(conn.id()) : conn.remoteAddress().toString();
        } catch (Throwable t) {
            return String.valueOf(conn.id());
        }
    }

    /**
     * 处理二进制报文：只用于结果文件的分块传输。
     * <p>
     * 帧格式见 {@link LocalModelProtocol}（{@code [magic][reqidLen][reqid][formatLen][format]
     * [current][total][content]}）。收齐全部分块后才完成任务；任一块非法/超时则整个任务失败，
     * 已收到的分块被丢弃。
     * </p>
     *
     * @param conn    连接
     * @param content 二进制内容
     */
    @Override
    public void onMessage(Channel conn, ByteBuf content) {
        if (content == null) {
            return;
        }
        byte[] frame = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), frame);
        FileChunk chunk = LocalModelProtocol.parseFileChunk(frame);
        if (chunk == null) {
            System.err.println("[LocalModelDeploy] 丢弃非法二进制报文（" + frame.length + " 字节）");
            return;
        }
        handleFileChunk(conn, chunk);
    }

    /**
     * 接收一个文件分块。
     *
     * @param conn  来源连接
     * @param chunk 分块
     */
    private void handleFileChunk(Channel conn, FileChunk chunk) {
        String reqid = chunk.reqid;
        if (!ars.containsKey(reqid)) {
            // 请求已经完成或超时：迟到的分块直接丢弃
            System.err.println("[LocalModelDeploy] 收到分块但没有等待中的任务（可能已超时或已完成）：reqid="
                    + reqid + " " + chunk.current + "/" + chunk.total + " " + chunk.content.length + "B");
            return;
        }
        if (!isExpectedSource(reqid, conn)) {
            // 分块来自另一条连接：丢弃，避免跨服务器/跨连接投递结果
            System.err.println("[LocalModelDeploy] 忽略来自其它连接的结果分块：reqid=" + reqid + " 来源="
                    + describeChannel(conn) + " 期望=" + describeChannel(requestChannels.get(reqid))
                    + " " + chunk.current + "/" + chunk.total + " " + chunk.content.length + "B");
            return;
        }
        PendingFile pending = pendingFiles.get(reqid);
        if (pending == null) {
            try {
                long deadline = System.currentTimeMillis() + fileTimeoutMs;
                FileChunkAssembler assembler = FileChunkAssembler.start(chunk, deadline);
                pending = new PendingFile(assembler, conn);
                pendingFiles.put(reqid, pending);
                scheduleFileTimeout(reqid, pending);
                System.out.println("[LocalModelDeploy] 开始接收结果：reqid=" + reqid + " format=" + chunk.format
                        + " 分块=" + chunk.total + " 首块=" + chunk.content.length + "B");
            } catch (IllegalArgumentException ex) {
                failFileTransfer(reqid, "文件分块非法：" + ex.getMessage());
                return;
            }
        } else {
            try {
                pending.assembler.accept(chunk);
                pending.assembler.touch(System.currentTimeMillis() + fileTimeoutMs);
            } catch (IllegalArgumentException ex) {
                failFileTransfer(reqid, "文件分块不匹配：" + ex.getMessage());
                return;
            }
        }
        // 收到分块说明任务仍在推进，延长整个请求的截止时间
        extendRequestDeadline(reqid);
        if (pending.assembler.isComplete()) {
            pendingFiles.remove(reqid);
            try {
                byte[] data = pending.assembler.assemble();
                completeFileTransfer(reqid, data, pending.assembler.format());
            } catch (IllegalStateException ex) {
                failFileTransfer(reqid, "文件拼接失败：" + ex.getMessage());
            }
        }
    }

    /** 收齐全部分块后完成任务。 */
    private void completeFileTransfer(String reqid, byte[] data, String format) {
        CompletableFuture<ModelGenerationResult> future = ars.remove(reqid);
        clearProgressState(reqid);
        if (future != null) {
            System.out.println("[LocalModelDeploy] 结果接收完成：reqid=" + reqid + " format=" + format + " 大小="
                    + (data == null ? 0 : data.length) + "B");
            future.complete(new ModelGenerationResult(data, format));
        } else {
            System.err.println("[LocalModelDeploy] 结果接收完成但没有等待中的任务（可能已超时）：reqid=" + reqid
                    + " 大小=" + (data == null ? 0 : data.length) + "B");
        }
    }

    /** 分块接收失败：完成任务异常并丢弃已收到的分块。 */
    private void failFileTransfer(String reqid, String reason) {
        PendingFile pending = pendingFiles.remove(reqid);
        CompletableFuture<ModelGenerationResult> future = ars.remove(reqid);
        clearProgressState(reqid);
        if (pending != null) {
            System.err.println("[LocalModelDeploy] 文件接收失败：" + reason + "（已收到 "
                    + pending.assembler.received() + "/" + pending.assembler.total() + " 块）");
        }
        if (future != null) {
            future.completeExceptionally(new IOException(reason));
        }
    }

    /** 安排一次分块接收超时检查；每收到新分块都会推后截止时间并重新排期。 */
    private void scheduleFileTimeout(String reqid, PendingFile pending) {
        long delay = Math.max(1000L, pending.assembler.deadline() - System.currentTimeMillis());
        TIMER.schedule(() -> {
            if (pendingFiles.get(reqid) != pending) {
                return;
            }
            long remaining = pending.assembler.deadline() - System.currentTimeMillis();
            if (remaining > 0) {
                scheduleFileTimeout(reqid, pending);
                return;
            }
            failFileTransfer(reqid, "文件分块接收超时（已收到 " + pending.assembler.received() + "/"
                    + pending.assembler.total() + " 块），丢弃已接收分块");
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** 收到分块/进度时延长请求截止时间。 */
    private void extendRequestDeadline(String reqid) {
        long target = System.currentTimeMillis() + progressTimeoutMs;
        extendDeadline(requestDeadlines.get(reqid), target);
        extendDeadline(registrationDeadlines.get(reqid), target);
    }

    /** 正在接收的文件分块。 */
    private static final class PendingFile {
        final FileChunkAssembler assembler;
        final Channel channel;

        PendingFile(FileChunkAssembler assembler, Channel channel) {
            this.assembler = assembler;
            this.channel = channel;
        }
    }

    /**
     * 处理能力集合变更报文。
     * <p>
     * 报文形如 {@code {"type":"capabilities","model_types":["qwen","h3"]}}。服务端先把该连接
     * 从旧的类型连接池里摘除（正在执行的任务不受影响），再按新集合登记，最后回一条同样的报文作为确认。
     * </p>
     *
     * @param conn    连接
     * @param message 能力集合报文
     */
    private void handleCapabilities(Channel conn, JsonObject message) {
        Set<ModelType> requested = new LinkedHashSet<>();
        for (String name : LocalModelProtocol.modelTypes(message)) {
            ModelType type = ModelType.fromString(name);
            if (type != null) {
                requested.add(type);
            }
        }
        Set<ModelType> old = channelModelTypes.get(conn);
        if (old == null) {
            // 未知连接（例如握手尚未完成）：直接按新集合登记
            old = Collections.emptySet();
        }
        String uid = channelUsers.get(conn);
        unregisterTypes(conn, uid, old);
        channelModelTypes.put(conn, requested);
        registerTypes(conn, uid, requested);
        // 能力集合报文可以只更新模型类型；带了 flags 才更新协议能力标记
        Set<String> flags;
        if (message.has(LocalModelProtocol.FIELD_FLAGS)) {
            flags = new LinkedHashSet<>();
            for (String flag : LocalModelProtocol.flags(message)) {
                flags.add(flag.toLowerCase(java.util.Locale.ROOT));
            }
            if (flags.isEmpty()) {
                channelFlags.remove(conn);
            } else {
                channelFlags.put(conn, flags);
            }
        } else {
            Set<String> current = channelFlags.get(conn);
            flags = current == null ? Collections.<String>emptySet() : current;
        }
        List<String> names = new ArrayList<>(requested.size());
        for (ModelType type : requested) {
            names.add(type.name().toLowerCase());
        }
        System.out.println("[LocalModelDeploy] 能力集合变更：" + names + " flags=" + flags
                + " uid=" + (uid == null ? "(shared)" : uid));
        conn.writeAndFlush(new TextWebSocketFrame(LocalModelProtocol.capabilities(names, flags).toString()));
    }

    /** 把连接登记到各模型类型的连接池。 */
    private void registerTypes(Channel conn, String uid, Set<ModelType> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        if (uid == null) {
            for (ModelType type : types) {
                typedPools.computeIfAbsent(type, k -> new LinkedBlockingQueue<>()).offer(conn);
                typedCounts.merge(type, 1, Integer::sum);
            }
        } else {
            Map<ModelType, BlockingQueue<Channel>> pools = userPools.computeIfAbsent(uid,
                    k -> new ConcurrentHashMap<>());
            Map<ModelType, Integer> counts = userCounts.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
            for (ModelType type : types) {
                pools.computeIfAbsent(type, k -> new LinkedBlockingQueue<>()).offer(conn);
                counts.merge(type, 1, Integer::sum);
            }
        }
    }

    /** 把连接从各模型类型的连接池里摘除。 */
    private void unregisterTypes(Channel conn, String uid, Set<ModelType> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        if (uid == null) {
            for (ModelType type : types) {
                BlockingQueue<Channel> pool = typedPools.get(type);
                if (pool != null) {
                    pool.remove(conn);
                }
                typedCounts.merge(type, -1, Integer::sum);
            }
        } else {
            Map<ModelType, BlockingQueue<Channel>> pools = userPools.get(uid);
            Map<ModelType, Integer> counts = userCounts.get(uid);
            for (ModelType type : types) {
                if (pools != null) {
                    BlockingQueue<Channel> pool = pools.get(type);
                    if (pool != null) {
                        pool.remove(conn);
                    }
                }
                if (counts != null) {
                    counts.merge(type, -1, Integer::sum);
                }
            }
        }
    }

    /** 是否是进度上报报文（{@code {"type":"progress","reqid":...}}）。 */
    private static boolean isProgress(JsonObject message) {
        if (message == null || !message.has("type") || message.get("type").isJsonNull()) {
            return false;
        }
        return "progress".equalsIgnoreCase(message.get("type").getAsString());
    }

    /**
     * 处理本地模型连接器的进度上报。
     * <p>
     * 报文形如 {@code {"type":"progress","reqid":"...","status":"queued|running",
     * "progress":0.35,"message":"..."}}。服务端只做两件事：
     * </p>
     * <ol>
     *   <li>记录任务状态，便于诊断；</li>
     *   <li>把该任务的超时推后到 {@code now + progressTimeoutMs}（只会延后，不会提前）。</li>
     * </ol>
     *
     * @param message 进度报文
     */
    private void handleProgress(JsonObject message) {
        if (!message.has("reqid") || message.get("reqid").isJsonNull()) {
            return;
        }
        String reqid = message.get("reqid").getAsString();
        if (reqid == null || reqid.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + progressTimeoutMs;
        extendDeadline(requestDeadlines.get(reqid), deadline);
        extendDeadline(registrationDeadlines.get(reqid), deadline);
        String status = message.has("status") && !message.get("status").isJsonNull()
                ? message.get("status").getAsString().trim() : LocalModelProtocol.STATUS_RUNNING;
        if (status.isEmpty()) {
            status = LocalModelProtocol.STATUS_RUNNING;
        }
        String detail = message.has("message") && !message.get("message").isJsonNull()
                ? message.get("message").getAsString().trim() : "";
        Double progress = null;
        if (message.has("progress") && !message.get("progress").isJsonNull()) {
            try {
                progress = Double.valueOf(Math.max(0d, Math.min(1d, message.get("progress").getAsDouble())));
            } catch (RuntimeException ignored) {
                // progress 不是数字时忽略进度，不影响状态与超时
            }
        }
        Long etaMs = null;
        if (message.has("eta") && !message.get("eta").isJsonNull()) {
            try {
                long eta = message.get("eta").getAsLong();
                if (eta > 0) {
                    etaMs = Long.valueOf(eta);
                }
            } catch (RuntimeException ignored) {
                // eta 不是数字时忽略估计时间，不影响状态与超时
            }
        }
        String logDetail = detail;
        if (progress != null) {
            logDetail = (logDetail + " " + Math.round(progress.doubleValue() * 100) + "%").trim();
        }
        if (etaMs != null) {
            logDetail = (logDetail + " 预计 " + Math.round(etaMs.longValue() / 1000d) + "s").trim();
        }
        String previous = taskStatus.put(reqid, status);
        if (!status.equals(previous)) {
            System.out.println("[LocalModelDeploy] 任务进度：" + reqid + " → " + status
                    + (logDetail.isEmpty() ? "" : "（" + logDetail + "）"));
        }
        // 转给正在执行该请求的工具调用；没有绑定（语音合成等非工具路径）时忽略
        Consumer<ToolProgress> listener = progressListeners.get(reqid);
        if (listener != null) {
            try {
                listener.accept(new ToolProgress(status, progress, etaMs, detail));
            } catch (Throwable t) {
                // 展示失败不能影响任务
                t.printStackTrace();
            }
        }
    }

    /** 把截止时间向后推，只增不减；{@code deadline} 为 null 时忽略。 */
    private static void extendDeadline(AtomicLong deadline, long target) {
        if (deadline == null) {
            return;
        }
        deadline.accumulateAndGet(target, Math::max);
    }

    /** 清理任务相关的进度、截止时间与未完成的分块接收状态。 */
    private void clearProgressState(String reqid) {
        requestDeadlines.remove(reqid);
        registrationDeadlines.remove(reqid);
        requestChannels.remove(reqid);
        requestClients.remove(reqid);
        taskStatus.remove(reqid);
        progressListeners.remove(reqid);
        pendingFiles.remove(reqid);
    }

    /**
     * 任务最近一次上报的状态（{@code queued} / {@code running} 等），没有上报时返回 null。
     *
     * @param reqid 请求 ID
     * @return 状态字符串或 null
     */
    public String getTaskStatus(String reqid) {
        return reqid == null ? null : taskStatus.get(reqid);
    }

    /**
     * 任务距离超时还剩多少毫秒；任务不存在时返回 -1。
     *
     * @param reqid 请求 ID
     * @return 剩余毫秒数，或 -1
     */
    public long getTaskRemainingMillis(String reqid) {
        if (reqid == null) {
            return -1;
        }
        AtomicLong deadline = requestDeadlines.get(reqid);
        if (deadline == null) {
            deadline = registrationDeadlines.get(reqid);
        }
        return deadline == null ? -1 : deadline.get() - System.currentTimeMillis();
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
            clearProgressState(reqid);
            future.complete(new ModelGenerationResult(data, format != null ? format : "mp3"));
            return true;
        }
        return false;
    }

    /**
     * 注册一个外部任务，返回 Future 等待后续通过 {@link #onMessage(String, byte[], String)}
     * 或进度上报完成。默认 5 分钟无响应自动超时；本地模型连接器只要持续上报进度，超时就会被延后。
     */
    public CompletableFuture<ModelGenerationResult> registerTask(String reqid) {
        CompletableFuture<ModelGenerationResult> future = new CompletableFuture<>();
        ars.put(reqid, future);
        AtomicLong deadline = new AtomicLong(System.currentTimeMillis() + DEFAULT_REQUEST_TIMEOUT_MS);
        registrationDeadlines.put(reqid, deadline);
        scheduleRegistrationTimeout(reqid, future, deadline);
        return future;
    }

    /**
     * 安排一次外部任务超时检查；截止时间被进度上报推后时自动重新排期。
     *
     * @param reqid    请求 ID
     * @param future   等待中的 Future
     * @param deadline 可延长的截止时间
     */
    private void scheduleRegistrationTimeout(String reqid, CompletableFuture<ModelGenerationResult> future,
            AtomicLong deadline) {
        long delay = Math.max(1000L, deadline.get() - System.currentTimeMillis());
        TIMER.schedule(() -> {
            if (future.isDone()) {
                return;
            }
            long remaining = deadline.get() - System.currentTimeMillis();
            if (remaining > 0) {
                scheduleRegistrationTimeout(reqid, future, deadline);
                return;
            }
            if (future.completeExceptionally(new IOException("Task registration timeout"))) {
                ars.remove(reqid);
                clearProgressState(reqid);
            }
        }, delay, TimeUnit.MILLISECONDS);
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

    /**
     * 判断指定用户接下来会取用的连接器是否声明了某个协议能力标记。
     * <p>
     * 取用顺序与 {@link #require(String, ModelType, String, JsonObject)} 一致：用户专属连接优先，
     * 用户没有该类型的连接时检查共享连接池。标记在握手 URL（{@code flags=...}）或运行期的
     * {@code capabilities} 报文里声明；声明了 {@link LocalModelProtocol#FLAG_CHUNKED} 的连接
     * 可以接收按分块下发的大请求，因此调用方可以直接把图片等多媒体内容放进报文，
     * 而不是只给一个链接。没有声明该标记的连接（例如浏览器接入页）仍然只适合收链接。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（只检查共享连接池）
     * @param modelType 模型类型
     * @param flag      协议能力标记，例如 {@link LocalModelProtocol#FLAG_CHUNKED}
     * @return 取用顺序上的连接中有声明该标记者时返回 true；没有连接时返回 false
     */
    public boolean supportsFlag(String uid, ModelType modelType, String flag) {
        if (flag == null) {
            return false;
        }
        String target = flag.trim().toLowerCase(java.util.Locale.ROOT);
        if (target.isEmpty()) {
            return false;
        }
        String targetUser = uid == null || uid.isEmpty() ? null : uid;
        if (targetUser != null && hasUserService(targetUser, modelType)) {
            return anyFlag(targetUser, modelType, target);
        }
        return anyFlag(null, modelType, target);
    }

    /**
     * 判断指定用户接下来会取用的连接器是否支持分块/大报文
     * （{@link LocalModelProtocol#FLAG_CHUNKED}）。
     *
     * @param uid       用户标识，可为 null 或空（只检查共享连接池）
     * @param modelType 模型类型
     * @return 支持时返回 true
     */
    public boolean supportsChunked(String uid, ModelType modelType) {
        return supportsFlag(uid, modelType, LocalModelProtocol.FLAG_CHUNKED);
    }

    /**
     * 检查指定归属的连接里是否有声明了该标记、且支持该模型类型的连接。
     *
     * @param owner     连接归属用户；null 表示共享连接池
     * @param modelType 模型类型
     * @param flag      协议能力标记
     * @return 存在这样的连接时返回 true
     */
    private boolean anyFlag(String owner, ModelType modelType, String flag) {
        for (Map.Entry<Channel, Set<ModelType>> entry : channelModelTypes.entrySet()) {
            String channelUid = channelUsers.get(entry.getKey());
            if (owner == null ? channelUid != null : !owner.equals(channelUid)) {
                continue;
            }
            if (!entry.getValue().contains(modelType)) {
                continue;
            }
            Set<String> flags = channelFlags.get(entry.getKey());
            if (flags != null && flags.contains(flag)) {
                return true;
            }
        }
        return false;
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
     * 把一条请求下发给连接器。
     * <p>
     * 默认仍然是一个文本帧（兼容所有老客户端）。如果请求超过 {@link #requestChunkSize} 且该连接
     * 声明了 {@link LocalModelProtocol#FLAG_CHUNKED}（支持分块/大报文），则改成一串
     * {@code LMR1} 二进制分块；连接器按 reqid 组装后走与文本请求完全相同的处理流程。
     * 这样过大的请求（例如带参考图的图生图）不会因为单个 WebSocket 帧过大而失败。
     * </p>
     *
     * @param ch      目标连接
     * @param reqid   请求 ID
     * @param payload 已序列化的请求 JSON
     */
    private void sendRequest(Channel ch, String reqid, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        boolean chunked = hasFlag(ch, LocalModelProtocol.FLAG_CHUNKED);
        if (!chunked || data.length <= requestChunkSize) {
            ch.writeAndFlush(new TextWebSocketFrame(payload));
            return;
        }
        int total = LocalModelProtocol.fileChunkCount(data.length, requestChunkSize);
        System.out.println("[LocalModelDeploy] 请求较大，按二进制分块下发：reqid=" + reqid + " 大小=" + data.length
                + "B 分块=" + total + " 每块=" + requestChunkSize + "B");
        for (int index = 1; index <= total; index++) {
            int offset = (index - 1) * requestChunkSize;
            int length = Math.min(requestChunkSize, data.length - offset);
            byte[] frame = LocalModelProtocol.encodeRequestChunk(reqid, "json", data, offset, length, index, total);
            ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame)));
        }
    }

    /** 连接是否声明了该协议能力标记。 */
    private boolean hasFlag(Channel ch, String flag) {
        Set<String> flags = channelFlags.get(ch);
        return flags != null && flags.contains(flag);
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
        return require(null, modelType, reqid, request, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * 通用模型请求方法（不带用户，可指定等待上限）。
     *
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @param timeoutMs 等待本地模型回传的上限（毫秒），非正数时使用默认值
     * @return 包含响应数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> require(ModelType modelType, String reqid, JsonObject request,
            long timeoutMs) {
        return require(null, modelType, reqid, request, timeoutMs);
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
        return require(uid, modelType, reqid, request, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * 通用模型请求方法，支持按用户取用本地模型，并自定义等待上限。
     * <p>
     * 取用顺序与 {@link #require(String, ModelType, String, JsonObject)} 相同。视频生成等长任务
     * 需要显式放大 {@code timeoutMs}，否则会在默认上限到达时把仍在生成的请求判为超时。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（只使用共享连接池）
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @param timeoutMs 等待本地模型回传的上限（毫秒），非正数时使用默认值
     * @return 包含响应数据的 {@link CompletableFuture}
     */
    public CompletableFuture<ModelGenerationResult> require(String uid, ModelType modelType, String reqid,
            JsonObject request, long timeoutMs) {
        final String targetUser = uid == null || uid.isEmpty() ? null : uid;
        final long waitMillis = timeoutMs > 0 ? timeoutMs : DEFAULT_REQUEST_TIMEOUT_MS;
        if (!hasOnlineService(targetUser, modelType)) {
            System.err.println("[LocalModelDeploy] 没有可用的本地模型连接：type=" + modelType + " uid="
                    + (targetUser == null ? "(shared)" : targetUser) + " reqid=" + reqid);
            return CompletableFuture.failedFuture(new IOException("no local model found for type: " + modelType));
        }

        request.addProperty("reqid", reqid);
        if (!request.has("type")) {
            request.addProperty("type", modelType.toString());
        }
        // 工具线程调用 require 时绑定了当前工具调用的进度接收方；每次真正下发请求前登记，
        // 失败重试时由 finally 清掉、下一轮重新登记
        final Consumer<ToolProgress> toolSink = ToolProgressContext.current();
        if (toolSink != null) {
            // 连接池/资源锁的等待发生在服务器和连接器两侧：先亮“排队中”，
            // 连接器随后会用 queued/running 覆盖成更准确的状态
            try {
                toolSink.accept(new ToolProgress(ToolProgress.STATUS_QUEUED, null, null, "等待本地模型连接"));
            } catch (Throwable ignored) {
                // 展示失败不影响请求
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            while (true) {
                Channel ch = null;
                CompletableFuture<ModelGenerationResult> future = null;
                try {
                    if (!hasOnlineService(targetUser, modelType))
                        throw new IOException("no local model found for type: " + modelType);

                    ch = acquireChannel(targetUser, modelType);
                    if (ch == null) {
                        System.err.println("[LocalModelDeploy] 等待可用连接超时，继续重试：reqid=" + reqid + " type="
                                + modelType + " uid=" + (targetUser == null ? "(shared)" : targetUser));
                        continue;
                    }
                    // 取到的连接可能已经关闭而尚未从池中移除，跳过它重新取
                    if (!ch.isOpen() || !ch.isActive()) {
                        System.err.println("[LocalModelDeploy] 取到已关闭的连接，跳过：reqid=" + reqid + " channel="
                                + ch.remoteAddress());
                        continue;
                    }

                    future = new CompletableFuture<>();
                    ars.put(reqid, future);
                    if (toolSink != null) {
                        progressListeners.put(reqid, toolSink);
                    }
                    // 记录请求来源：连接 + 客户端标识。断线重连后客户端标识不变，可用于接续任务
                    requestChannels.put(reqid, ch);
                    String clientId = channelClients.get(ch);
                    if (clientId != null) {
                        requestClients.put(reqid, clientId);
                    }
                    // 截止时间可变：连接器在生成期间持续上报进度，服务端据此延长超时
                    AtomicLong deadline = new AtomicLong(System.currentTimeMillis() + waitMillis);
                    requestDeadlines.put(reqid, deadline);
                    addFuture(ch, future);
                    // 请求体可能很大（例如图生图的参考图），只序列化一次
                    String payload = request.toString();
                    System.out.println("[LocalModelDeploy] 下发请求：reqid=" + reqid + " type=" + modelType + " uid="
                            + (targetUser == null ? "(shared)" : targetUser) + " channel=" + ch.remoteAddress()
                            + " 超时=" + waitMillis + "ms 报文=" + payload.length() + "B");
                    long sentAt = System.currentTimeMillis();
                    sendRequest(ch, reqid, payload);

                    while (true) {
                        long remaining = deadline.get() - System.currentTimeMillis();
                        if (remaining <= 0) {
                            System.err.println("[LocalModelDeploy] 请求超时：reqid=" + reqid + " type=" + modelType
                                    + " 已等待=" + (System.currentTimeMillis() - sentAt) + "ms（进度上报会延长超时）");
                            throw new IOException("Connection timeout");
                        }
                        try {
                            ModelGenerationResult result = future.get(remaining, TimeUnit.MILLISECONDS);
                            System.out.println("[LocalModelDeploy] 任务完成：reqid=" + reqid + " type=" + modelType
                                    + " 大小=" + (result.bodyData == null ? 0 : result.bodyData.length) + "B 用时="
                                    + (System.currentTimeMillis() - sentAt) + "ms");
                            return result;
                        } catch (java.util.concurrent.TimeoutException e) {
                            // 等待期间可能刚收到进度上报，重新按最新截止时间判断
                            if (!ch.isOpen() || !ch.isActive()
                                    || System.currentTimeMillis() >= deadline.get()) {
                                System.err.println("[LocalModelDeploy] 连接已关闭或超时：reqid=" + reqid + " type="
                                        + modelType + " 已等待=" + (System.currentTimeMillis() - sentAt) + "ms");
                                throw new IOException("Connection timeout");
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[LocalModelDeploy] 任务失败：reqid=" + reqid + " type=" + modelType + " 错误="
                            + e);
                    throw new RuntimeException(e);
                } finally {
                    ars.remove(reqid);
                    clearProgressState(reqid);
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
     * <p>
     * 除音源（{@code url}、{@code name}）外，SVS 模型还需要以下参数：
     * </p>
     * <ul>
     *   <li>{@code pitch} - 音高，整数</li>
     *   <li>{@code separate} - 是否分离人声（旧本地模型字段）</li>
     *   <li>{@code do_split} - localemp.py 是否执行伴奏分离与合成，默认 true</li>
     *   <li>{@code timbre} / {@code voice} - 音色标识（localemp.py 使用 {@code voice}）</li>
     *   <li>{@code filter} / {@code filter_radius} - 滤波强度/中值滤波半径</li>
     *   <li>{@code strength} / {@code protect} - 合成强度/防撕裂保护</li>
     * </ul>
     * 通过 {@link #builder()} 构建：
     * <pre>
     * SvsOptions options = SvsOptions.builder()
     *     .pitch(2).separate(true).timbre("chara").filter(3).strength(0.75);
     * // localemp.py：
     * SvsOptions options = SvsOptions.builder()
     *     .voice("my_model").pitch(2).filterRadius(3).protect(0.33).doSplit(false);
     * </pre>
     */
    public static class SvsOptions {
        private int pitch = 0;
        private boolean separate = false;
        /** localemp.py 的伴奏分离与合成开关，默认 true。 */
        private boolean doSplit = true;
        private String timbre;
        private int filter = SVS_MIN_FILTER;
        private double strength = SVS_MAX_STRENGTH;
        /** localemp.py 的模型名（不含 .pth）；与 timbre 二选一。 */
        private String voice;
        /** localemp.py 的中值滤波半径，0~7。 */
        private int filterRadius = 0;
        /** localemp.py 的防撕裂保护，0~1。 */
        private double protect = 0d;

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
         * 设置 localemp.py 是否执行伴奏分离与合成。
         *
         * @param doSplit true 分离人声/伴奏并混回；false 直接转换整段音频，输出不含伴奏
         */
        public SvsOptions doSplit(boolean doSplit) {
            this.doSplit = doSplit;
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
         * 设置 localemp.py 使用的模型名（不带 .pth）。
         *
         * @param voice 模型名
         */
        public SvsOptions voice(String voice) {
            if (voice == null || voice.isEmpty())
                throw new IllegalArgumentException("voice must not be empty");
            this.voice = voice;
            return this;
        }

        /**
         * 设置 localemp.py 的中值滤波半径。
         *
         * @param filterRadius 0~7
         */
        public SvsOptions filterRadius(int filterRadius) {
            if (filterRadius < 0 || filterRadius > 7)
                throw new IllegalArgumentException("filterRadius out of range [0, 7]: " + filterRadius);
            this.filterRadius = filterRadius;
            return this;
        }

        /**
         * 设置 localemp.py 的防撕裂保护。
         *
         * @param protect 0~1
         */
        public SvsOptions protect(double protect) {
            if (protect < 0d || protect > 1d)
                throw new IllegalArgumentException("protect out of range [0, 1]: " + protect);
            this.protect = protect;
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

        /** @return localemp.py 是否执行伴奏分离与合成 */
        public boolean isDoSplit() {
            return doSplit;
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

        /** @return localemp.py 的模型名 */
        public String getVoice() {
            return voice;
        }

        /** @return localemp.py 的中值滤波半径 */
        public int getFilterRadius() {
            return filterRadius;
        }

        /** @return localemp.py 的防撕裂保护 */
        public double getProtect() {
            return protect;
        }

        /**
         * 将五个 SVS 参数写入指定的请求 JSON 对象。
         *
         * @param request 目标请求对象
         * @return 传入的请求对象本身，便于链式调用
         * @throws IllegalArgumentException 未设置音色时抛出
         */
        public JsonObject applyTo(JsonObject request) {
            if ((timbre == null || timbre.isEmpty()) && (voice == null || voice.isEmpty()))
                throw new IllegalArgumentException("timbre or voice must not be empty");
            request.addProperty("pitch", pitch);
            request.addProperty("separate", separate);
            request.addProperty("do_split", doSplit);
            if (timbre != null && !timbre.isEmpty())
                request.addProperty("timbre", timbre);
            if (voice != null && !voice.isEmpty())
                request.addProperty("voice", voice);
            request.addProperty("filter", filter);
            request.addProperty("filter_radius", filterRadius);
            request.addProperty("strength", strength);
            request.addProperty("protect", protect);
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
