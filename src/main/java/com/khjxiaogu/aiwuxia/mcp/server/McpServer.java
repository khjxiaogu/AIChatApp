package com.khjxiaogu.aiwuxia.mcp.server;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpJson;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;

/**
 * MCP 服务端的协议实现（与传输方式无关）。
 * <p>
 * 负责处理单条 JSON-RPC 消息：{@code initialize}、{@code ping}、{@code tools/list}、
 * {@code tools/call} 以及各类通知，并在 {@code tools/call} 前后完成计费：
 * </p>
 * <ol>
 *   <li>按 {@link McpToolRegistry} 查找当前用户可调用的 {@link ToolData}；</li>
 *   <li>按工具价格与令牌所属用户余额预扣费用（{@link McpAccessTokenStore#charge}）；</li>
 *   <li>执行工具（{@link ToolData#tool}，入参为 JSON 文本）；</li>
 *   <li>成功则补写消费记录，失败则退回预扣费用并以 {@code isError} 结果回传。</li>
 * </ol>
 * <p>
 * 本类不接触 HTTP、不解析 Authorization 头，认证结果（{@link McpAccessTokenStore.Token}）
 * 由传输层传入后调用 {@link #handle}，因此也可以被 stdio 等其他传输复用。
 * </p>
 */
public class McpServer {

    /** 工具注册表。 */
    private final McpToolRegistry registry;
    /** 令牌与计费存储。 */
    private final McpAccessTokenStore store;
    /** 未经价格表单独配置的工具使用的默认价格（额外配额单位）。 */
    private final int defaultPrice;
    /** 服务名称，写入 initialize 响应的 serverInfo。 */
    private final String serverName;
    /** 服务版本，写入 initialize 响应的 serverInfo。 */
    private final String serverVersion;

    /**
     * 创建协议实现。
     *
     * @param registry      工具注册表
     * @param store         令牌与计费存储
     * @param defaultPrice  默认单次调用价格（小于 0 按 0 处理）
     * @param serverName    服务名称，null 时使用 {@code AIWuxia-MCP}
     * @param serverVersion 服务版本，null 时使用 {@code 0.0.1}
     */
    public McpServer(McpToolRegistry registry, McpAccessTokenStore store, int defaultPrice, String serverName,
            String serverVersion) {
        this.registry = registry == null ? new McpToolRegistry() : registry;
        this.store = store;
        this.defaultPrice = Math.max(0, defaultPrice);
        this.serverName = serverName == null ? "AIWuxia-MCP" : serverName;
        this.serverVersion = serverVersion == null ? "0.0.1" : serverVersion;
    }

    /** 工具注册表。 */
    public McpToolRegistry getRegistry() {
        return registry;
    }

    /** 默认单次调用价格。 */
    public int getDefaultPrice() {
        return defaultPrice;
    }

    /**
     * 计算某个工具的调用价格（先查价格表，未配置时用默认价格）。
     *
     * @param tool 工具名称
     * @return 价格，不小于 0
     */
    public int priceOf(String tool) {
        return store == null ? defaultPrice : store.priceOf(tool, defaultPrice);
    }

    /**
     * 处理一条 JSON-RPC 请求。
     *
     * @param token     已认证的访问令牌
     * @param request   JSON-RPC 请求对象
     * @param clientIp  客户端 IP（写入消费记录，可为 null）
     * @return 响应对象；请求是通知（无 id）时返回 null
     * @throws McpException 请求非法、方法不存在、未授权、配额不足等
     */
    public JsonObject handle(McpAccessTokenStore.Token token, JsonObject request, String clientIp) throws McpException {
        if (request == null) {
            throw new McpException(McpProtocol.ERROR_INVALID_REQUEST, "请求为空");
        }
        if (!request.has("method") || request.get("method").isJsonNull()
                || !request.get("method").isJsonPrimitive()) {
            throw new McpException(McpProtocol.ERROR_INVALID_REQUEST, "缺少 method 字段");
        }
        String method = request.get("method").getAsString();
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();
        // 通知（无 id）不需要响应，也不做认证以外的任何事
        boolean notification = !request.has("id") || request.get("id").isJsonNull();
        switch (method) {
        case McpProtocol.METHOD_INITIALIZE:
            return handleInitialize(params);
        case McpProtocol.METHOD_PING:
            return McpJson.result(null, new JsonObject());
        case McpProtocol.METHOD_TOOLS_LIST:
            return handleToolsList(token);
        case McpProtocol.METHOD_TOOLS_CALL:
            return handleToolsCall(token, params, request.get("id"), clientIp);
        case McpProtocol.METHOD_RESOURCES_LIST:
        case McpProtocol.METHOD_RESOURCES_TEMPLATES_LIST:
        case McpProtocol.METHOD_PROMPTS_LIST:
            return emptyListResult(method);
        case McpProtocol.METHOD_LOGGING_SET_LEVEL:
        case McpProtocol.METHOD_COMPLETION_COMPLETE:
            return McpJson.result(null, new JsonObject());
        default:
            if (notification || method.startsWith("notifications/")) {
                return null;
            }
            throw McpException.methodNotFound(method);
        }
    }

