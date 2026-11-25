/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.multi;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.mcp.server.McpAccessTokenStore;
import com.khjxiaogu.aiwuxia.mcp.server.McpBuiltinTools;
import com.khjxiaogu.aiwuxia.mcp.server.McpServerEndpoint;
import com.khjxiaogu.aiwuxia.mcp.server.McpToolRegistry;
import com.khjxiaogu.aiwuxia.mcp.server.McpToolSource;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.SiteConfig;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;

/**
 * MultiAgent 自带的 MCP 服务端。
 * <p>
 * 与网页进程的 {@code /aichat/mcp} 完全独立：在 MultiAgent 进程内监听一个 HTTP 端口
 * （默认 {@value #DEFAULT_PORT}，路径 {@code /mcp}），令牌存在 {@code save/multi/mcp.db}，
 * 不依赖网页的 {@code user_paid_balance}。每个令牌对应一个私聊用户（QQ 号 / openid），
 * {@code tools/list} 只返回这个用户私聊 agent 自己的工具。
 * </p>
 * <p>
 * 本服务端不提供任何查看/编辑页面：令牌只能由 {@value #TOKEN_CREATE_TOOL} /
 * {@value #TOKEN_DELETE_TOOL} 两个工具创建和删除，{@code /token}、{@code /usage}、
 * {@code /status}、{@code /tools}、{@code /whoami} 等管理接口一律 404；只保留
 * {@code /media} 用于把工具生成的图片/语音/视频换成可访问链接。
 * </p>
 * <p>
 * 工具范围：返回该用户私聊 agent 当前注册的全部工具，但过滤掉 {@code sessionOnly}
 * （例如 {@code send_image} / {@code send_emoji_image} 这类必须由会话自己处理的工具）。
 * 文档库和工单工具已经改为非 sessionOnly，因此会出现在 MCP 工具列表里。
 * </p>
 */
public final class MultiAgentMcp {

    /** 默认监听端口：避开网页 8999、本机模型回调 8998 与本机模型网关 8997。 */
    public static final int DEFAULT_PORT = 8996;
    /** 默认 MCP 路径。 */
    public static final String DEFAULT_PATH = "/mcp";
    /** 创建令牌工具名。 */
    public static final String TOKEN_CREATE_TOOL = "mcp_token_create";
    /** 删除令牌工具名。 */
    public static final String TOKEN_DELETE_TOOL = "mcp_token_delete";

    /** 所属连接器，用于按 uid 查私聊 agent 与取对象存储。 */
    private final MultiAgentConnector connector;
    /** 令牌 SQLite 连接（独立于网页 messages.db）。 */
    private final Connection database;
    /** 免费令牌库。 */
    private final McpAccessTokenStore store;
    /** MCP 工具注册表。 */
    private final McpToolRegistry registry;
    /** MCP HTTP 端点。 */
    private final McpServerEndpoint endpoint;
    /** HTTP 服务构建器，持有它避免被回收。 */
    private final BasicWebServerBuilder serverBuilder;
    /** 对外公开的 MCP 地址（不含末尾斜杠）。 */
    private final String baseUrl;
    /** 监听端口。 */
    private final int port;
    /** MCP 路径。 */
    private final String path;

    /**
     * 启动 MCP 服务端；未启用或启动失败时返回 null（不影响 QQ 机器人本身）。
     *
     * @param dataFolder 运行数据目录（save/）
     * @param config     多 agent 配置
     * @param connector  所属连接器
     * @return 已启动的服务端，未启用/失败时为 null
     */
    public static MultiAgentMcp start(File dataFolder, MultiAgentConfig config, MultiAgentConnector connector) {
        if (config == null || !config.mcpEnabled) {
            return null;
        }
        try {
            return new MultiAgentMcp(dataFolder, config, connector);
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("[Multi] MCP 服务端启动失败：" + t);
            return null;
        }
    }

