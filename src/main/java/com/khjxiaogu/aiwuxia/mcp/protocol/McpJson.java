package com.khjxiaogu.aiwuxia.mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * MCP 用到的 JSON 构造与解析工具。
 * <p>
 * 统一 JSON-RPC 消息的字段形状，避免服务端与客户端各自拼装：
 * </p>
 * <ul>
 *   <li>请求：{@code {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{...}}}</li>
 *   <li>成功响应：{@code {"jsonrpc":"2.0","id":1,"result":{...}}}</li>
 *   <li>错误响应：{@code {"jsonrpc":"2.0","id":1,"error":{"code":...,"message":...}}}</li>
 *   <li>通知：{@code {"jsonrpc":"2.0","method":"notifications/initialized"}}</li>
 * </ul>
 */
public final class McpJson {

    private McpJson() {
    }

    /** JSON-RPC 版本字符串。 */
    public static final String JSONRPC_VERSION = "2.0";

    /**
     * 解析任意 JSON 文本。
     *
     * @param text JSON 文本
     * @return 解析结果
     * @throws McpException 文本为空或不是合法 JSON 时抛出（{@link McpProtocol#ERROR_PARSE}）
     */
    public static JsonElement parse(String text) throws McpException {
        if (text == null || text.trim().isEmpty()) {
            throw new McpException(McpProtocol.ERROR_PARSE, "请求体为空");
        }
        try {
            return JsonParser.parseString(text);
        } catch (JsonSyntaxException | IllegalStateException ex) {
            throw new McpException(McpProtocol.ERROR_PARSE, "请求体不是合法 JSON", ex);
        }
    }

    /**
     * 解析并要求结果是一个 JSON 对象。
     *
     * @param text JSON 文本
     * @return JSON 对象
     * @throws McpException 解析失败或根节点不是对象时抛出
     */
    public static JsonObject parseObject(String text) throws McpException {
        JsonElement element = parse(text);
        if (!element.isJsonObject()) {
            throw new McpException(McpProtocol.ERROR_INVALID_REQUEST, "请求体必须是 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    /** 新建一个空 JSON 对象（语法糖，便于链式构造）。 */
    public static JsonObject object() {
        return new JsonObject();
    }

    /**
     * 构造成功响应。
     *
     * @param id    请求 ID，可为 null（序列化为 null）
     * @param result 结果对象，可为 null（序列化为空对象）
     * @return JSON-RPC 响应
     */
    public static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", JSONRPC_VERSION);
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", result == null ? new JsonObject() : result);
        return response;
    }

    /**
     * 构造错误响应。
     *
     * @param id    请求 ID，可为 null
     * @param error 错误对象（由 {@link McpException#toErrorObject()} 生成）
     * @return JSON-RPC 响应
     */
    public static JsonObject error(JsonElement id, JsonObject error) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", JSONRPC_VERSION);
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error == null ? new JsonObject() : error);
        return response;
    }

    /**
     * 由异常构造错误响应。
     *
     * @param id  请求 ID，可为 null
     * @param ex  异常
     * @return JSON-RPC 响应
     */
    public static JsonObject error(JsonElement id, McpException ex) {
        return error(id, ex.toErrorObject());
    }

    /**
     * 构造通知消息（无 id，不期望响应）。
     *
     * @param method 方法名
     * @param params 参数对象，可为 null
     * @return JSON-RPC 通知
     */
    public static JsonObject notification(String method, JsonObject params) {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", JSONRPC_VERSION);
        notification.addProperty("method", method);
        if (params != null) {
            notification.add("params", params);
        }
        return notification;
    }

    /**
     * 构造请求消息。
     *
     * @param id     请求 ID
     * @param method 方法名
     * @param params 参数对象，可为 null
     * @return JSON-RPC 请求
     */
    public static JsonObject request(JsonElement id, String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", JSONRPC_VERSION);
        request.add("id", id == null ? JsonNull.INSTANCE : id);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        return request;
    }

    /**
     * 把工具参数对象序列化为工具实现所接收的 JSON 文本。
     * <p>
     * 工具调用统一以 JSON 字符串作为入参（见 {@code com.khjxiaogu.aiwuxia.llm.Tool#run(String)}），
     * 因此服务端需要把 MCP 的 {@code arguments} 对象重新序列化。
     * </p>
     *
     * @param arguments 参数对象，可为 null
     * @return JSON 文本，null 视为 {@code {}}
     */
    public static String stringifyArguments(JsonObject arguments) {
        return arguments == null ? "{}" : arguments.toString();
    }

    /**
     * 提取工具调用结果中的文本内容。
     * <p>
     * MCP 的 {@code tools/call} 结果是内容数组，元素可能是 text / image / resource 等类型。
     * 本方法把其中的文本拼接返回，非文本内容以占位说明代替，保证模型至少能看到“有非文本结果”。
     * </p>
     *
     * @param result {@code tools/call} 的 result 对象
     * @return 拼接后的文本，永不为 null
     */
    public static String contentToText(JsonObject result) {
        if (result == null || !result.has("content") || !result.get("content").isJsonArray()) {
            return result == null ? "" : result.toString();
        }
        JsonArray content = result.getAsJsonArray("content");
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : content) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String type = item.has("type") && !item.get("type").isJsonNull() ? item.get("type").getAsString() : "";
            if (McpProtocol.CONTENT_TYPE_TEXT.equals(type)) {
                if (item.has("text") && !item.get("text").isJsonNull()) {
                    sb.append(item.get("text").getAsString());
                }
            } else if ("image".equals(type)) {
                sb.append("[图片内容]");
            } else if ("audio".equals(type)) {
                sb.append("[音频内容]");
            } else if ("resource".equals(type)) {
                sb.append("[资源内容]");
            } else if (!type.isEmpty()) {
                sb.append('[').append(type).append("内容]");
            }
        }
        return sb.toString();
    }
}