    /**
     * 处理 initialize：协商协议版本并返回服务能力。
     *
     * @param params 客户端参数（可含 protocolVersion）
     * @return initialize 结果
     */
    public JsonObject handleInitialize(JsonObject params) {
        String requested = params != null && params.has("protocolVersion") && !params.get("protocolVersion").isJsonNull()
                ? params.get("protocolVersion").getAsString()
                : null;
        JsonObject toolsCapability = new JsonObject();
        // 本服务端不主动发送 tools/list_changed 通知，客户端每次请求都会拿到最新工具表
        toolsCapability.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCapability);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", serverName);
        serverInfo.addProperty("version", serverVersion);
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", McpProtocol.negotiate(requested));
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "本服务把 AIWuxia 的 ToolData 以 MCP 工具形式提供。调用工具会扣除访问令牌所属用户的额外配额。");
        return result;
    }

    /**
     * 处理 tools/list：返回当前令牌所属用户可见的工具及其 JSON Schema。
     *
     * @param token 已认证令牌
     * @return 工具列表结果
     */
    public JsonObject handleToolsList(McpAccessTokenStore.Token token) {
        List<ToolData> tools = registry.list(token == null ? null : token.getUid());
        JsonArray array = new JsonArray();
        for (ToolData tool : tools) {
            array.add(toMcpTool(tool, priceOf(tool.name)));
        }
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    /**
     * 处理 tools/call：查找工具 → 预扣费用 → 执行 → 记录消费/退款。
     *
     * @param token    已认证令牌
     * @param params   请求参数（name、arguments）
     * @param id       请求 ID（写入消费记录）
     * @param clientIp 客户端 IP
     * @return 工具调用结果
     * @throws McpException 参数错误、工具不存在、配额不足
     */
    public JsonObject handleToolsCall(McpAccessTokenStore.Token token, JsonObject params, JsonElement id,
            String clientIp) throws McpException {
        if (params == null || !params.has("name") || params.get("name").isJsonNull()) {
            throw McpException.invalidParams("缺少工具名称 name");
        }
        String name = params.get("name").getAsString();
        JsonObject arguments;
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            arguments = params.getAsJsonObject("arguments");
        } else {
            arguments = new JsonObject();
        }
        ToolData tool = registry.find(name, token == null ? null : token.getUid());
        if (tool == null || tool.tool == null) {
            throw McpException.invalidParams("工具不存在或当前不可用：" + name);
        }
        int price = priceOf(name);
        String requestId = id == null || id.isJsonNull() ? null : id.getAsString();
        McpAccessTokenStore.Charge charge = null;
        if (store != null) {
            charge = store.charge(token, name, price, clientIp, requestId);
        }
        long start = System.currentTimeMillis();
        String text;
        try {
            text = tool.tool.run(McpJson.stringifyArguments(arguments));
            if (text == null) {
                text = "";
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (store != null && charge != null) {
                store.refund(charge, ex.toString());
            }
            return errorResult("工具执行失败：" + describe(ex), price);
        }
        if (store != null && charge != null) {
            store.commit(charge, System.currentTimeMillis() - start, text.length());
        }
        return successResult(text, price, store == null || token == null ? null : store.getPaidBalance(token.getUid()));
    }

    // ==================== 内部实现 ====================

    /** 把 ToolData 转换为 MCP 工具描述。 */
    private JsonObject toMcpTool(ToolData tool, int price) {
        JsonObject json = new JsonObject();
        json.addProperty("name", tool.name);
        json.addProperty("description", tool.description == null ? "" : tool.description);
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        if (tool.params != null) {
            for (Entry<String, String> entry : tool.params.entrySet()) {
                JsonObject property = new JsonObject();
                property.addProperty("type", "string");
                property.addProperty("description", entry.getValue() == null ? "" : entry.getValue());
                properties.add(entry.getKey(), property);
                required.add(entry.getKey());
            }
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        json.add("inputSchema", schema);
        // 价格通过 _meta 暴露，方便客户端在调用前展示
        JsonObject meta = new JsonObject();
        meta.addProperty("price", price);
        json.add("_meta", meta);
        return json;
    }

    /** 构造文本成功结果。 */
    private static JsonObject successResult(String text, int price, Integer balance) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", McpProtocol.CONTENT_TYPE_TEXT);
        item.addProperty("text", text);
        content.add(item);
        result.add("content", content);
        JsonObject meta = new JsonObject();
        meta.addProperty("price", price);
        if (balance != null) {
            meta.addProperty("paidBalance", balance.intValue());
        }
        result.add("_meta", meta);
        return result;
    }

    /** 构造文本错误结果（工具内部失败，按 MCP 约定用 isError 而不是 JSON-RPC 错误）。 */
    private static JsonObject errorResult(String text, int price) {
        JsonObject result = successResult(text, price, null);
        result.addProperty("isError", true);
        return result;
    }

    /** 空列表结果（resources/prompts 等本服务不提供的类别）。 */
    private static JsonObject emptyListResult(String method) {
        JsonObject result = new JsonObject();
        String key = method.startsWith("resources/templates") ? "resourceTemplates"
                : method.startsWith("resources") ? "resources" : "prompts";
        result.add(key, new JsonArray());
        return result;
    }

    /** 工具异常的可读描述。 */
    private static String describe(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getName();
        }
        return message;
    }

    /** 当前可见工具（供管理接口展示）。 */
    public Collection<ToolData> listToolsFor(String uid) {
        return registry.list(uid);
    }

    /** 工具价格表（供管理接口展示）。 */
    public Map<String, Integer> prices() {
        return store == null ? new java.util.LinkedHashMap<String, Integer>() : store.prices();
    }
}
