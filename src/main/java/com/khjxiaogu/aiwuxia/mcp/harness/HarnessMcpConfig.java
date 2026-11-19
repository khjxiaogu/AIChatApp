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
package com.khjxiaogu.aiwuxia.mcp.harness;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * DeepSeek Harness MCP 配置。
 * <p>
 * 配置优先级：{@code -D} 系统属性 &gt; {@code save/mcp.json} 的 {@code harness} 段 &gt; 默认值。
 * 工作区路径来自 {@code harness.workspaces} 或 {@code -Ddsh_mcp_workspaces=路径1;路径2}，
 * 列表里的每个目录都会出现在 {@code harness_workspaces} 结果中；聊天按会话记录的工作目录
 * 是否位于这些目录之下来筛选。
 * </p>
 * <p>
 * 常用系统属性与 {@link com.khjxiaogu.aiwuxia.DeepseekHarnessConnector} 保持一致：
 * {@code dsh_home}、{@code dsh_root}、{@code dsh_launcher}、{@code dsh_cli}、
 * {@code dsh_profile}、{@code dsh_provider}、{@code dsh_model}、{@code dsh_reasoning}、
 * {@code dsh_agent_preset}、{@code dsh_approval}。新增项为
 * {@code dsh_mcp_enabled}、{@code dsh_mcp_workspaces}、{@code dsh_mcp_data}。
 * </p>
 */
public final class HarnessMcpConfig {

    /** 是否启用 Harness MCP。 */
    public final boolean enabled;
    /** 对外展示的工作区根目录。 */
    public final List<File> workspaces;
    /** 插件、patch、心跳文件所在目录。 */
    public final File dataFolder;
    /** DSH_HOME。 */
    public final String dshHome;
    /** 安装根目录，null 表示自动探测。 */
    public final String dshRoot;
    /** 启动器路径，null 表示自动探测。 */
    public final String launcher;
    /** CLI 入口路径，null 表示自动探测。 */
    public final String cli;
    /** 运行的 dsh profile。 */
    public final String profile;
    /** web 表层端口，0 表示系统分配。 */
    public final int webPort;
    /** 是否让 web 表层打开浏览器。 */
    public final boolean openBrowser;
    /** 模型提供方。 */
    public final String provider;
    /** 模型 id。 */
    public final String model;
    /** 推理强度，空串表示不指定。 */
    public final String reasoning;
    /** agent preset。 */
    public final String agentPreset;
    /** 审批模式：ask / allow / deny / off。 */
    public final String approval;
    /** 等待一次工具审批的毫秒数。 */
    public final long approvalTimeoutMs;
    /** 等待一次提问回答的毫秒数。 */
    public final long questionTimeoutMs;
    /** 普通桥接请求的超时毫秒数。 */
    public final long requestTimeoutMs;
    /** 单次工具调用里 {@code harness_latest} 允许的最长等待秒数。 */
    public final int maxWaitSeconds;
    /** 是否启用 DSH 侧看门狗（Java 停止刷新心跳后让 DSH 自行退出）。 */
    public final boolean watchdog;
    /** 心跳间隔毫秒。 */
    public final long watchdogIntervalMs;
    /** 心跳超过多久没更新判定 Java 端已死。 */
    public final long heartbeatMaxAgeMs;

    private HarnessMcpConfig(JsonObject harness, File saveFolder) {
        this.enabled = optBool(harness, "enabled", true);
        List<File> configured = new ArrayList<>();
        String propertyWorkspaces = System.getProperty("dsh_mcp_workspaces");
        if (propertyWorkspaces != null && !propertyWorkspaces.trim().isEmpty()) {
            for (String part : propertyWorkspaces.split("[;,]")) {
                addWorkspace(configured, part);
            }
        } else if (harness.has("workspaces") && harness.get("workspaces").isJsonArray()) {
            JsonArray array = harness.getAsJsonArray("workspaces");
            for (JsonElement element : array) {
                if (element != null && element.isJsonPrimitive()) {
                    addWorkspace(configured, element.getAsString());
                }
            }
        }
        String cwd = opt(harness, "cwd", System.getProperty("dsh_mcp_cwd"));
        if (configured.isEmpty() && cwd != null && !cwd.trim().isEmpty()) {
            addWorkspace(configured, cwd);
        }
        if (configured.isEmpty()) {
            configured.add(new File(".").getAbsoluteFile());
        }
        this.workspaces = Collections.unmodifiableList(configured);

        String data = opt(harness, "dataFolder", System.getProperty("dsh_mcp_data"));
        File baseFolder = saveFolder == null ? new File("save") : saveFolder;
        this.dataFolder = data == null || data.trim().isEmpty()
                ? new File(baseFolder, "harness-mcp") : new File(data.trim());
        this.dshHome = opt(harness, "dshHome",
                System.getProperty("dsh_home", new File(System.getProperty("user.home", "."), ".dsh").getPath()));
        this.dshRoot = opt(harness, "dshRoot", System.getProperty("dsh_root"));
        this.launcher = opt(harness, "launcher", System.getProperty("dsh_launcher"));
        this.cli = opt(harness, "cli", System.getProperty("dsh_cli"));
        this.profile = normalizeProfile(opt(harness, "profile", System.getProperty("dsh_profile", "web")));
        this.webPort = (int) Math.max(0, optLong(harness, "webPort", 0));
        this.openBrowser = optBool(harness, "openBrowser", false);
        this.provider = opt(harness, "provider", System.getProperty("dsh_provider", "deepseek-account"));
        this.model = opt(harness, "model", System.getProperty("dsh_model", "deepseek-flash"));
        String effort = opt(harness, "reasoning", System.getProperty("dsh_reasoning", "high"));
        this.reasoning = effort == null ? "" : effort.trim();
        this.agentPreset = opt(harness, "agentPreset", System.getProperty("dsh_agent_preset", "standard"));
        String mode = opt(harness, "approval", System.getProperty("dsh_approval", "ask"));
        this.approval = mode == null || mode.trim().isEmpty() ? "ask" : mode.trim().toLowerCase();
        this.approvalTimeoutMs = Math.max(5000, optLong(harness, "approvalTimeoutMs", 120000));
        this.questionTimeoutMs = Math.max(5000, optLong(harness, "questionTimeoutMs", 120000));
        this.requestTimeoutMs = Math.max(10000, optLong(harness, "requestTimeoutMs", 120000));
        this.maxWaitSeconds = (int) Math.max(0, Math.min(25, optLong(harness, "maxWaitSeconds", 20)));
        this.watchdog = optBool(harness, "watchdog", true);
        this.watchdogIntervalMs = Math.max(1000, optLong(harness, "watchdogIntervalMs", 10000));
        this.heartbeatMaxAgeMs = Math.max(3000, optLong(harness, "heartbeatMaxAgeMs", 30000));
    }

