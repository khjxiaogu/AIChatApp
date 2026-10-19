package com.khjxiaogu.aiwuxia.mcp.server;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpJson;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;
import com.khjxiaogu.webserver.loging.SimpleLogger;
import com.khjxiaogu.webserver.web.CallBack;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;

import io.netty.buffer.ByteBuf;

/**
 * MCP 服务端的 HTTP 传输层（Streamable HTTP）与令牌管理 API。
 * <p>
 * 挂载在 {@code /aichat/mcp} 路径上（由 {@code AIChatService#mcp} 转发），按剩余路径分派：
 * </p>
 * <ul>
 *   <li>{@code /aichat/mcp}：MCP JSON-RPC 端点。POST 处理请求，GET/DELETE 按协议返回 405/200；</li>
 *   <li>{@code /aichat/mcp/token}、{@code /aichat/mcp/token/revoke}、{@code /aichat/mcp/token/delete}：令牌创建/列表/吊销/删除；</li>
 *   <li>{@code /aichat/mcp/usage}：消费记录查询；</li>
 *   <li>{@code /aichat/mcp/price}：单工具价格查询与设置；</li>
 *   <li>{@code /aichat/mcp/status}、{@code /aichat/mcp/tools}：统计与工具列表；</li>
 *   <li>{@code /aichat/mcp/whoami}：用令牌自检（需要 Authorization）。</li>
 * </ul>
 * <p>
 * 鉴权：MCP 请求必须携带 {@code Authorization: Bearer mcp_xxx}（或 {@code X-MCP-Token}），
 * 令牌由 {@link McpAccessTokenStore} 校验；工具调用会从令牌所属用户的额外配额（补充配额）中扣费。
 * 管理 API 沿用本项目既有的“用户标识即身份”做法，身份只从请求 URL 的查询参数读取（由外部框架注入），
 * 不从请求体读取；不做额外登录校验，部署时请放在反向代理/内网之后，或只监听本机。
 * </p>
 */
public class McpServerEndpoint implements CallBack {

    /** 工具注册表。 */
    private final McpToolRegistry registry;
    /** 令牌与计费存储。 */
    private final McpAccessTokenStore store;
    /** 协议实现。 */
    private final McpServer server;
    /** 默认价格。 */
    private final int defaultPrice;
    /** 服务名称（写入 initialize 响应与状态接口）。 */
    private final String serverName;
    /** 日志记录器。 */
    private final SimpleLogger logger = new SimpleLogger("MCP");

    /**
     * 创建 HTTP 端点。
     *
     * @param registry     工具注册表
     * @param store        令牌与计费存储
     * @param defaultPrice 默认单次调用价格（额外配额单位，小于 0 按 0 处理）
     * @param serverName   服务名称
     * @param serverVersion 服务版本
     */
    public McpServerEndpoint(McpToolRegistry registry, McpAccessTokenStore store, int defaultPrice, String serverName,
            String serverVersion) {
        this.registry = registry;
        this.store = store;
        this.defaultPrice = Math.max(0, defaultPrice);
        this.serverName = serverName == null ? "AIWuxia-MCP" : serverName;
        this.server = new McpServer(registry, store, this.defaultPrice, this.serverName, serverVersion);
    }

    /** 工具注册表（供宿主程序在运行期注册要暴露的工具）。 */
    public McpToolRegistry getRegistry() {
        return registry;
    }

    /** 令牌与计费存储。 */
    public McpAccessTokenStore getStore() {
        return store;
    }

    /** 协议实现（不含传输层，可用于 stdio 等其它接入方式）。 */
    public McpServer getServer() {
        return server;
    }

    /** 默认单次调用价格。 */
    public int getDefaultPrice() {
        return defaultPrice;
    }

