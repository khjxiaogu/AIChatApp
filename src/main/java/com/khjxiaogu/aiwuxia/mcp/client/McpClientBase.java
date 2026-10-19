package com.khjxiaogu.aiwuxia.mcp.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpJson;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;

/**
 * MCP 客户端公共实现。
 * <p>
 * 把与传输无关的 MCP 语义（初始化握手、分页列出工具、调用工具并整理文本结果）
 * 集中在这里；子类只需实现两个原语：
 * </p>
 * <ul>
 *   <li>{@link #rpc(JsonElement, String, JsonObject)}：发送带 id 的请求并返回 {@code result}；</li>
 *   <li>{@link #notify(String, JsonObject)}：发送无 id 的通知。</li>
 * </ul>
 * <p>
 * 实例不保证线程安全，由 {@code McpClientManager} 串行初始化与刷新。
 * </p>
 */
public abstract class McpClientBase implements McpClient {

    /** 客户端名称。 */
    private final String name;
    /** JSON-RPC 请求 ID 生成器。 */
    private final AtomicLong requestIds = new AtomicLong();
    /** 协商后的协议版本。 */
    protected volatile String protocolVersion;
    /** 是否已完成初始化。 */
    protected volatile boolean initialized;

    /**
     * 创建客户端公共部分。
     *
     * @param name 客户端名称
     */
    protected McpClientBase(String name) {
        this.name = name == null || name.trim().isEmpty() ? "mcp" : name.trim();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public String getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public void initialize() throws IOException, McpException {
        if (initialized) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", McpProtocol.LATEST);
        // 本客户端不提供 sampling/roots 等高级能力，声明空能力集合
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "AIWuxia-MCP-Client");
        clientInfo.addProperty("version", "0.0.1");
        params.add("clientInfo", clientInfo);
        JsonObject result = rpc(nextId(), McpProtocol.METHOD_INITIALIZE, params);
        String version = McpProtocol.LATEST;
        if (result != null && result.has("protocolVersion") && !result.get("protocolVersion").isJsonNull()) {
            version = result.get("protocolVersion").getAsString();
        }
        if (!McpProtocol.supports(version)) {
            // 服务端返回了未知版本：按协议建议继续使用本端最新版本，仅在日志中提示
            System.err.println("[MCP] 服务端 " + name + " 返回未知协议版本 " + version + "，继续使用 "
                    + McpProtocol.LATEST);
        }
        protocolVersion = version;
        notify(McpProtocol.NOTIFICATION_INITIALIZED, null);
        initialized = true;
    }

    @Override
    public List<McpRemoteTool> listTools() throws IOException, McpException {
        initialize();
        List<McpRemoteTool> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JsonObject params = new JsonObject();
            if (cursor != null) {
                params.addProperty("cursor", cursor);
            }
            JsonObject result = rpc(nextId(), McpProtocol.METHOD_TOOLS_LIST, params);
            if (result == null || !result.has("tools") || !result.get("tools").isJsonArray()) {
                break;
            }
            for (JsonElement element : result.getAsJsonArray("tools")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                McpRemoteTool tool = parseTool(element.getAsJsonObject());
                if (tool != null && tool.getName() != null && !tool.getName().isEmpty()) {
                    tools.add(tool);
                }
            }
            cursor = result.has("nextCursor") && !result.get("nextCursor").isJsonNull()
                    ? result.get("nextCursor").getAsString()
                    : null;
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
        }
        return tools;
    }

    @Override
    public String callTool(String name, JsonObject arguments) throws IOException, McpException {
        initialize();
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        JsonObject result = rpc(nextId(), McpProtocol.METHOD_TOOLS_CALL, params);
        String text = McpJson.contentToText(result);
        boolean isError = result != null && result.has("isError") && !result.get("isError").isJsonNull()
                && result.get("isError").getAsBoolean();
        if (isError) {
            return text.isEmpty() ? "工具执行失败" : "工具执行失败：" + text;
        }
        return text;
    }

    /**
     * 发送 JSON-RPC 请求并返回 {@code result} 对象。
     *
     * @param id     请求 ID
     * @param method 方法名
     * @param params 参数对象，可为 null
     * @return 结果对象，永不 null（无结果时返回空对象）
     * @throws IOException  传输失败
     * @throws McpException 服务端返回 JSON-RPC 错误
     */
    protected abstract JsonObject rpc(JsonElement id, String method, JsonObject params) throws IOException, McpException;

    /**
     * 发送 JSON-RPC 通知（不等待响应）。
     *
     * @param method 方法名
     * @param params 参数对象，可为 null
     * @throws IOException  传输失败
     * @throws McpException 服务端拒绝
     */
    protected abstract void notify(String method, JsonObject params) throws IOException, McpException;

    /** 生成下一个请求 ID（数字自增，避免服务端无法匹配字符串 id）。 */
    protected JsonElement nextId() {
        return new JsonPrimitive(Long.valueOf(requestIds.incrementAndGet()));
    }

    /** 把 tools/list 的单个元素解析为远程工具描述。 */
    private McpRemoteTool parseTool(JsonObject json) {
        if (!json.has("name") || json.get("name").isJsonNull()) {
            return null;
        }
        String toolName = json.get("name").getAsString();
        String description = json.has("description") && !json.get("description").isJsonNull()
                ? json.get("description").getAsString()
                : "";
        JsonObject schema = json.has("inputSchema") && json.get("inputSchema").isJsonObject()
                ? json.getAsJsonObject("inputSchema")
                : new JsonObject();
        Map<String, String> params = new LinkedHashMap<>();
        if (schema.has("properties") && schema.get("properties").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : schema.getAsJsonObject("properties").entrySet()) {
                String propertyDescription = "";
                JsonElement property = entry.getValue();
                if (property != null && property.isJsonObject()) {
                    JsonObject propertyObject = property.getAsJsonObject();
                    if (propertyObject.has("description") && !propertyObject.get("description").isJsonNull()) {
                        propertyDescription = propertyObject.get("description").getAsString();
                    }
                    if (propertyDescription.isEmpty() && propertyObject.has("type")
                            && !propertyObject.get("type").isJsonNull()) {
                        propertyDescription = "（" + propertyObject.get("type").getAsString() + "）";
                    }
                }
                params.put(entry.getKey(), propertyDescription);
            }
        }
        return new McpRemoteTool(getName(), toolName, description, params, schema);
    }
}
