package com.khjxiaogu.aiwuxia.mcp.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;

/**
 * 把 MCP 远程工具桥接为本地 {@link ToolData}。
 * <p>
 * 远程工具通过 {@link McpClient} 执行，本地 AI 看到的则是普通的 {@code ToolData}：
 * </p>
 * <ul>
 *   <li>工具名按配置加前缀并做字符清洗（MCP 名称只允许字母、数字、下划线、短横线）；</li>
 *   <li>参数说明取自远程 {@code inputSchema.properties}；</li>
 *   <li>调用时把本地 JSON 文本参数解析为对象后发给远程服务端，
 *       返回的文本内容直接作为工具结果（失败时返回带“MCP工具调用失败”的说明文本）。</li>
 * </ul>
 */
public final class McpToolBridge {

    /** 工具名最大长度（与多数模型服务商的限制保持一致）。 */
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    private McpToolBridge() {
    }

    /**
     * 把一个 MCP 客户端提供的工具转换为本地工具集合。
     *
     * @param client 已初始化的 MCP 客户端
     * @param prefix 工具名前缀，可为 null
     * @return 本地工具列表（同名工具会自动改名保证唯一）
     * @throws IOException  读取远程工具列表失败
     * @throws McpException 协议错误
     */
    public static List<ToolData> toTools(McpClient client, String prefix) throws IOException, McpException {
        List<ToolData> tools = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<McpRemoteTool> remoteTools = client.listTools();
        for (final McpRemoteTool remote : remoteTools) {
            String localName = uniqueName(prefix, remote.getName(), used);
            String description = remote.getDescription();
            if (description == null || description.isEmpty()) {
                description = "来自 MCP 服务端 " + client.getName() + " 的工具 " + remote.getName();
            } else {
                description = "[MCP:" + client.getName() + "] " + description;
            }
            ToolData data = new ToolData.Builder(localName, description)
                    .params(remote.getParams())
                    .tool(arguments -> {
                        JsonObject parsed;
                        try {
                            parsed = JsonParser.parseString(arguments).getAsJsonObject();
                        } catch (RuntimeException ex) {
                            return "参数格式错误：需要 JSON 对象";
                        }
                        try {
                            return client.callTool(remote.getName(), parsed);
                        } catch (McpException ex) {
                            return "MCP工具调用失败：" + ex.getMessage();
                        } catch (IOException ex) {
                            return "MCP服务连接失败：" + ex.getMessage();
                        }
                    })
                    .build();
            tools.add(data);
        }
        return tools;
    }

    /**
     * 生成唯一的本地工具名。
     *
     * @param prefix     前缀，可为 null
     * @param remoteName 远程工具原名
     * @param used       已使用的名称集合（会被本方法写入）
     * @return 唯一且合法的工具名
     */
    public static String uniqueName(String prefix, String remoteName, Set<String> used) {
        String base = sanitize(prefix, remoteName);
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate)) {
            String tail = "_" + suffix;
            int keep = Math.max(1, MAX_TOOL_NAME_LENGTH - tail.length());
            candidate = (base.length() > keep ? base.substring(0, keep) : base) + tail;
            suffix++;
        }
        used.add(candidate);
        return candidate;
    }

    /**
     * 清洗工具名：只保留字母、数字、下划线、短横线，并限制总长度。
     *
     * @param prefix     前缀，可为 null
     * @param remoteName 远程工具名
     * @return 合法工具名
     */
    public static String sanitize(String prefix, String remoteName) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            appendLegal(sb, prefix);
        }
        if (remoteName != null) {
            appendLegal(sb, remoteName);
        }
        if (sb.length() == 0) {
            sb.append("mcp_tool");
        }
        String name = sb.toString();
        if (name.length() > MAX_TOOL_NAME_LENGTH) {
            name = name.substring(0, MAX_TOOL_NAME_LENGTH);
        }
        while (name.endsWith("_") || name.endsWith("-")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.isEmpty() ? "mcp_tool" : name;
    }

    /** 追加合法字符，非法字符替换为下划线。 */
    private static void appendLegal(StringBuilder sb, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
                    || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
    }

    /**
     * 把工具调用结果整理为单行摘要（用于日志/展示，可选）。
     *
     * @param text 原始结果
     * @param max  最大长度
     * @return 截断后的摘要
     */
    public static String summarize(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() > max ? single.substring(0, max) + "..." : single;
    }

    /**
     * 把 {@link ToolData} 集合复制到一个新的工具表（便于快照）。
     *
     * @param tools 工具集合
     * @return 新的工具列表
     */
    public static List<ToolData> copyOf(Collection<ToolData> tools) {
        List<ToolData> copy = new ArrayList<>();
        if (tools != null) {
            copy.addAll(tools);
        }
        return copy;
    }

    /**
     * 解析工具参数 JSON 文本，非法时返回空对象。
     *
     * @param arguments JSON 文本
     * @return 参数对象
     */
    public static JsonObject parseArguments(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(arguments);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    /** 便于调试：工具名到说明的映射。 */
    public static Map<String, String> describe(Collection<ToolData> tools) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tools != null) {
            for (ToolData tool : tools) {
                result.put(tool.name, tool.description);
            }
        }
        return result;
    }
}
