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
package com.khjxiaogu.aiwuxia.mcp.harness;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * DeepSeek Harness MCP 桥的 Java 端（本机回环 TCP）。
 * <p>
 * DSH 侧插件 {@code harness-mcp-bridge.mjs} 通过本桥提供“工作区 / 聊天 / 任务 /
 * 最新输出 / 提问与审批”能力。与只处理单次审批的 {@link com.khjxiaogu.aiwuxia.DshApprovalBridge}
 * 不同，本类维护一条长连接，并同时支持：
 * </p>
 * <ul>
 *   <li>Java → 插件：带 id 的 JSON-RPC 风格请求，等待对应响应；</li>
 *   <li>插件 → Java：无 id 的通知，例如新的聊天消息、待回答的提问与审批；</li>
 *   <li>连接断开后的自动重连：插件会定时重连，未完成的请求立即失败。</li>
 * </ul>
 * <p>
 * 协议：每行一个 JSON 对象，{@code UTF-8}。
 * </p>
 * <ul>
 *   <li>插件 → Java 首帧：{@code {"type":"hello","token":"..."}}</li>
 *   <li>Java → 插件请求：{@code {"id":1,"method":"chat/list","params":{...}}}</li>
 *   <li>插件 → Java 响应：{@code {"id":1,"result":{...}}} 或 {@code {"id":1,"error":"..."}}</li>
 *   <li>插件 → Java 通知：{@code {"method":"interaction","params":{...}}}</li>
 * </ul>
 * <p>
 * 只监听 {@code 127.0.0.1} 的临时端口，并用一次性 token 校验来源，避免同机其它进程冒充插件。
 * </p>
 */
public class HarnessBridge implements Closeable {

    /**
     * 桥事件处理器。
     * <p>所有回调都在连接读取线程上调用，实现方应避免长时间阻塞。</p>
     */
    public interface Handler {
        /** 插件完成握手。 */
        void onHello(JsonObject info);

        /** 插件上报一个新的待处理交互（提问或审批）。 */
        void onInteraction(JsonObject payload);

        /** 插件通知某个交互已关闭（超时、被 GUI 抢先处理等）。 */
        void onInteractionClosed(JsonObject payload);

        /** 插件上报会话状态变化（可选）。 */
        void onChatEvent(JsonObject payload);

        /** 连接断开。 */
        void onDisconnected();
    }

    /** 单次请求的响应。 */
    private static final class Pending {
        private final CompletableFuture<JsonObject> future = new CompletableFuture<>();
    }

    private final ServerSocket server;
    private final String token;
    private final Handler handler;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile Socket active;
    private volatile Writer writer;
    private volatile boolean connected;
    private volatile boolean closed;
    private Thread acceptor;

    /**
     * 创建桥。
     *
     * @param handler 事件处理器
     * @throws IOException 无法监听时抛出
     */
    public HarnessBridge(Handler handler) throws IOException {
        this.handler = handler;
        this.server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        this.token = newToken();
    }

