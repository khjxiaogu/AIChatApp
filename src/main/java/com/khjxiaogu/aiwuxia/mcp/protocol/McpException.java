package com.khjxiaogu.aiwuxia.mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * MCP / JSON-RPC 异常。
 * <p>
 * 携带 JSON-RPC 标准错误码（{@link #getCode()}）与可选的附加数据（{@link #getData()}），
 * 服务端捕获后可直接序列化为 {@code {"jsonrpc":"2.0","id":...,"error":{"code":...,"message":...}}}
 * 响应；客户端收到错误响应时也用它向上抛出。
 * </p>
 */
public class McpException extends Exception {

    private static final long serialVersionUID = 1L;

    /** JSON-RPC 错误码，见 {@link McpProtocol} 中的 ERROR_* 常量 */
    private final int code;
    /** 附加数据，可为 null（序列化时省略） */
    private final transient JsonElement data;

    /**
     * 构造一个不带附加数据的异常。
     *
     * @param code    JSON-RPC 错误码
     * @param message 错误说明
     */
    public McpException(int code, String message) {
        this(code, message, null, null);
    }

    /**
     * 构造一个不带附加数据、带原因的异常。
     *
     * @param code    JSON-RPC 错误码
     * @param message 错误说明
     * @param cause   原因
     */
    public McpException(int code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    /**
     * 构造一个带附加数据的异常。
     *
     * @param code    JSON-RPC 错误码
     * @param message 错误说明
     * @param data    附加数据，可为 null
     * @param cause   原因，可为 null
     */
    public McpException(int code, String message, JsonElement data, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.data = data;
    }

    /** JSON-RPC 错误码。 */
    public int getCode() {
        return code;
    }

    /** 附加数据，可为 null。 */
    public JsonElement getData() {
        return data;
    }

    /**
     * 把本异常转换为 JSON-RPC 错误对象（不含 id）。
     *
     * @return {@code {"code":...,"message":...,"data":...}}，data 为 null 时省略该字段
     */
    public JsonObject toErrorObject() {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", getMessage() == null ? "未知错误" : getMessage());
        if (data != null) {
            error.add("data", data);
        }
        return error;
    }

    /**
     * 构造一个“未认证”异常。
     *
     * @param message 说明
     * @return 错误码为 {@link McpProtocol#ERROR_UNAUTHORIZED} 的异常
     */
    public static McpException unauthorized(String message) {
        return new McpException(McpProtocol.ERROR_UNAUTHORIZED, message);
    }

    /**
     * 构造一个“配额不足”异常。
     *
     * @param message 说明
     * @param data    附加数据（例如剩余额度），可为 null
     * @return 错误码为 {@link McpProtocol#ERROR_QUOTA_EXCEEDED} 的异常
     */
    public static McpException quotaExceeded(String message, JsonElement data) {
        return new McpException(McpProtocol.ERROR_QUOTA_EXCEEDED, message, data, null);
    }

    /**
     * 构造一个“参数错误”异常。
     *
     * @param message 说明
     * @return 错误码为 {@link McpProtocol#ERROR_INVALID_PARAMS} 的异常
     */
    public static McpException invalidParams(String message) {
        return new McpException(McpProtocol.ERROR_INVALID_PARAMS, message);
    }

    /**
     * 构造一个“方法不存在”异常。
     *
     * @param method 方法名
     * @return 错误码为 {@link McpProtocol#ERROR_METHOD_NOT_FOUND} 的异常
     */
    public static McpException methodNotFound(String method) {
        return new McpException(McpProtocol.ERROR_METHOD_NOT_FOUND, "不支持的方法：" + method);
    }

    /** 空 ID，用于无法确定请求 ID 时的错误响应。 */
    public static JsonElement nullId() {
        return JsonNull.INSTANCE;
    }
}
