package com.khjxiaogu.aiwuxia.mcp.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpJson;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;

/**
 * 基于 stdio 的 MCP 客户端。
 * <p>
 * 用于连接以“子进程 + 行分隔 JSON-RPC”方式实现的 MCP 服务端（如 filesystem、git 等本地工具服务）。
 * 客户端负责启动进程、写入请求、读取响应，并把响应按 id 分发到等待中的调用。
 * </p>
 * <ul>
 *   <li>每条消息一行 JSON，UTF-8 编码，符合 stdio 传输约定；</li>
 *   <li>每个请求在独立 future 上等待，超时抛 {@link McpException}；</li>
 *   <li>服务端进程的标准错误会转发到本地标准错误，便于排查；</li>
 *   <li>进程退出时所有等待中的请求立即失败，不会永久挂起。</li>
 * </ul>
 */
public class McpStdioClient extends McpClientBase {

    /** 启动命令（第一个元素为可执行文件）。 */
    private final List<String> command;
    /** 子进程附加环境变量。 */
    private final Map<String, String> environment;
    /** 工作目录，可为 null。 */
    private final File workingDirectory;
    /** 单次请求超时（毫秒）。 */
    private final int timeoutMs;
    /** 请求响应分发表：请求 id → 等待者。 */
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    /** 写锁，保证一条消息不会被并发写断。 */
    private final Object writeLock = new Object();
    /** 子进程。 */
    private volatile Process process;
    /** 子进程标准输入。 */
    private volatile BufferedWriter writer;
    /** 读线程与错误线程。 */
    private volatile Thread readerThread;
    private volatile Thread errorThread;
    /** 是否已关闭。 */
    private volatile boolean closed;

    /**
     * 创建 stdio 客户端。
     *
     * @param name             客户端名称
     * @param command          启动命令
     * @param environment      附加环境变量，可为 null
     * @param workingDirectory 工作目录，可为 null
     * @param timeoutMs        单次请求超时（毫秒），小于等于 0 按 60 秒处理
     */
    public McpStdioClient(String name, List<String> command, Map<String, String> environment, File workingDirectory,
            int timeoutMs) {
        super(name);
        this.command = command == null ? new ArrayList<String>() : new ArrayList<>(command);
        this.environment = environment == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<>(environment);
        this.workingDirectory = workingDirectory;
        this.timeoutMs = timeoutMs <= 0 ? 60000 : timeoutMs;
    }

    /** 启动命令。 */
    public List<String> getCommand() {
        return new ArrayList<>(command);
    }