    private static void addWorkspace(List<File> list, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        File file = new File(value.trim()).getAbsoluteFile();
        try {
            file = file.getCanonicalFile();
        } catch (Exception ignored) {
            // 路径不存在等情况下保留绝对路径
        }
        for (File existing : list) {
            if (existing.getPath().equalsIgnoreCase(file.getPath())) {
                return;
            }
        }
        list.add(file);
    }

    private static String normalizeProfile(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "web";
        }
        String profile = value.trim();
        // 这个 MCP 需要 workspaceRegistry / sessionController，只有 web 表层有；
        // 用户误配 sdk 时自动回退，避免工具全部不可用。
        return "sdk".equalsIgnoreCase(profile) ? "web" : profile;
    }

    private static String opt(JsonObject object, String key, String def) {
        String property = propertyName(key);
        String fromProperty = property == null ? null : System.getProperty(property);
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return fromProperty.trim();
        }
        return JsonConfig.getNonBlankString(object, key, def);
    }

    private static boolean optBool(JsonObject object, String key, boolean def) {
        String property = propertyName(key);
        String fromProperty = property == null ? null : System.getProperty(property);
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return Boolean.parseBoolean(fromProperty.trim());
        }
        return JsonConfig.getBool(object, key, def);
    }

    private static long optLong(JsonObject object, String key, long def) {
        String property = propertyName(key);
        String value = property == null ? null : System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            String fromConfig = JsonConfig.getNonBlankString(object, key);
            if (fromConfig != null) {
                value = fromConfig;
            }
        }
        if (value == null || value.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** JSON 键名到系统属性名：allow 这类通用词不加前缀，其余统一 dsh_mcp_ 前缀。 */
    private static String propertyName(String key) {
        if ("enabled".equals(key)) {
            return "dsh_mcp_enabled";
        }
        if ("workspaces".equals(key)) {
            return "dsh_mcp_workspaces";
        }
        switch (key) {
        case "dshHome":
            return "dsh_home";
        case "dshRoot":
            return "dsh_root";
        case "launcher":
            return "dsh_launcher";
        case "cli":
            return "dsh_cli";
        case "profile":
            return "dsh_profile";
        case "provider":
            return "dsh_provider";
        case "model":
            return "dsh_model";
        case "reasoning":
            return "dsh_reasoning";
        case "agentPreset":
            return "dsh_agent_preset";
        case "approval":
            return "dsh_approval";
        case "dataFolder":
            return "dsh_mcp_data";
        default:
            return "dsh_mcp_" + key;
        }
    }

    /**
     * 从 {@code save/mcp.json} 解析配置。
     *
     * @param saveFolder 数据目录
     * @param mcpConfig  {@code save/mcp.json} 内容
     * @return 配置；未启用时返回 null
     */
    public static HarnessMcpConfig from(File saveFolder, JsonObject mcpConfig) {
        JsonObject harness = null;
        if (mcpConfig != null && mcpConfig.has("harness") && mcpConfig.get("harness").isJsonObject()) {
            harness = mcpConfig.getAsJsonObject("harness");
        }
        // 也允许单独放一个 save/harness-mcp.json，避免所有配置都挤在 mcp.json 里
        File standalone = new File(saveFolder, "harness-mcp.json");
        if (harness == null && standalone.isFile()) {
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(FileUtil.readString(standalone));
                if (parsed != null && parsed.isJsonObject()) {
                    harness = parsed.getAsJsonObject();
                }
            } catch (Exception ex) {
                System.out.println("[Harness MCP] 无法解析 " + standalone + "：" + ex.getMessage());
            }
        }
        boolean propertyEnabled = Boolean.parseBoolean(System.getProperty("dsh_mcp_enabled", "false"));
        if (harness == null && !propertyEnabled) {
            return null;
        }
        HarnessMcpConfig config = new HarnessMcpConfig(harness == null ? new JsonObject() : harness, saveFolder);
        return config.enabled ? config : null;
    }

    /** 是否在配置中显式启用（用于日志提示）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 工作区路径列表（只读）。 */
    public List<File> getWorkspaces() {
        return workspaces;
    }
}
