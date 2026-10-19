package com.khjxiaogu.aiwuxia.mcp.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * 从 MCP 服务端发现的远程工具描述。
 * <p>
 * 是 MCP {@code tools/list} 返回条目在客户端的投影：名称、说明、参数说明与原始 JSON Schema。
 * {@link McpToolBridge} 会把它转换为本地 AI 可直接调用的 {@code ToolData}。
 * </p>
 */
public class McpRemoteTool {

    /** 工具名称（远程原名）。 */
    private final String name;
    /** 工具说明。 */
    private final String description;
    /** 参数名 → 参数说明（按 schema 顺序）。 */
    private final Map<String, String> params;
    /** 原始 inputSchema，保留以便后续扩展。 */
    private final JsonObject inputSchema;
    /** 所属服务端名称。 */
    private final String serverName;

    /**
     * 创建远程工具描述。
     *
     * @param serverName  服务端名称
     * @param name        工具名称
     * @param description 工具说明
     * @param params      参数名 → 说明
     * @param inputSchema 原始 JSON Schema
     */
    public McpRemoteTool(String serverName, String name, String description, Map<String, String> params,
            JsonObject inputSchema) {
        this.serverName = serverName;
        this.name = name;
        this.description = description;
        this.params = params == null ? new LinkedHashMap<String, String>()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.inputSchema = inputSchema;
    }

    /** 工具名称。 */
    public String getName() {
        return name;
    }

    /** 工具说明。 */
    public String getDescription() {
        return description;
    }

    /** 参数名 → 参数说明（只读）。 */
    public Map<String, String> getParams() {
        return params;
    }

    /** 原始 inputSchema，可能为 null。 */
    public JsonObject getInputSchema() {
        return inputSchema;
    }

    /** 所属服务端名称。 */
    public String getServerName() {
        return serverName;
    }
}
