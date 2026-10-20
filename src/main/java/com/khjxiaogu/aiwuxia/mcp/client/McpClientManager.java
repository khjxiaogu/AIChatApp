package com.khjxiaogu.aiwuxia.mcp.client;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.webserver.loging.SimpleLogger;

/**
 * MCP 客户端管理器：读取配置、建立连接、汇总工具。
 * <p>
 * 配置文件为 {@code save/mcp-client.json}（缺失时不启用任何远程服务），格式见 README 中的示例：
 * </p>
 * <pre>{@code
 * {
 *   "servers": [
 *     { "name": "aiwuxia", "type": "http", "url": "http://127.0.0.1:8999/aichat/mcp",
 *       "token": "mcp_xxx", "prefix": "wx_" },
 *     { "name": "filesystem", "type": "stdio",
 *       "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "I:\\data"],
 *       "prefix": "fs_" }
 *   ]
 * }
 * }</pre>
 * <p>
 * 每个服务端初始化后把工具桥接为 {@link ToolData}，再汇总为一份工具列表；
 * 宿主程序（{@code AIChatService}）把它注册到会话中，AI 就能像调用本地工具一样调用远程 MCP 工具。
 * 连接在后台线程进行，失败只记录日志，不影响主服务启动。
 * </p>
 */
public class McpClientManager implements Closeable {

    /** 配置文件。 */
    private final File configFile;
    /** 日志记录器。 */
    private final SimpleLogger logger = new SimpleLogger("MCP客户端");
    /** 已连接的客户端：配置名 → 客户端。 */
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    /** 各客户端的工具：配置名 → 工具列表。 */
    private final Map<String, List<ToolData>> serverTools = new LinkedHashMap<>();
    /** 各服务端的工具名前缀。 */
    private final Map<String, String> prefixes = new LinkedHashMap<>();
    /** 配置变更/连接完成后的回调。 */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** 汇总后的工具列表（整体替换，读取无需加锁）。 */
    private volatile List<ToolData> combined = new ArrayList<>();
    /** 是否已启动。 */
    private volatile boolean started;
    /** 最近一次失败原因，供状态接口展示。 */
    private volatile String lastError;

    /**
     * 创建管理器。
     *
     * @param configFile 配置文件（{@code save/mcp-client.json}），可为 null（等价于不启用）
     */
    public McpClientManager(File configFile) {
        this.configFile = configFile;
    }

    /** 配置文件。 */
    public File getConfigFile() {
        return configFile;
    }

