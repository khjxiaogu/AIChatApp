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
package com.khjxiaogu.aiwuxia.codex;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 一个 {@code codex app-server} 子进程及其 stdio JSON-RPC 通道。
 * <p>
 * 线上格式是 newline-delimited JSON（每行一条 JSON-RPC 2.0 消息），并且<b>不带</b>
 * {@code "jsonrpc":"2.0"} 头 —— 这点和 {@code DeepseekHarnessConnector} 相反，照抄会踩坑。
 * </p>
 * <p>
 * 握手：先发 {@code initialize}（clientInfo/capabilities），拿到结果后再发
 * {@code initialized} 通知；之后才能调用 {@code thread/*}、{@code turn/*}。
 * </p>
 * <p>
 * 服务端会主动发请求（工具审批、提问），本类把这类请求交给 {@link Listener}，
 * 由上层决定何时以及如何回复（{@link #respond(JsonElement, JsonElement)}）。
 * </p>
 */
public final class CodexProcess {

    /** 收到服务端消息的回调。 */
    public interface Listener {

        /** 收到一条通知（通知没有 id，不需要回复）。 */
        void onNotification(String method, JsonObject params);

        /**
         * 收到一条服务端发起的请求（有 id，必须回复）。
         *
         * @param id     请求 id，原样回传
         * @param method 方法名
         * @param params 参数
         */
        void onServerRequest(JsonElement id, String method, JsonObject params);

        /** 子进程退出或协议流关闭。 */
        void onClosed(String reason);
    }

    /** 客户端标识，会出现在 Codex 的 originator 与日志里。 */
    private static final String CLIENT_NAME = "codex-connector";
    private static final String CLIENT_TITLE = "Codex Connector";
    private static final String CLIENT_VERSION = "0.0.1";

    private final CodexConfig config;
    private final Listener listener;
    private final AtomicInteger requestSeq = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private Process process;
    private Writer stdin;
    private volatile boolean ready;
    private volatile String lastError = "";
    private boolean shutdownHookInstalled;

    public CodexProcess(CodexConfig config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    /** 是否已完成握手且进程存活。 */
    public boolean isReady() {
        Process current = process;
        return ready && current != null && current.isAlive();
    }

    /** 是否存活（不要求握手完成）。 */
    public boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /** 最近一次启动/请求错误。 */
    public String getLastError() {
        return lastError;
    }

    /**
     * 启动子进程并完成握手。
     *
     * @throws IOException 启动或握手失败
     */
    public synchronized void start() throws IOException {
        if (isReady()) {
            return;
        }
        if (isAlive()) {
            close();
        }
        String launcher = config.launcherOrDetected();
        if (launcher == null || launcher.trim().isEmpty()) {
            throw new IOException("未找到 codex 可执行文件，请用 -Dcodex_launcher=... 指定（node_modules 的 codex.cmd 或 codex.exe）");
        }
        File cwd = config.cwd;
        if (!cwd.exists() && !cwd.mkdirs()) {
            throw new IOException("无法创建工作目录：" + cwd.getPath());
        }
        List<String> command = new ArrayList<>();
        if (CodexConfig.isBatch(launcher)) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(launcher);
        command.add("app-server");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd);
        builder.redirectErrorStream(false);
        File home = config.homeFolder();
        if (home != null) {
            if (!home.exists() && !home.mkdirs()) {
                throw new IOException("无法创建 CODEX_HOME：" + home.getPath());
            }
            builder.environment().put("CODEX_HOME", home.getAbsolutePath());
        }
        String effectiveHome = builder.environment().get("CODEX_HOME");
        if (effectiveHome == null || effectiveHome.trim().isEmpty()) {
            effectiveHome = new File(System.getProperty("user.home", ""), ".codex").getAbsolutePath();
        }
        File authFile = new File(effectiveHome, "auth.json");
        File configFile = new File(effectiveHome, "config.toml");
        System.out.println("[Codex] 启动 " + launcher + "，cwd=" + cwd.getPath()
                + "，CODEX_HOME=" + effectiveHome
                + "，auth=" + authFile.isFile() + "，config=" + configFile.isFile()
                + "，user.home=" + System.getProperty("user.home"));
        pending.clear();
        ready = false;
        lastError = "";
        try {
            process = builder.start();
        } catch (IOException ex) {
            lastError = "启动 codex 失败：" + ex.getMessage();
            throw new IOException(lastError, ex);
        }
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        installShutdownHook();
        Thread reader = new Thread(this::readLoop, "codex-stdout");
        reader.setDaemon(true);
        reader.start();
        Thread drain = new Thread(this::drainStderr, "codex-stderr");
        drain.setDaemon(true);
        drain.start();

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", CLIENT_NAME);
        clientInfo.addProperty("title", CLIENT_TITLE);
        clientInfo.addProperty("version", CLIENT_VERSION);
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("experimentalApi", config.experimental);
        JsonObject params = new JsonObject();
        params.add("clientInfo", clientInfo);
        params.add("capabilities", capabilities);
        JsonObject result = request("initialize", params, config.requestTimeoutMs);
        String home2 = JsonConfig.getString(result, "codexHome");
        log("已连接 codex app-server（" + JsonConfig.getString(result, "userAgent") + "）"
                + (home2 == null ? "" : "，CODEX_HOME=" + home2));
        notify("initialized", new JsonObject());
        ready = true;
    }

    /**
     * 发一条请求并等待结果。
     *
     * @param method  方法名
     * @param params  参数，可为 null
     * @param timeout 超时毫秒
     * @return 结果对象（可能是空对象）
     * @throws IOException 写失败、超时或服务端报错
     */
    public JsonObject request(String method, JsonObject params, long timeout) throws IOException {
        Writer writer = stdin;
        if (writer == null || !isAlive()) {
            throw new IOException("codex 未启动");
        }
        int id = requestSeq.getAndIncrement();
        JsonObject frame = new JsonObject();
        frame.addProperty("id", id);
        frame.addProperty("method", method);
        frame.add("params", params == null ? new JsonObject() : params);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(writer, frame.toString());
        } catch (IOException ex) {
            pending.remove(id);
            throw ex;
        }
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.remove(id);
            throw new IOException("请求超时（" + method + "，" + timeout + "ms）");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new IOException("请求被中断：" + method);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw new IOException(cause == null ? ex.toString() : cause.getMessage());
        }
    }

    /**
     * 发一条通知（不需要回复）。
     *
     * @param method 方法名
     * @param params 参数，可为 null
     */
    public void notify(String method, JsonObject params) throws IOException {
        Writer writer = stdin;
        if (writer == null) {
            throw new IOException("codex 未启动");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("method", method);
        frame.add("params", params == null ? new JsonObject() : params);
        writeLine(writer, frame.toString());
    }

    /**
     * 回复一条服务端请求。
     *
     * @param id     请求 id
     * @param result 结果
     */
    public void respond(JsonElement id, JsonElement result) {
        try {
            Writer writer = stdin;
            if (writer == null) {
                return;
            }
            JsonObject frame = new JsonObject();
            frame.add("id", id);
            frame.add("result", result == null ? new JsonObject() : result);
            writeLine(writer, frame.toString());
        } catch (Throwable t) {
            log("回复服务端请求失败：" + t);
        }
    }

    /**
     * 用 JSON-RPC 错误回复服务端请求（用于不支持的方法，避免对方一直等待）。
     *
     * @param id      请求 id
     * @param code    错误码
     * @param message 错误说明
     */
    public void respondError(JsonElement id, int code, String message) {
        try {
            Writer writer = stdin;
            if (writer == null) {
                return;
            }
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            JsonObject frame = new JsonObject();
            frame.add("id", id);
            frame.add("error", error);
            writeLine(writer, frame.toString());
        } catch (Throwable t) {
            log("回复服务端错误失败：" + t);
        }
    }

    /** 关闭子进程：先关 stdin 让 Codex 自己退出，再 destroy，最后强杀。 */
    public synchronized void close() {
        ready = false;
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Throwable ignored) {
            // 进程已经退出
        }
        Process current = process;
        process = null;
        stdin = null;
        if (current == null) {
            return;
        }
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroy();
                if (!current.waitFor(2, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    // ==================== 内部 ====================

    private void writeLine(Writer writer, String line) throws IOException {
        synchronized (writer) {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }
    }

    private void readLoop() {
        Process current = process;
        if (current == null) {
            return;
        }
        String closedReason = "codex 输出流已关闭";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (!line.startsWith("{")) {
                    // stdout 理论上只承载协议；非 JSON 行原样记日志，便于发现版本差异
                    log("[stdout] " + shorten(line, 300));
                    continue;
                }
                try {
                    dispatch(JsonParser.parseString(line).getAsJsonObject());
                } catch (Throwable t) {
                    log("解析帧失败：" + t + " -> " + shorten(line, 200));
                }
            }
        } catch (IOException ex) {
            closedReason = "读取 codex 输出失败：" + ex.getMessage();
        } finally {
            ready = false;
            failPending(closedReason);
            if (listener != null) {
                listener.onClosed(closedReason);
            }
            log(closedReason);
        }
    }

    private void dispatch(JsonObject frame) {
        JsonElement id = frame.get("id");
        String method = JsonConfig.getString(frame, "method");
        JsonObject params = frame.has("params") && frame.get("params").isJsonObject()
                ? frame.getAsJsonObject("params") : new JsonObject();

        // 服务端发起的请求：有 id 也有 method
        if (id != null && method != null) {
            if (listener != null) {
                listener.onServerRequest(id, method, params);
            } else {
                respondError(id, -32601, "no handler");
            }
            return;
        }
        // 我们的请求的响应：有 id，没有 method
        if (id != null) {
            Integer key = JsonConfig.getIntOrNull(id);
            if (key == null) {
                return;
            }
            CompletableFuture<JsonObject> future = pending.remove(key);
            if (future == null) {
                return;
            }
            JsonElement error = frame.get("error");
            if (error != null && !error.isJsonNull()) {
                future.completeExceptionally(new IOException(errorMessage(error)));
                return;
            }
            JsonElement result = frame.get("result");
            future.complete(result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
            return;
        }
        // 通知
        if (method != null && listener != null) {
            listener.onNotification(method, params);
        }
    }

    private void drainStderr() {
        Process current = process;
        if (current == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lastError = line.trim();
                    log("[stderr] " + shorten(line, 500));
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        }
    }

    private void failPending(String reason) {
        for (Integer id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(new IOException(reason));
            }
        }
    }

    private void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "codex-shutdown"));
        } catch (Throwable ignored) {
            // 关停阶段可能已经不允许注册
        }
    }

    private static String errorMessage(JsonElement error) {
        if (error == null || error.isJsonNull()) {
            return "未知错误";
        }
        if (error.isJsonObject()) {
            JsonObject object = error.getAsJsonObject();
            String message = JsonConfig.getString(object, "message");
            String data = object.has("data") && !object.get("data").isJsonNull()
                    ? object.get("data").toString() : null;
            return message == null ? error.toString() : message + (data == null ? "" : "（" + data + "）");
        }
        return error.toString();
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static void log(String message) {
        System.out.println("[Codex] " + message);
    }
}
