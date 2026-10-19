package com.khjxiaogu.aiwuxia.mcp.client;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpJson;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;

/**
 * 基于 Streamable HTTP 的 MCP 客户端。
 * <p>
 * 每个 JSON-RPC 请求都是一次独立的 HTTP POST：
 * </p>
 * <ul>
 *   <li>请求头携带 {@code Content-Type: application/json} 与
 *       {@code Accept: application/json, text/event-stream}；</li>
 *   <li>令牌放在 {@code Authorization: Bearer mcp_xxx}（或配置的附加头）中；</li>
 *   <li>服务端用 {@code application/json} 响应时直接解析 JSON-RPC 响应；</li>
 *   <li>服务端用 {@code text/event-stream} 响应时按 SSE 逐事件读取，
 *       一旦收到与请求 id 匹配的响应立即返回，不会等待长连接结束。</li>
 * </ul>
 * <p>
 * 初始化响应中的 {@code Mcp-Session-Id} 会被保存，并在后续请求中回传。
 * </p>
 */
public class McpHttpClient extends McpClientBase {

    /** 服务端 MCP 端点。 */
    private final String url;
    /** 访问令牌明文，可为 null。 */
    private final String token;
    /** 附加请求头（例如自定义鉴权头）。 */
    private final Map<String, String> headers;
    /** 连接超时（毫秒）。 */
    private final int connectTimeoutMs;
    /** 读取超时（毫秒）。 */
    private final int readTimeoutMs;
    /** 服务端下发的会话标识。 */
    private volatile String sessionId;

    /**
     * 创建 HTTP 客户端。
     *
     * @param name 客户端名称
     * @param url  MCP 端点地址（例如 {@code http://127.0.0.1:8999/mcp}）
     */
    public McpHttpClient(String name, String url) {
        this(name, url, null, null, 10000, 60000);
    }

    /**
     * 创建 HTTP 客户端。
     *
     * @param name             客户端名称
     * @param url              MCP 端点地址
     * @param token            访问令牌（mcp_ 开头），可为 null
     * @param headers          附加请求头，可为 null
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读取超时（毫秒）
     */
    public McpHttpClient(String name, String url, String token, Map<String, String> headers, int connectTimeoutMs,
            int readTimeoutMs) {
        super(name);
        this.url = url;
        this.token = token;
        this.headers = headers == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(headers);
        this.connectTimeoutMs = connectTimeoutMs <= 0 ? 10000 : connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs <= 0 ? 60000 : readTimeoutMs;
    }

    /** 服务端 MCP 端点地址。 */
    public String getUrl() {
        return url;
    }

    /** 当前会话标识，未初始化时为 null。 */
    public String getSessionId() {
        return sessionId;
    }

    @Override
    protected JsonObject rpc(JsonElement id, String method, JsonObject params) throws IOException, McpException {
        JsonObject payload = McpJson.request(id, method, params);
        JsonObject response = post(payload, id);
        if (response == null) {
            throw new McpException(McpProtocol.ERROR_INTERNAL, "服务端未返回响应：" + method);
        }
        return response;
    }

    @Override
    protected void notify(String method, JsonObject params) throws IOException, McpException {
        post(McpJson.notification(method, params), null);
    }