    private MultiAgentMcp(File dataFolder, MultiAgentConfig config, MultiAgentConnector connector) throws Exception {
        this.connector = connector;
        this.port = config.mcpPort <= 0 ? DEFAULT_PORT : config.mcpPort;
        this.path = config.mcpPath == null || config.mcpPath.isEmpty() ? DEFAULT_PATH : config.mcpPath;
        this.baseUrl = buildBaseUrl(dataFolder, config);
        Class.forName("org.sqlite.JDBC");
        File dbFile = new File(new File(dataFolder, "multi"), "mcp.db");
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        this.database = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        this.store = new McpAccessTokenStore(database, McpAccessTokenStore.DEFAULT_USAGE_TYPE, false);
        this.registry = new McpToolRegistry();
        McpBuiltinTools.register(registry);
        registry.addSource(new McpToolSource() {
            @Override
            public String getName() {
                return "multi-agent";
            }

            @Override
            public Collection<ToolData> getTools(String uid) {
                return connector.getPrivateSessionTools(uid);
            }
        });
        this.endpoint = new McpServerEndpoint(registry, store, "MultiAgent MCP", "0.0.1",
                this::resolveMediaUrl, connector.getObjectStorage(), false);
        this.serverBuilder = BasicWebServerBuilder.build();
        serverBuilder.createURIRoot()
                .createContext(path, endpoint)
                .complete()
                .setNotFound(new File(dataFolder, "404.html"))
                .compile()
                .serverHttp(port);
        System.out.println("[Multi] MCP 服务端已开启：" + baseUrl
                + "（免费、按用户隔离工具、不开放令牌管理页面）");
    }

    /**
     * 把一个私聊 agent 的令牌工具注册进它的工具表。
     * <p>
     * 公开群 agent 不注册：令牌代表“个人”，只属于私聊用户。重复调用会按工具名覆盖。
     * </p>
     *
     * @param session 会话
     */
    public void registerToolsForSession(AIGroupSession session) {
        if (!(session instanceof MultiAgentSession)) {
            return;
        }
        MultiAgentSession agent = (MultiAgentSession) session;
        if (agent.isPublicAgent()) {
            return;
        }
        String uid = agent.getPeer();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        for (ToolData tool : tokenTools(uid.trim())) {
            agent.getToolCallManager().registerTool(tool);
        }
    }

    /**
     * 为某个用户生成创建/删除令牌的工具。
     *
     * @param uid 用户账号（QQ 号 / openid）
     * @return 两个工具
     */
    private List<ToolData> tokenTools(String uid) {
        List<ToolData> tools = new ArrayList<>();
        tools.add(createTokenTool(uid));
        tools.add(deleteTokenTool(uid));
        return tools;
    }

    /** 创建令牌工具：返回一次性明文、id 与连接地址。 */
    private ToolData createTokenTool(final String uid) {
        return new ToolData.Builder(TOKEN_CREATE_TOOL,
                "为该用户创建一个 MCP 访问令牌。调用后把一次性明文、令牌 id 和服务地址告诉用户；"
                        + "明文只返回一次，请让用户立即保存。MCP 服务地址：" + baseUrl)
                .putOptionalParam("name", "令牌备注名，便于以后识别；可不填。", "备注")
                .putOptionalParam("expires_in_days", "有效天数；不填或 0 表示永不过期。", "有效天数")
                .sync().orderSensitive().maxCalls(8)
                .toolText((params, output, context) -> {
                    JsonObject json = parse(params);
                    String name = JsonConfig.getNonBlankString(json, "name", "");
                    int days = JsonConfig.getInt(json, "expires_in_days", 0);
                    Long expiresAt = days > 0
                            ? Long.valueOf(System.currentTimeMillis() + days * 86400000L) : null;
                    try {
                        McpAccessTokenStore.CreatedToken created = store.createToken(uid, name, 0, expiresAt);
                        StringBuilder text = new StringBuilder();
                        text.append("已创建 MCP 访问令牌。\n");
                        text.append("- id：").append(created.getToken().getId()).append('\n');
                        if (!name.isEmpty()) {
                            text.append("- 备注：").append(name).append('\n');
                        }
                        text.append("- 令牌：").append(created.getPlaintext()).append('\n');
                        text.append("- 有效期：")
                                .append(expiresAt == null ? "永不过期" : dateText(expiresAt.longValue()))
                                .append('\n');
                        text.append("- 服务地址：").append(baseUrl).append('\n');
                        text.append("连接方式：MCP 客户端使用 Streamable HTTP，请求头 Authorization: Bearer ")
                                .append(created.getPlaintext()).append("（也支持 X-MCP-Token）。\n");
                        text.append("令牌明文只显示这一次，请让用户立即保存，不要发到公开群聊。\n");
                        text.append("该令牌只能调用该用户本人的工具；需要删除时调用 ")
                                .append(TOKEN_DELETE_TOOL).append("，id=")
                                .append(created.getToken().getId()).append("。");
                        return text.toString();
                    } catch (Throwable ex) {
                        return "创建 MCP 访问令牌失败：" + describe(ex);
                    }
                })
                .build();
    }

