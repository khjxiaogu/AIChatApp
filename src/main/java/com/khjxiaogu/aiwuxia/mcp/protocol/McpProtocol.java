package com.khjxiaogu.aiwuxia.mcp.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MCP（Model Context Protocol）协议常量与版本协商工具。
 * <p>
 * 本类只保存协议层面的“魔法值”，不包含任何传输或业务逻辑：
 * JSON-RPC 方法名、协议版本、自定义错误码、HTTP 头名称等。
 * 服务端（{@code mcp.server}）与客户端（{@code mcp.client}）共用这些常量，
 * 避免两边各写一份字符串导致不一致。
 * </p>
 * <p>
 * 版本协商规则：客户端在 {@code initialize} 中提出自己的版本，服务端若能识别则原样回显，
 * 否则返回本端最新版本（{@link #LATEST}），由客户端决定是否继续。
 * </p>
 */
public final class McpProtocol {

    private McpProtocol() {
    }

    /** 2024-11-05 版协议（最初的 HTTP+SSE 与 stdio 传输） */
    public static final String VERSION_2024_11_05 = "2024-11-05";
    /** 2025-03-26 版协议（引入 Streamable HTTP） */
    public static final String VERSION_2025_03_26 = "2025-03-26";
    /** 2025-06-18 版协议（当前实现所对齐的版本） */
    public static final String VERSION_2025_06_18 = "2025-06-18";

    /** 本实现默认使用的最新协议版本 */
    public static final String LATEST = VERSION_2025_06_18;

    /** 本实现可识别的全部协议版本 */
    public static final Set<String> SUPPORTED_VERSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(VERSION_2024_11_05, VERSION_2025_03_26, VERSION_2025_06_18)));

    // ==================== JSON-RPC 方法名 ====================

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_PING = "ping";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    public static final String METHOD_PROMPTS_LIST = "prompts/list";
    public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";
    public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

    public static final String NOTIFICATION_INITIALIZED = "notifications/initialized";
    public static final String NOTIFICATION_CANCELLED = "notifications/cancelled";
    public static final String NOTIFICATION_PROGRESS = "notifications/progress";
    public static final String NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

    // ==================== JSON-RPC 错误码 ====================

    /** 解析错误：请求体不是合法 JSON */
    public static final int ERROR_PARSE = -32700;
    /** 非法请求：缺少 jsonrpc/method 等必要字段 */
    public static final int ERROR_INVALID_REQUEST = -32600;
    /** 方法不存在 */
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    /** 参数错误（含工具不存在、参数缺失） */
    public static final int ERROR_INVALID_PARAMS = -32602;
    /** 服务端内部错误 */
    public static final int ERROR_INTERNAL = -32603;

    /** 未认证：令牌缺失、无效、被吊销或已过期 */
    public static final int ERROR_UNAUTHORIZED = -32001;
    /** 配额不足：用户额外配额或令牌额度不足 */
    public static final int ERROR_QUOTA_EXCEEDED = -32002;
    /** 工具执行失败：工具内部抛出的异常（与参数错误区分） */
    public static final int ERROR_TOOL_FAILED = -32003;

    // ==================== HTTP 头与传输常量 ====================

    /** Streamable HTTP 的会话标识头 */
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    /** 请求携带的协议版本头（2025-06-18 起） */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    /** 访问令牌使用标准 Authorization 头 */
    public static final String HEADER_AUTHORIZATION = "Authorization";
    /** 部分客户端不方便设置 Authorization 时使用的备用头 */
    public static final String HEADER_MCP_TOKEN = "X-MCP-Token";

    /** JSON 内容类型 */
    public static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
    /** SSE 内容类型 */
    public static final String CONTENT_TYPE_SSE = "text/event-stream; charset=UTF-8";

    /** 工具调用结果的文本类型标识 */
    public static final String CONTENT_TYPE_TEXT = "text";

    /**
     * 判断本实现是否支持某个协议版本。
     *
     * @param version 客户端提出的版本，可为 null
     * @return 支持返回 true
     */
    public static boolean supports(String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }

    /**
     * 协商协议版本：客户端版本受支持时原样回显，否则返回本实现的最新版本。
     *
     * @param requested 客户端在 initialize 中提出的版本，可为 null
     * @return 服务端实际使用的版本，永不为 null
     */
    public static String negotiate(String requested) {
        return supports(requested) ? requested : LATEST;
    }
}