    private static String newToken() {
        byte[] data = new byte[16];
        new SecureRandom().nextBytes(data);
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 供生成的 patch 文件使用的端口。 */
    public int getPort() {
        return server.getLocalPort();
    }

    /** 供生成的 patch 文件使用的共享 token。 */
    public String getToken() {
        return token;
    }

    /** 插件当前是否已连接。 */
    public boolean isConnected() {
        return connected;
    }

    /** 开始接受插件连接。 */
    public synchronized void start() {
        if (acceptor != null || closed) {
            return;
        }
        acceptor = new Thread(this::acceptLoop, "harness-bridge");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> serve(socket), "harness-bridge-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!closed) {
                    System.out.println("[Harness MCP] 桥接服务接受连接失败：" + e);
                }
                return;
            }
        }
    }

    /** 处理一条插件连接：先校验 hello，再持续读取请求/响应/通知。 */
    private void serve(Socket socket) {
        String line = null;
        try (Socket s = socket) {
            // 握手阶段限时，避免无关连接长期占着线程；握手成功后恢复阻塞读
            s.setSoTimeout(10000);
            s.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            line = reader.readLine();
            if (line == null || line.trim().isEmpty()) {
                return;
            }
            JsonObject hello = parse(line);
            if (hello == null || !"hello".equals(JsonConfig.getString(hello, "type"))
                    || !token.equals(JsonConfig.getString(hello, "token"))) {
                return;
            }
            s.setSoTimeout(0);
            synchronized (this) {
                Socket previous = active;
                active = s;
                writer = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                connected = true;
                if (previous != null && previous != s) {
                    try {
                        previous.close();
                    } catch (IOException ignored) {
                        // 旧连接关闭失败不影响新连接
                    }
                    failAll("DSH 桥接连接已重连");
                }
            }
            JsonObject ack = new JsonObject();
            ack.addProperty("type", "hello");
            ack.addProperty("ok", true);
            writeLine(ack);
            Handler current = handler;
            if (current != null) {
                try {
                    current.onHello(hello);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            while (!closed) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonObject frame = parse(line);
                if (frame == null) {
                    continue;
                }
                dispatch(frame);
            }
        } catch (IOException e) {
            if (!closed && connected) {
                System.out.println("[Harness MCP] 桥接连接中断：" + e.getMessage());
            }
        } finally {
            boolean wasActive = false;
            synchronized (this) {
                if (active == socket) {
                    active = null;
                    writer = null;
                    connected = false;
                    wasActive = true;
                }
            }
            if (wasActive) {
                failAll("DSH 桥接连接已断开");
                Handler current = handler;
                if (current != null) {
                    try {
                        current.onDisconnected();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }
        }
    }

    private void dispatch(JsonObject frame) {
        if (frame.has("id") && !frame.get("id").isJsonNull()) {
            String id = frame.get("id").getAsString();
            Pending item = pending.remove(id);
            if (item == null) {
                return;
            }
            if (frame.has("error")) {
                item.future.completeExceptionally(new IOException(JsonConfig.getString(frame, "error")));
            } else {
                JsonElement result = frame.get("result");
                item.future.complete(result != null && result.isJsonObject() ? result.getAsJsonObject()
                        : new JsonObject());
            }
            return;
        }
        String method = JsonConfig.getString(frame, "method");
        if (method == null || method.isEmpty()) {
            return;
        }
        JsonObject params = frame.has("params") && frame.get("params").isJsonObject()
                ? frame.getAsJsonObject("params") : new JsonObject();
        Handler current = handler;
        if (current == null) {
            return;
        }
        try {
            if ("interaction".equals(method)) {
                current.onInteraction(params);
            } else if ("interaction/closed".equals(method)) {
                current.onInteractionClosed(params);
            } else if ("chat/event".equals(method)) {
                current.onChatEvent(params);
            } else if ("ping".equals(method)) {
                JsonObject pong = new JsonObject();
                pong.addProperty("method", "pong");
                writeLine(pong);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 向插件发一个请求并等待响应。
     *
     * @param method    方法名
     * @param params    参数，可为 null
     * @param timeoutMs 超时毫秒
     * @return 响应对象
     * @throws IOException 未连接、超时或插件返回错误
     */
    public JsonObject request(String method, JsonObject params, long timeoutMs) throws IOException {
        if (!connected || writer == null) {
            throw new IOException("DSH 桥接尚未连接");
        }
        String id = String.valueOf(ids.incrementAndGet());
        Pending item = new Pending();
        pending.put(id, item);
        JsonObject frame = new JsonObject();
        frame.addProperty("id", id);
        frame.addProperty("method", method);
        frame.add("params", params == null ? new JsonObject() : params);
        try {
            writeLine(frame);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return item.future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("DSH 插件响应超时（" + method + "）");
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("等待 DSH 插件响应被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
    }

    /**
     * 向插件发一个通知（不等待响应）。
     *
     * @param method 方法名
     * @param params 参数，可为 null
     * @throws IOException 未连接或写入失败
     */
    public void notify(String method, JsonObject params) throws IOException {
        if (!connected || writer == null) {
            throw new IOException("DSH 桥接尚未连接");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("method", method);
        frame.add("params", params == null ? new JsonObject() : params);
        writeLine(frame);
    }

    /** 写一行 JSON，串行化所有写入。 */
    private synchronized void writeLine(JsonObject frame) throws IOException {
        Writer current = writer;
        if (current == null) {
            throw new IOException("DSH 桥接尚未连接");
        }
        current.write(frame.toString());
        current.write('\n');
        current.flush();
    }

    private void failAll(String reason) {
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending item = pending.remove(entry.getKey());
            if (item != null) {
                item.future.completeExceptionally(new IOException(reason));
            }
        }
    }

    private static JsonObject parse(String line) {
        try {
            JsonElement element = JsonParser.parseString(line);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
        Socket current = active;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        failAll("DSH 桥接已关闭");
    }
}