    /** 删除令牌工具：支持 id 或明文定位。 */
    private ToolData deleteTokenTool(final String uid) {
        return new ToolData.Builder(TOKEN_DELETE_TOOL,
                "删除该用户的一个 MCP 访问令牌。可以传创建时返回的 id，或直接传令牌明文；删除后无法恢复。")
                .putOptionalParam("id", "创建令牌时返回的 id。", "令牌 id")
                .putOptionalParam("token", "令牌明文；不确定 id 时可以用它定位。", "令牌明文")
                .sync().orderSensitive().maxCalls(8)
                .toolText((params, output, context) -> {
                    JsonObject json = parse(params);
                    String idText = JsonConfig.getNonBlankString(json, "id");
                    String plaintext = JsonConfig.getNonBlankString(json, "token");
                    if (idText == null && plaintext == null) {
                        return "缺少 id 或 token：请提供创建时返回的令牌 id，或令牌明文。";
                    }
                    long id;
                    try {
                        if (idText != null) {
                            id = Long.parseLong(idText);
                        } else {
                            McpAccessTokenStore.Token token = store.findByPlaintext(plaintext);
                            if (token == null || !uid.equals(token.getUid())) {
                                return "没有找到属于当前用户的该令牌。";
                            }
                            id = token.getId();
                        }
                    } catch (NumberFormatException ex) {
                        return "令牌 id 必须是数字。";
                    }
                    boolean deleted = store.deleteToken(uid, id);
                    return deleted ? "已删除 MCP 访问令牌 id=" + id + "。"
                            : "没有找到 id=" + id + " 的令牌（可能已删除或不属于当前用户）。";
                })
                .build();
    }

    /**
     * 对外公开的 MCP 地址（不含末尾斜杠），例如 {@code https://example.com/mcp}。
     *
     * @return 服务地址
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** MCP 端点（供需要复用协议实现的调用方使用）。 */
    public McpServerEndpoint getEndpoint() {
        return endpoint;
    }

    /** 令牌存储（免费模式）。 */
    public McpAccessTokenStore getStore() {
        return store;
    }

    /** 停止 HTTP 服务（进程退出前可调用；一般不需要）。 */
    public void close() {
        try {
            serverBuilder.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ==================== 内部实现 ====================

    /** 按 {@code site.json} + 端口推导公开地址；配置了 {@code mcp.publicUrl} 时优先使用。 */
    private static String buildBaseUrl(File dataFolder, MultiAgentConfig config) {
        String explicit = config.mcpPublicUrl;
        if (explicit != null && !explicit.trim().isEmpty()) {
            String url = explicit.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        SiteConfig site = SiteConfig.resolve(dataFolder);
        if (!site.isConfigured()) {
            System.out.println("[Multi] 未配置 site.json 的 host，MCP 地址使用 127.0.0.1；"
                    + "如需公网地址请配置 multi.json 的 mcp.publicUrl");
        }
        // 本进程的 MCP 服务是明文 HTTP；要走 HTTPS 请配置 mcp.publicUrl（反向代理）
        int port = config.mcpPort <= 0 ? DEFAULT_PORT : config.mcpPort;
        boolean defaultPort = port == 80;
        String host = site.hostOr("127.0.0.1");
        // site.json 可能已经写了端口（例如 example.com:8443）：此时不再追加 MCP 端口，
        // 由反向代理按路径转发；需要精确地址时请显式配置 mcp.publicUrl。
        String authority = host.indexOf(':') >= 0 ? host : host + (defaultPort ? "" : ":" + port);
        return "http://" + authority + config.mcpPath;
    }

    /** 把工具结果里的媒体 id 换成 {@code /media} 链接。 */
    private String resolveMediaUrl(String id, Consumer<UsageIntf<?>> addUsage) {
        if (id == null || id.isEmpty()) {
            return id;
        }
        try {
            return baseUrl + "/media?file_id=" + URLEncoder.encode(id, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            // UTF-8 一定可用；这里只是编译期受检异常兜底
            return baseUrl + "/media?file_id=" + id;
        }
    }

    private static JsonObject parse(String params) {
        if (params == null || params.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(params);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Throwable ex) {
            return new JsonObject();
        }
    }

    private static String dateText(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(time));
    }

    private static String describe(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getName() : message;
    }
}