    @Override
    public void close() throws IOException {
        String current = sessionId;
        sessionId = null;
        if (current == null || url == null) {
            return;
        }
        // 尽力通知服务端结束会话；无状态服务端可能直接返回 200，失败不影响关闭
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty(McpProtocol.HEADER_SESSION_ID, current);
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty(McpProtocol.HEADER_AUTHORIZATION, "Bearer " + token);
            }
            connection.getResponseCode();
        } catch (IOException ex) {
            // 关闭阶段忽略
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ==================== HTTP 传输 ====================

    /**
     * 发送一次 HTTP POST 并解析响应。
     *
     * @param payload    请求 JSON
     * @param expectedId 期望匹配的响应 id；null 表示不期望响应（通知）
     * @return 对应请求的 {@code result} 对象；通知返回 null
     */
    private JsonObject post(JsonObject payload, JsonElement expectedId) throws IOException, McpException {
        HttpResponse response = send(payload);
        try {
            if (response.status == 401) {
                throw McpException.unauthorized(extractErrorMessage(response.body, "访问令牌无效或已过期"));
            }
            if (response.status >= 400) {
                throw httpError(response.status, response.body);
            }
            if (expectedId == null) {
                return null;
            }
            if (response.stream != null) {
                return streamSse(response.stream, expectedId);
            }
            if (response.body == null || response.body.trim().isEmpty()) {
                throw new McpException(McpProtocol.ERROR_INTERNAL, "服务端返回空响应");
            }
            return unwrap(McpJson.parse(response.body), expectedId);
        } finally {
            closeQuietly(response.stream);
            if (response.connection != null) {
                response.connection.disconnect();
            }
        }
    }

    /** 一次 HTTP 交换的结果。 */
    private static class HttpResponse {
        /** 连接对象，读完响应后关闭。 */
        HttpURLConnection connection;
        /** HTTP 状态码。 */
        int status;
        /** 响应 Content-Type。 */
        String contentType;
        /** 非 SSE、非错误的完整响应体。 */
        String body;
        /** SSE 流（由调用方读取并关闭）。 */
        InputStream stream;
    }

    /** 执行 HTTP POST 并按响应类型准备读取方式。 */
    private HttpResponse send(JsonObject payload) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty(McpProtocol.HEADER_AUTHORIZATION, "Bearer " + token);
            }
            if (sessionId != null) {
                connection.setRequestProperty(McpProtocol.HEADER_SESSION_ID, sessionId);
            }
            if (protocolVersion != null) {
                connection.setRequestProperty(McpProtocol.HEADER_PROTOCOL_VERSION, protocolVersion);
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
                out.flush();
            }
            HttpResponse response = new HttpResponse();
            response.connection = connection;
            response.status = connection.getResponseCode();
            String newSession = connection.getHeaderField(McpProtocol.HEADER_SESSION_ID);
            if (newSession != null && !newSession.isEmpty()) {
                sessionId = newSession;
            }
            response.contentType = connection.getContentType();
            InputStream stream = response.status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            if (stream == null) {
                response.body = "";
                return response;
            }
            stream = decode(stream, connection.getHeaderField("Content-Encoding"));
            if (response.status < 400 && response.contentType != null
                    && response.contentType.toLowerCase().contains("text/event-stream")) {
                response.stream = stream;
            } else {
                response.body = readAll(stream);
            }
            return response;
        } catch (IOException ex) {
            if (connection != null) {
                connection.disconnect();
            }
            throw new IOException("连接 MCP 服务端失败：" + url + "（" + ex.getMessage() + "）", ex);
        }
    }

    /** 按 Content-Encoding 包装输入流。 */
    private static InputStream decode(InputStream stream, String encoding) throws IOException {
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            return new GZIPInputStream(stream);
        }
        return stream;
    }

    /** 读取输入流全部文本并关闭。 */
    private static String readAll(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 安静关闭可关闭资源。 */
    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ex) {
                // 忽略
            }
        }
    }

    /**
     * 逐事件读取 SSE，返回与请求 id 匹配的响应。
     *
     * @param stream     SSE 输入流（由本方法关闭）
     * @param expectedId 期望的请求 id
     * @return 响应的 result 对象
     */
    private static JsonObject streamSse(InputStream stream, JsonElement expectedId) throws IOException, McpException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        JsonObject matched = matchSseEvent(data.toString(), expectedId);
                        if (matched != null) {
                            return matched;
                        }
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String chunk = line.substring(5);
                    if (chunk.startsWith(" ")) {
                        chunk = chunk.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(chunk);
                }
            }
            if (data.length() > 0) {
                JsonObject matched = matchSseEvent(data.toString(), expectedId);
                if (matched != null) {
                    return matched;
                }
            }
        } finally {
            closeQuietly(reader);
        }
        throw new McpException(McpProtocol.ERROR_INTERNAL, "SSE 响应结束但未收到匹配的 JSON-RPC 响应");
    }

    /** 解析单个 SSE 事件，命中请求 id 时返回 result。 */
    private static JsonObject matchSseEvent(String data, JsonElement expectedId) throws McpException {
        JsonElement element = McpJson.parse(data);
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                JsonObject matched = matchResponse(item, expectedId);
                if (matched != null) {
                    return matched;
                }
            }
            return null;
        }
        return matchResponse(element, expectedId);
    }

    /** 判断一个 JSON-RPC 消息是否为目标响应。 */
    private static JsonObject matchResponse(JsonElement element, JsonElement expectedId) throws McpException {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("id") || object.get("id").isJsonNull()) {
            // 服务端通知（如 notifications/message），忽略
            return null;
        }
        String expected = expectedId == null ? null : expectedId.getAsString();
        String actual = object.get("id").getAsString();
        if (expected != null && !expected.equals(actual)) {
            return null;
        }
        return unwrap(object, expectedId);
    }

    /** 从 JSON-RPC 响应中取 result，遇到 error 抛出异常。 */
    private static JsonObject unwrap(JsonElement element, JsonElement expectedId) throws McpException {
        if (element == null || element.isJsonNull()) {
            throw new McpException(McpProtocol.ERROR_INTERNAL, "服务端返回空消息");
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject object = item.getAsJsonObject();
                if (object.has("id") && !object.get("id").isJsonNull()
                        && (expectedId == null || expectedId.getAsString().equals(object.get("id").getAsString()))) {
                    return unwrap(object, expectedId);
                }
            }
            throw new McpException(McpProtocol.ERROR_INTERNAL, "服务端响应中没有匹配的 JSON-RPC 结果");
        }
        if (!element.isJsonObject()) {
            throw new McpException(McpProtocol.ERROR_INTERNAL, "服务端响应不是 JSON-RPC 对象");
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("error") && object.get("error").isJsonObject()) {
            JsonObject error = object.getAsJsonObject("error");
            int code = error.has("code") && !error.get("code").isJsonNull()
                    ? error.get("code").getAsInt()
                    : McpProtocol.ERROR_INTERNAL;
            String message = error.has("message") && !error.get("message").isJsonNull()
                    ? error.get("message").getAsString()
                    : "MCP 服务端返回错误";
            JsonElement data = error.has("data") && !error.get("data").isJsonNull() ? error.get("data") : null;
            throw new McpException(code, message, data, null);
        }
        if (object.has("result") && object.get("result").isJsonObject()) {
            return object.getAsJsonObject("result");
        }
        if (object.has("result") && object.get("result").isJsonNull()) {
            return new JsonObject();
        }
        if (object.has("result")) {
            JsonObject wrapped = new JsonObject();
            wrapped.add("value", object.get("result"));
            return wrapped;
        }
        throw new McpException(McpProtocol.ERROR_INTERNAL, "服务端响应缺少 result 或 error 字段");
    }

    /** 由 HTTP 错误响应构造异常。 */
    private static McpException httpError(int status, String body) {
        String message = extractErrorMessage(body, "HTTP " + status);
        return new McpException(McpProtocol.ERROR_INTERNAL, "MCP 服务端返回 HTTP " + status + "：" + message);
    }

    /** 从错误响应体中提取可读信息。 */
    private static String extractErrorMessage(String body, String fallback) {
        if (body == null || body.trim().isEmpty()) {
            return fallback;
        }
        try {
            JsonElement element = McpJson.parse(body);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("error") && object.get("error").isJsonObject()) {
                    JsonObject error = object.getAsJsonObject("error");
                    if (error.has("message") && !error.get("message").isJsonNull()) {
                        return error.get("message").getAsString();
                    }
                }
                if (object.has("error") && object.get("error").isJsonPrimitive()) {
                    return object.get("error").getAsString();
                }
            }
        } catch (McpException ex) {
            // 非 JSON，返回原文片段
        }
        String trimmed = body.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