    /**
     * 在后台线程启动并连接全部服务端。
     * <p>
     * 连接失败只记录日志，不抛异常；连接完成后会通知注册的监听器。
     * </p>
     */
    public void startAsync() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start();
                } catch (Throwable ex) {
                    logger.printStackTrace(ex);
                }
            }
        }, "mcp-client-startup");
        thread.setDaemon(true);
        thread.start();
    }

    /** 同步启动（会阻塞到所有服务端连接完成或超时）。 */
    public synchronized void start() {
        started = true;
        doReload();
    }

    /**
     * 重新连接全部服务端并刷新工具表。
     * <p>会先关闭旧连接，再按当前配置重新连接；单个服务端失败不影响其它服务端。</p>
     */
    public synchronized void reload() {
        doReload();
    }

    // ==================== 工具访问 ====================

    /**
     * 当前汇总工具表的快照（每次返回新集合，调用方可安全注册到会话）。
     *
     * @return 工具列表，永不 null
     */
    public List<ToolData> getTools() {
        return McpToolBridge.copyOf(combined);
    }

    /**
     * 是否已连接任意服务端。
     *
     * @return 至少有一个服务端连接成功返回 true
     */
    public boolean isConnected() {
        return !clients.isEmpty();
    }

    /** 已连接的服务端名称列表。 */
    public List<String> getServerNames() {
        synchronized (this) {
            return new ArrayList<>(clients.keySet());
        }
    }

    /**
     * 注册一个“工具表变化”监听器（连接完成或重新加载后触发）。
     *
     * @param listener 监听器，null 忽略
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 最近一次失败原因，无失败时为 null。 */
    public String getLastError() {
        return lastError;
    }

    /**
     * 状态描述（供管理接口展示，不包含令牌明文）。
     *
     * @return 状态 JSON
     */
    public synchronized JsonObject describe() {
        JsonObject json = new JsonObject();
        json.addProperty("configFile", configFile == null ? "" : configFile.getPath());
        json.addProperty("started", started);
        json.addProperty("connected", !clients.isEmpty());
        json.addProperty("toolCount", combined.size());
        if (lastError != null) {
            json.addProperty("lastError", lastError);
        }
        JsonArray servers = new JsonArray();
        for (Entry<String, McpClient> entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            JsonObject server = new JsonObject();
            server.addProperty("name", entry.getKey());
            server.addProperty("type", client instanceof McpStdioClient ? "stdio" : "http");
            server.addProperty("protocolVersion", client.getProtocolVersion());
            server.addProperty("prefix", prefixes.get(entry.getKey()));
            List<ToolData> tools = serverTools.get(entry.getKey());
            server.addProperty("toolCount", tools == null ? 0 : tools.size());
            servers.add(server);
        }
        json.add("servers", servers);
        return json;
    }

    @Override
    public synchronized void close() {
        started = false;
        for (McpClient client : clients.values()) {
            closeQuietly(client);
        }
        clients.clear();
        serverTools.clear();
        prefixes.clear();
        combined = new ArrayList<>();
    }

    // ==================== 连接实现 ====================

    /** 关闭旧连接并按配置重新连接。 */
    private void doReload() {
        for (McpClient client : clients.values()) {
            closeQuietly(client);
        }
        clients.clear();
        serverTools.clear();
        prefixes.clear();
        lastError = null;
        List<ToolData> merged = new ArrayList<>();
        Map<String, Boolean> mergedNames = new LinkedHashMap<>();
        if (configFile == null || !configFile.isFile()) {
            lastError = "未找到 MCP 客户端配置文件：" + (configFile == null ? "" : configFile.getPath());
            combined = merged;
            notifyListeners();
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(FileUtil.readString(configFile)).getAsJsonObject();
        } catch (Exception ex) {
            lastError = "解析 MCP 客户端配置失败：" + ex.getMessage();
            logger.warning(lastError);
            combined = merged;
            notifyListeners();
            return;
        }
        if (!root.has("servers") || !root.get("servers").isJsonArray()) {
            lastError = "MCP 客户端配置缺少 servers 数组";
            logger.warning(lastError);
            combined = merged;
            notifyListeners();
            return;
        }
        JsonArray servers = root.getAsJsonArray("servers");
        int index = 0;
        for (JsonElement element : servers) {
            index++;
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!bool(entry, "enabled", true)) {
                continue;
            }
            String name = string(entry, "name", "server" + index);
            String prefix = string(entry, "prefix", name + "_");
            McpClient client = null;
            try {
                client = createClient(name, entry);
                client.initialize();
                List<ToolData> tools = McpToolBridge.toTools(client, prefix);
                clients.put(name, client);
                serverTools.put(name, tools);
                prefixes.put(name, prefix);
                for (ToolData tool : tools) {
                    if (!mergedNames.containsKey(tool.name)) {
                        merged.add(tool);
                        mergedNames.put(tool.name, Boolean.TRUE);
                    } else {
                        logger.warning("MCP 工具重名，已忽略后注册的同名工具：" + tool.name);
                    }
                }
                logger.info("已连接 MCP 服务端 " + name + "（" + tools.size() + " 个工具，协议 "
                        + client.getProtocolVersion() + "）");
            } catch (Throwable ex) {
                if (client != null) {
                    closeQuietly(client);
                }
                lastError = "连接 MCP 服务端 " + name + " 失败：" + ex.getMessage();
                logger.warning(lastError);
            }
        }
        combined = merged;
        if (merged.isEmpty() && lastError == null) {
            lastError = "未连接任何 MCP 服务端";
        }
        notifyListeners();
    }

    /** 按配置条目创建客户端。 */
    private McpClient createClient(String name, JsonObject entry) {
        String type = string(entry, "type", "http");
        if ("stdio".equalsIgnoreCase(type)) {
            List<String> command = parseCommand(entry.get("command"));
            Map<String, String> environment = parseStringMap(entry.get("env"));
            File cwd = entry.has("cwd") && !entry.get("cwd").isJsonNull() ? new File(entry.get("cwd").getAsString())
                    : null;
            int timeout = integer(entry, "timeoutMs", 60000);
            return new McpStdioClient(name, command, environment, cwd, timeout);
        }
        String url = string(entry, "url", null);
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("http 类型的服务端缺少 url");
        }
        String token = string(entry, "token", null);
        Map<String, String> headers = parseStringMap(entry.get("headers"));
        int connectTimeout = integer(entry, "connectTimeoutMs", 10000);
        int readTimeout = integer(entry, "readTimeoutMs", 60000);
        return new McpHttpClient(name, url.trim(), token, headers, connectTimeout, readTimeout);
    }

    /** 通知监听器。 */
    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }
    }

    /** 关闭客户端并忽略异常。 */
    private static void closeQuietly(McpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ex) {
            // 忽略
        }
    }

    /** 读取 JSON 字符串字段。 */
    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    /** 读取 JSON 布尔字段。 */
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** 读取 JSON 整数字段。 */
    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** 解析启动命令：支持字符串数组，也支持单个命令行字符串（按空白切分）。 */
    private static List<String> parseCommand(JsonElement element) {
        List<String> command = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return command;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    command.add(item.getAsString());
                }
            }
            return command;
        }
        if (element.isJsonPrimitive()) {
            for (String part : element.getAsString().trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    command.add(part);
                }
            }
        }
        return command;
    }

    /** 解析字符串映射（用于 env/headers）。 */
    private static Map<String, String> parseStringMap(JsonElement element) {
        Map<String, String> map = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return map;
        }
        for (Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return map;
    }
}