    @Override
    protected JsonObject rpc(JsonElement id, String method, JsonObject params) throws IOException, McpException {
        ensureStarted();
        String key = id.getAsString();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(key, future);
        try {
            write(McpJson.request(id, method, params));
            JsonObject response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return unwrap(response);
        } catch (TimeoutException ex) {
            throw new McpException(McpProtocol.ERROR_INTERNAL, "等待 MCP 服务端响应超时（" + timeoutMs + "ms）：" + method);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpException(McpProtocol.ERROR_INTERNAL, "等待 MCP 服务端响应时被中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new McpException(McpProtocol.ERROR_INTERNAL, "MCP 服务端响应失败：" + cause.getMessage(), cause);
        } finally {
            pending.remove(key);
        }
    }

    @Override
    protected void notify(String method, JsonObject params) throws IOException, McpException {
        ensureStarted();
        write(McpJson.notification(method, params));
    }

    @Override
    public void close() throws IOException {
        closed = true;
        failAll(new IOException("MCP stdio 客户端已关闭"));
        Process current = process;
        process = null;
        if (current != null) {
            current.destroy();
            try {
                if (!current.waitFor(3, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        BufferedWriter currentWriter = writer;
        writer = null;
        if (currentWriter != null) {
            currentWriter.close();
        }
    }

    // ==================== 子进程与消息循环 ====================

    /** 启动子进程（幂等）。 */
    private synchronized void ensureStarted() throws IOException {
        if (closed) {
            throw new IOException("MCP stdio 客户端已关闭");
        }
        if (process != null && process.isAlive()) {
            return;
        }
        if (command.isEmpty()) {
            throw new IOException("MCP stdio 客户端 " + getName() + " 未配置启动命令");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }
        if (!environment.isEmpty()) {
            builder.environment().putAll(environment);
        }
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "mcp-stdio-reader-" + getName());
        readerThread.setDaemon(true);
        readerThread.start();
        errorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                errorLoop();
            }
        }, "mcp-stdio-error-" + getName());
        errorThread.setDaemon(true);
        errorThread.start();
    }

    /** 读取子进程标准输出并分发响应。 */
    private void readLoop() {
        Process current = process;
        if (current == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (IOException ex) {
            if (!closed) {
                System.err.println("[MCP] stdio 客户端 " + getName() + " 读取失败：" + ex.getMessage());
            }
        } finally {
            failAll(new IOException("MCP stdio 服务端 " + getName() + " 已关闭"));
        }
    }

    /** 处理一行 JSON-RPC 消息。 */
    private void handleLine(String line) {
        JsonElement element;
        try {
            element = JsonParser.parseString(line);
        } catch (RuntimeException ex) {
            System.err.println("[MCP] stdio 客户端 " + getName() + " 收到非 JSON 输出：" + abbreviate(line));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject message = element.getAsJsonObject();
        JsonElement id = message.get("id");
        boolean hasId = id != null && !id.isJsonNull();
        if (hasId && message.has("method")) {
            // 服务端向客户端发起的请求：本客户端不提供 sampling/roots，统一回复方法不存在
            try {
                write(McpJson.error(id,
                        new McpException(McpProtocol.ERROR_METHOD_NOT_FOUND, "客户端不支持该请求：" + message.get("method"))));
            } catch (IOException ex) {
                System.err.println("[MCP] stdio 客户端 " + getName() + " 回复服务端请求失败：" + ex.getMessage());
            }
            return;
        }
        if (hasId && (message.has("result") || message.has("error"))) {
            String key = id.getAsString();
            CompletableFuture<JsonObject> future = pending.remove(key);
            if (future != null) {
                future.complete(message);
            }
            return;
        }
        // 其余为服务端通知（例如日志），忽略
    }

    /** 转发子进程标准错误。 */
    private void errorLoop() {
        Process current = process;
        if (current == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.err.println("[MCP:" + getName() + "] " + line);
            }
        } catch (IOException ex) {
            // 进程结束时流关闭，忽略
        }
    }

    /** 写一条消息到子进程标准输入。 */
    private void write(JsonObject message) throws IOException {
        BufferedWriter current = writer;
        if (current == null) {
            throw new IOException("MCP stdio 客户端 " + getName() + " 未启动");
        }
        synchronized (writeLock) {
            current.write(message.toString());
            current.write('\n');
            current.flush();
        }
    }

    /** 让所有等待中的请求失败。 */
    private void failAll(IOException error) {
        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pending.entrySet()) {
            CompletableFuture<JsonObject> future = pending.remove(entry.getKey());
            if (future != null) {
                future.completeExceptionally(error);
            }
        }
    }

    /** 从响应消息中取出 result，遇到 error 抛出异常。 */
    private static JsonObject unwrap(JsonObject response) throws McpException {
        if (response == null) {
            throw new McpException(McpProtocol.ERROR_INTERNAL, "MCP 服务端未返回响应");
        }
        if (response.has("error") && response.get("error").isJsonObject()) {
            JsonObject error = response.getAsJsonObject("error");
            int code = error.has("code") && !error.get("code").isJsonNull()
                    ? error.get("code").getAsInt()
                    : McpProtocol.ERROR_INTERNAL;
            String message = error.has("message") && !error.get("message").isJsonNull()
                    ? error.get("message").getAsString()
                    : "MCP 服务端返回错误";
            JsonElement data = error.has("data") && !error.get("data").isJsonNull() ? error.get("data") : null;
            throw new McpException(code, message, data, null);
        }
        if (response.has("result") && response.get("result").isJsonObject()) {
            return response.getAsJsonObject("result");
        }
        if (response.has("result")) {
            return new JsonObject();
        }
        throw new McpException(McpProtocol.ERROR_INTERNAL, "MCP 服务端响应缺少 result");
    }

    /** 截断过长的日志片段。 */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
