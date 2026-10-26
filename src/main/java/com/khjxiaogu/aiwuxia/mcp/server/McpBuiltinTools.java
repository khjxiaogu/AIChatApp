package com.khjxiaogu.aiwuxia.mcp.server;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;

/**
 * MCP 服务端自带的基础工具。
 * <p>
 * 这些工具不依赖任何 AI 会话状态，用来给外部 MCP 客户端提供连通性自检能力
 * （初始化、列出工具、调用工具、扣费是否正常），也可以作为注册业务工具的参考示例。
 * 它们的实现与普通 MCP 模块一致：返回 {@link ToolData}，由
 * {@link McpToolRegistry#register(ToolData)} 暴露出去。
 * </p>
 */
public final class McpBuiltinTools {

    private McpBuiltinTools() {
    }

    /**
     * 把内置工具注册到指定注册表。
     *
     * @param registry 工具注册表，null 忽略
     */
    public static void register(McpToolRegistry registry) {
        if (registry == null) {
            return;
        }
        registry.register(echo());
        registry.register(serverTime());
    }

    /**
     * 回显工具：把 {@code text} 参数原样返回，用于验证整条调用链（含计费）是否正常。
     *
     * @return 工具定义
     */
    public static ToolData echo() {
        return new ToolData.Builder("mcp_echo", "回显工具：把 text 参数原样返回，可用于测试 MCP 连接与调用是否正常。")
                .putParam("text", "要回显的文本", "文本")
                .tool(arguments -> {
                    try {
                        JsonObject json = JsonParser.parseString(arguments).getAsJsonObject();
                        if (json.has("text") && !json.get("text").isJsonNull()) {
                            return json.get("text").getAsString();
                        }
                    } catch (Throwable ex) {
                        return "参数格式错误";
                    }
                    return "参数格式错误：缺少 text";
                })
                .build();
    }

    /**
     * 服务时间工具：返回服务端当前时间（无参数，用于验证无参工具）。
     *
     * @return 工具定义
     */
    public static ToolData serverTime() {
        return new ToolData.Builder("mcp_server_time", "获取 MCP 服务端当前时间，格式为 yyyy-MM-dd HH:mm:ss。")
                .tool(arguments -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
                .build();
    }
}