    @Override
    public void call(Request request, Response response) {
        try {
            String path = normalizePath(request.getCurrentPath());
            if (isRpcPath(path)) {
                handleRpc(request, response);
            } else {
                handleAdmin(request, response, path);
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (!response.isWritten()) {
                writeJson(response, 500, failure("服务端内部错误"));
            }
        }
    }

    // ==================== MCP 端点 ====================

    /** 处理 {@code /aichat/mcp} 上的请求。 */
    private void handleRpc(Request request, Response response) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            writeCorsPreflight(response);
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            // 本服务不主动推送消息，按 Streamable HTTP 规范允许返回 405
            response.setHeader("Allow", "POST, DELETE");
            writeJson(response, 405, rpcError(McpException.nullId(),
                    new McpException(McpProtocol.ERROR_INVALID_REQUEST, "本服务端不支持 GET 流式通道，请使用 POST")));
            return;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            // 会话是无状态的，删除会话只需成功返回
            response.setHeader("Cache-Control", "no-store");
            response.write(200);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            response.setHeader("Allow", "POST, DELETE");
            writeJson(response, 405, failure("仅支持 POST"));
            return;
        }
        String accept = request.getHeaders().get("Accept");
        if (accept != null && !accept.contains("application/json") && !accept.contains("text/event-stream")
                && !accept.contains("*/*")) {
            writeJson(response, 406, failure("Accept 头必须包含 application/json 或 text/event-stream"));
            return;
        }
        String contentType = request.getHeaders().get("Content-Type");
        if (contentType != null && !contentType.toLowerCase().contains("application/json")) {
            writeJson(response, 415, failure("Content-Type 必须为 application/json"));
            return;
        }
        String clientIp = remoteIp(request);
        McpAccessTokenStore.Token token;
        try {
            token = authenticate(request);
        } catch (McpException ex) {
            writeJson(response, 401, rpcError(McpException.nullId(), ex));
            return;
        }
        String body = readBody(request);
        JsonElement parsed;
        try {
            parsed = McpJson.parse(body);
        } catch (McpException ex) {
            writeJson(response, 400, rpcError(McpException.nullId(), ex));
            return;
        }
        if (parsed.isJsonArray()) {
            handleBatch(parsed.getAsJsonArray(), token, clientIp, request, response);
            return;
        }
        if (!parsed.isJsonObject()) {
            writeJson(response, 400, rpcError(McpException.nullId(),
                    new McpException(McpProtocol.ERROR_INVALID_REQUEST, "请求必须是 JSON 对象或数组")));
            return;
        }
        JsonObject requestObject = parsed.getAsJsonObject();
        JsonObject reply = dispatch(requestObject, token, clientIp);
        if (reply == null) {
            response.setHeader("Cache-Control", "no-store");
            response.write(202);
            return;
        }
        applySessionHeader(request, response,
                McpProtocol.METHOD_INITIALIZE.equals(stringField(requestObject, "method")));
        writeJson(response, 200, reply);
    }

    /** 处理批量请求。 */
    private void handleBatch(JsonArray batch, McpAccessTokenStore.Token token, String clientIp, Request request,
            Response response) {
        JsonArray replies = new JsonArray();
        boolean hasInitialize = false;
        for (JsonElement element : batch) {
            if (!element.isJsonObject()) {
                replies.add(rpcError(McpException.nullId(),
                        new McpException(McpProtocol.ERROR_INVALID_REQUEST, "批量请求中的元素必须是 JSON 对象")));
                continue;
            }
            JsonObject requestObject = element.getAsJsonObject();
            if (McpProtocol.METHOD_INITIALIZE.equals(stringField(requestObject, "method"))) {
                hasInitialize = true;
            }
            JsonObject reply = dispatch(requestObject, token, clientIp);
            if (reply != null) {
                replies.add(reply);
            }
        }
        if (replies.size() == 0) {
            response.setHeader("Cache-Control", "no-store");
            response.write(202);
            return;
        }
        if (hasInitialize) {
            applySessionHeader(request, response, true);
        }
        writeJson(response, 200, replies);
    }

    /**
     * 处理单条 JSON-RPC 消息。
     *
     * @return 响应对象；通知返回 null
     */
    private JsonObject dispatch(JsonObject request, McpAccessTokenStore.Token token, String clientIp) {
        JsonElement id = request.get("id");
        boolean notification = id == null || id.isJsonNull();
        try {
            JsonObject result = server.handle(token, request, clientIp);
            if (notification || result == null) {
                return null;
            }
            return McpJson.result(id, result);
        } catch (McpException ex) {
            if (notification) {
                return null;
            }
            return McpJson.error(id, ex);
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (notification) {
                return null;
            }
            return McpJson.error(id,
                    new McpException(McpProtocol.ERROR_INTERNAL, "服务端内部错误：" + ex.getMessage(), ex));
        }
    }

    // ==================== 管理 API ====================

    /** 处理令牌/用量/价格等管理接口。 */
    private void handleAdmin(Request request, Response response, String path) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            writeCorsPreflight(response);
            return;
        }
        try {
            if ("/token".equals(path)) {
                if ("GET".equalsIgnoreCase(method)) {
                    listTokens(request, response);
                } else if ("POST".equalsIgnoreCase(method)) {
                    createToken(request, response);
                } else {
                    writeJson(response, 405, failure("仅支持 GET/POST"));
                }
                return;
            }
            if ("/token/revoke".equals(path)) {
                updateTokenState(request, response, false);
                return;
            }
            if ("/token/delete".equals(path)) {
                updateTokenState(request, response, true);
                return;
            }
            if ("/usage".equals(path)) {
                usage(request, response);
                return;
            }
            if ("/price".equals(path)) {
                price(request, response, method);
                return;
            }
            if ("/status".equals(path)) {
                status(request, response);
                return;
            }
            if ("/tools".equals(path)) {
                tools(request, response);
                return;
            }
            if ("/whoami".equals(path)) {
                whoami(request, response);
                return;
            }
            writeJson(response, 404, failure("未知接口：" + path));
        } catch (IllegalArgumentException ex) {
            writeJson(response, 400, failure(ex.getMessage()));
        } catch (McpException ex) {
            writeJson(response, ex.getCode() == McpProtocol.ERROR_UNAUTHORIZED ? 401 : 400, failure(ex.getMessage()));
        } catch (Throwable ex) {
            ex.printStackTrace();
            writeJson(response, 500, failure("服务端内部错误"));
        }
    }

    /**
     * 创建令牌。
     * <p>
     * 用户标识只取自 URL 查询参数（由外部框架注入），请求体不再携带；
     * 请求体仅提供备注名、额度上限与有效期。
     * </p>
     */
    private void createToken(Request request, Response response) throws McpException {
        JsonObject body = readJsonObject(request);
        String uid = requireUid(request);
        String name = stringField(body, "name");
        Integer quotaLimit = intField(body, "quotaLimit");
        Long expiresAt = longField(body, "expiresAt");
        Integer expiresInDays = intField(body, "expiresInDays");
        // 0 或负数表示不设置有效期，避免创建出“立即过期”的令牌
        if (expiresAt == null && expiresInDays != null && expiresInDays.intValue() > 0) {
            expiresAt = Long.valueOf(System.currentTimeMillis() + expiresInDays.longValue() * 86400000L);
        }
        try {
            McpAccessTokenStore.CreatedToken created = store.createToken(uid, name,
                    quotaLimit == null ? 0 : quotaLimit.intValue(), expiresAt);
            JsonObject data = created.getToken().toJson();
            // 明文只在此处返回一次，客户端需自行保存
            data.addProperty("token", created.getPlaintext());
            data.addProperty("paidBalance", store.getPaidBalance(uid));
            writeJson(response, 200, success(data));
            logger.info("为用户 " + uid + " 创建 MCP 访问令牌 #" + created.getToken().getId());
        } catch (java.sql.SQLException ex) {
            ex.printStackTrace();
            throw new McpException(McpProtocol.ERROR_INTERNAL, "创建令牌失败：" + ex.getMessage(), ex);
        }
    }

    /** 列出令牌（用户标识取自 URL 查询参数）。 */
    private void listTokens(Request request, Response response) {
        String uid = requireUid(request);
        JsonObject data = new JsonObject();
        JsonArray tokens = new JsonArray();
        for (McpAccessTokenStore.Token token : store.listTokens(uid)) {
            tokens.add(token.toJson());
        }
        data.add("tokens", tokens);
        data.addProperty("paidBalance", store.getPaidBalance(uid));
        data.addProperty("defaultPrice", defaultPrice);
        writeJson(response, 200, success(data));
    }

    /** 吊销或删除令牌（用户标识取自 URL 查询参数，请求体只带令牌 id）。 */
    private void updateTokenState(Request request, Response response, boolean delete) throws McpException {
        JsonObject body = readJsonObject(request);
        String uid = requireUid(request);
        Integer id = intField(body, "id");
        if (id == null) {
            throw new IllegalArgumentException("缺少令牌 id");
        }
        boolean changed = delete ? store.deleteToken(uid, id.longValue())
                : store.revokeToken(uid, id.longValue());
        JsonObject data = new JsonObject();
        data.addProperty("id", id.intValue());
        data.addProperty("changed", changed);
        writeJson(response, changed ? 200 : 404, changed ? success(data) : failure("令牌不存在或不属于该用户"));
    }

    /** 查询消费记录（用户标识取自 URL 查询参数）。 */
    private void usage(Request request, Response response) {
        String uid = requireUid(request);
        String tokenIdText = query(request, "tokenId");
        Integer limit = parseInt(query(request, "limit"));
        Long tokenId = null;
        if (tokenIdText != null && !tokenIdText.trim().isEmpty()) {
            try {
                tokenId = Long.valueOf(tokenIdText.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("tokenId 必须是数字");
            }
        }
        JsonArray records = new JsonArray();
        List<McpAccessTokenStore.UsageRecord> list = store.listUsage(uid, tokenId, limit == null ? 100 : limit.intValue());
        for (McpAccessTokenStore.UsageRecord record : list) {
            records.add(record.toJson());
        }
        JsonObject data = new JsonObject();
        data.add("records", records);
        data.addProperty("count", list.size());
        data.add("statistics", store.statistics(uid));
        writeJson(response, 200, success(data));
    }

    /** 查询或设置单工具价格。 */
    private void price(Request request, Response response, String method) throws McpException {
        if ("GET".equalsIgnoreCase(method)) {
            JsonObject data = new JsonObject();
            data.addProperty("defaultPrice", defaultPrice);
            JsonObject prices = new JsonObject();
            for (Map.Entry<String, Integer> entry : store.prices().entrySet()) {
                prices.addProperty(entry.getKey(), entry.getValue());
            }
            data.add("prices", prices);
            writeJson(response, 200, success(data));
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            writeJson(response, 405, failure("仅支持 GET/POST"));
            return;
        }
        JsonObject body = readJsonObject(request);
        String tool = stringField(body, "tool");
        Integer cost = intField(body, "cost");
        if (tool == null || tool.trim().isEmpty() || cost == null) {
            throw new IllegalArgumentException("缺少 tool 或 cost");
        }
        try {
            if (cost.intValue() < 0) {
                store.removePrice(tool.trim());
            } else {
                store.setPrice(tool.trim(), cost.intValue());
            }
            JsonObject data = new JsonObject();
            data.addProperty("tool", tool.trim());
            data.addProperty("defaultPrice", defaultPrice);
            data.addProperty("price", server.priceOf(tool.trim()));
            writeJson(response, 200, success(data));
        } catch (java.sql.SQLException ex) {
            ex.printStackTrace();
            throw new McpException(McpProtocol.ERROR_INTERNAL, "设置价格失败：" + ex.getMessage(), ex);
        }
    }

    /** 统计信息。 */
    private void status(Request request, Response response) {
        String uid = query(request, "uid");
        JsonObject data = new JsonObject();
        data.add("statistics", store.statistics(uid));
        data.addProperty("defaultPrice", defaultPrice);
        data.addProperty("serverName", serverName);
        JsonArray tools = new JsonArray();
        for (ToolData tool : registry.getStaticTools()) {
            tools.add(tool.name);
        }
        data.add("staticTools", tools);
        writeJson(response, 200, success(data));
    }

    /** 工具列表（管理用途，不需要令牌）。 */
    private void tools(Request request, Response response) {
        String uid = query(request, "uid");
        JsonArray tools = new JsonArray();
        for (ToolData tool : registry.list(uid)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", tool.name);
            item.addProperty("description", tool.description);
            item.addProperty("price", server.priceOf(tool.name));
            JsonObject params = new JsonObject();
            if (tool.params != null) {
                for (Map.Entry<String, String> entry : tool.params.entrySet()) {
                    params.addProperty(entry.getKey(), entry.getValue());
                }
            }
            item.add("params", params);
            tools.add(item);
        }
        JsonObject data = new JsonObject();
        data.add("tools", tools);
        data.addProperty("defaultPrice", defaultPrice);
        writeJson(response, 200, success(data));
    }

    /** 用令牌自检：返回令牌信息、剩余额度与工具数量。 */
    private void whoami(Request request, Response response) throws McpException {
        McpAccessTokenStore.Token token = authenticate(request);
        JsonObject data = token.toJson();
        data.addProperty("paidBalance", store.getPaidBalance(token.getUid()));
        data.addProperty("defaultPrice", defaultPrice);
        data.addProperty("toolCount", registry.list(token.getUid()).size());
        writeJson(response, 200, success(data));
    }

    // ==================== 工具方法 ====================

    /** 取 Bearer 或 X-MCP-Token 令牌并校验。 */
    private McpAccessTokenStore.Token authenticate(Request request) throws McpException {
        String plaintext = null;
        String authorization = request.getHeaders().get(McpProtocol.HEADER_AUTHORIZATION);
        if (authorization != null) {
            String trimmed = authorization.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                plaintext = trimmed.substring(7).trim();
            } else if (trimmed.regionMatches(true, 0, "Token ", 0, 6)) {
                plaintext = trimmed.substring(6).trim();
            } else if (!trimmed.isEmpty() && trimmed.indexOf(' ') < 0) {
                plaintext = trimmed;
            }
        }
        if (plaintext == null || plaintext.isEmpty()) {
            plaintext = request.getHeaders().get(McpProtocol.HEADER_MCP_TOKEN);
        }
        if (plaintext == null || plaintext.trim().isEmpty()) {
            throw McpException.unauthorized("缺少访问令牌，请在 Authorization 头中提供 Bearer mcp_xxx");
        }
        McpAccessTokenStore.Token token = store.findByPlaintext(plaintext.trim());
        if (token == null) {
            throw McpException.unauthorized("访问令牌无效");
        }
        if (!token.isUsable(System.currentTimeMillis())) {
            throw McpException.unauthorized("访问令牌已停用或已过期");
        }
        return token;
    }

    /** 在 initialize 请求时返回会话标识头。 */
    private void applySessionHeader(Request request, Response response, boolean initialize) {
        String existing = request.getHeaders().get(McpProtocol.HEADER_SESSION_ID);
        if (existing != null && !existing.isEmpty()) {
            response.setHeader(McpProtocol.HEADER_SESSION_ID, existing);
            return;
        }
        if (initialize) {
            response.setHeader(McpProtocol.HEADER_SESSION_ID, UUID.randomUUID().toString());
        }
        String protocolVersion = request.getHeaders().get(McpProtocol.HEADER_PROTOCOL_VERSION);
        if (protocolVersion != null && !protocolVersion.isEmpty()) {
            response.setHeader(McpProtocol.HEADER_PROTOCOL_VERSION, protocolVersion);
        }
    }

    /** 路径规范化：空串与带尾斜杠视为根路径。 */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    /** 是否为 MCP 协议端点路径。 */
    private static boolean isRpcPath(String path) {
        return "/".equals(path);
    }

    /** 读取请求体文本。 */
    private static String readBody(Request request) {
        ByteBuf body = request.getBody();
        if (body == null) {
            return "";
        }
        try {
            return body.toString(StandardCharsets.UTF_8);
        } catch (Throwable ex) {
            return "";
        }
    }

    /** 读取 JSON 对象请求体。 */
    private static JsonObject readJsonObject(Request request) throws McpException {
        String body = readBody(request);
        if (body.trim().isEmpty()) {
            return new JsonObject();
        }
        return McpJson.parseObject(body);
    }

    /** 客户端 IP。 */
    private static String remoteIp(Request request) {
        try {
            InetSocketAddress remote = request.getRemote();
            if (remote != null && remote.getAddress() != null) {
                return remote.getAddress().getHostAddress();
            }
        } catch (Throwable ex) {
            // 忽略：部分连接（如本地文件）没有远端地址
        }
        return null;
    }

    /** 读取查询参数（URL 解码由框架完成）。 */
    private static String query(Request request, String key) {
        Map<String, String> query = request.getQuery();
        return query == null ? null : query.get(key);
    }

    /** 读取 URL 查询参数中的用户标识（由外部框架注入）；缺失时返回 400。 */
    private static String requireUid(Request request) {
        String uid = query(request, "uid");
        if (uid == null || uid.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少用户标识");
        }
        return uid.trim();
    }

    /** 读取字符串字段。 */
    private static String stringField(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    /** 读取整数字段。 */
    private static Integer intField(JsonObject object, String key) {
        String text = stringField(object, key);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }

    /** 读取长整数字段。 */
    private static Long longField(JsonObject object, String key) {
        String text = stringField(object, key);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }

    /** 解析可空整数。 */
    private static Integer parseInt(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("limit 必须是整数");
        }
    }

    /** 写 JSON 响应（状态码 + JSON 内容类型）。 */
    private void writeJson(Response response, int status, JsonElement body) {
        byte[] bytes = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
        response.setHeader("Content-Type", McpProtocol.CONTENT_TYPE_JSON);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Expose-Headers", McpProtocol.HEADER_SESSION_ID);
        response.write(status, bytes);
    }

    /** 写 CORS 预检响应。 */
    private void writeCorsPreflight(Response response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers",
                "Authorization, Content-Type, " + McpProtocol.HEADER_SESSION_ID + ", "
                        + McpProtocol.HEADER_PROTOCOL_VERSION + ", " + McpProtocol.HEADER_MCP_TOKEN);
        response.setHeader("Access-Control-Max-Age", "86400");
        response.write(204);
    }

    /** 成功响应包装：{@code {"ok":true,"data":...}}。 */
    private static JsonObject success(JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.add("data", data == null ? new JsonObject() : data);
        return json;
    }

    /** 失败响应包装：{@code {"ok":false,"error":"..."}}。 */
    private static JsonObject failure(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", message == null ? "未知错误" : message);
        return json;
    }

    /** JSON-RPC 错误响应（HTTP 层使用）。 */
    private static JsonObject rpcError(JsonElement id, McpException ex) {
        return McpJson.error(id, ex);
    }
}
