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
package com.khjxiaogu.aiwuxia.codex;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Codex 连接器配置。
 * <p>
 * 优先级：{@code -D} 系统属性 &gt; {@code mcp.json}/{@code agents.json} 里的 {@code codex} 段 &gt; 默认值。
 * 所有开关都带 {@code codex_} 前缀，避免和既有配置撞名：
 * </p>
 * <table border="1">
 * <caption>系统属性</caption>
 * <tr><th>属性</th><th>默认值</th><th>含义</th></tr>
 * <tr><td>codex_enabled</td><td>true</td><td>是否启用</td></tr>
 * <tr><td>codex_launcher</td><td>自动探测</td><td>codex.exe / codex.cmd 路径</td></tr>
 * <tr><td>codex_home</td><td>空</td><td>CODEX_HOME；空表示沿用用户默认（~/.codex）</td></tr>
 * <tr><td>codex_cwd</td><td>save/codex-workspace</td><td>工作目录（Codex 动手改文件的范围）</td></tr>
 * <tr><td>codex_default_project</td><td>空</td><td>默认项目目录；存在时优先作为 cwd，未指定则用 save/codex-workspace</td></tr>
 * <tr><td>codex_roots</td><td>空</td><td>额外允许的工作目录，分号分隔</td></tr>
 * <tr><td>codex_allow_any_cwd</td><td>false</td><td>是否允许任意工作目录（危险）</td></tr>
 * <tr><td>codex_model</td><td>空</td><td>模型 id；空表示用 codex 自己的默认</td></tr>
 * <tr><td>codex_effort</td><td>空</td><td>推理强度：low/medium/high/ultra</td></tr>
 * <tr><td>codex_sandbox</td><td>workspace-write</td><td>read-only / workspace-write / danger-full-access</td></tr>
 * <tr><td>codex_approval</td><td>ask</td><td>审批处理：ask 私聊问管理员 / allow 一律放行 / deny 一律拒绝 / off 不接审批</td></tr>
 * <tr><td>codex_approval_policy</td><td>自动</td><td>覆盖下发给 Codex 的 approvalPolicy（untrusted/on-request/never）</td></tr>
 * <tr><td>codex_approvals_reviewer</td><td>user</td><td>审批路由：user / auto_review</td></tr>
 * <tr><td>codex_approval_timeout_ms</td><td>120000</td><td>等管理员答复审批的超时</td></tr>
 * <tr><td>codex_ai_review</td><td>true</td><td>审批先让对话 AI 预审：合理自动放行，不确定/危险才转人工</td></tr>
 * <tr><td>codex_open_approval</td><td>false</td><td>允许 AI 转人工时指定建议审核人；关闭时一律由管理员审核</td></tr>
 * <tr><td>codex_review_timeout_ms</td><td>30000</td><td>等对话 AI 预审答复的超时，超时转人工</td></tr>
 * <tr><td>codex_request_timeout_ms</td><td>60000</td><td>单条 JSON-RPC 请求超时</td></tr>
 * <tr><td>codex_turn_timeout_ms</td><td>900000</td><td>单轮最长执行时间，到点 turn/interrupt</td></tr>
 * <tr><td>codex_max_wait_seconds</td><td>20</td><td>工具里长轮询的最长等待秒数（上限 25）</td></tr>
 * <tr><td>codex_max_items</td><td>100</td><td>一次 latest 最多返回多少条事件</td></tr>
 * <tr><td>codex_history_turns</td><td>5</td><td>续聊时回放最近多少轮历史（0 关闭）</td></tr>
 * <tr><td>codex_max_event_chars</td><td>8000</td><td>单条事件文本上限（超出截断）</td></tr>
 * <tr><td>codex_ephemeral</td><td>false</td><td>是否用不落盘的临时线程（false 才能重启后续聊）</td></tr>
 * <tr><td>codex_data</td><td>save/codex-mcp</td><td>线程映射、日志所在目录</td></tr>
 * </table>
 */
public final class CodexConfig {

    /** 默认开发者指令：约束 Codex 在群聊场景下的行为。{@link CodexService} 会把它拼进 thread/start。 */
    public static final String DEFAULT_INSTRUCTIONS =
            "你在这个 QQ 机器人里工作，工具调用结果会转发给群友。\n"
            + "1. 只做用户要求的任务，不要主动扩大改动范围，不要删除与任务无关的文件。\n"
            + "2. 需要联网、写工作目录之外的文件、或执行有副作用的命令时，先说明理由再申请权限。\n"
            + "3. 最终回答用简体中文，先给结论，再给改动清单（文件路径）与验证方式；不要输出大段代码。\n"
            + "4. 不要假装执行过没有执行的操作；失败就直说失败原因和下一步建议。";

    /** 是否启用。 */
    public final boolean enabled;
    /** 启动器路径，null 表示自动探测。 */
    public final String launcher;
    /** CODEX_HOME，null 表示沿用用户默认。 */
    public final String home;
    /** 默认工作目录。 */
    public final File cwd;
    /** 运行数据目录（线程映射等）。 */
    public final File dataFolder;
    /** 模型 id，空串表示用 Codex 默认。 */
    public final String model;
    /** 推理强度，空串表示不指定。 */
    public final String effort;
    /** 沙箱模式。 */
    public final String sandbox;
    /** 下发给 Codex 的审批策略。 */
    public final String approvalPolicy;
    /** 审批处理模式：ask / allow / deny / off。 */
    public final String approval;
    /** 审批路由：user / auto_review。 */
    public final String approvalsReviewer;
    /** 是否先让对话 AI 预审 Codex 的审批请求（合理就自动放行）。 */
    public final boolean aiReview;
    /** 是否允许 AI 在转人工时指定建议审核人；关闭时一律由管理员审核。 */
    public final boolean openApproval;
    /** 等对话 AI 预审答复的超时毫秒；超时转人工。 */
    public final long reviewTimeoutMs;
    /** 等管理员答复审批的超时毫秒。 */
    public final long approvalTimeoutMs;
    /** 单条请求超时毫秒。 */
    public final long requestTimeoutMs;
    /** 单轮超时毫秒。 */
    public final long turnTimeoutMs;
    /** 长轮询最长秒数。 */
    public final int maxWaitSeconds;
    /** 一次返回的最大事件条数。 */
    public final int maxItems;
    /** 单条事件的文本上限。 */
    public final int maxEventChars;
    /** 续聊时最多回放多少轮历史（0 表示不回放）。 */
    public final int historyTurns;
    /** 是否使用临时（不落盘）线程。 */
    public final boolean ephemeral;
    /** 是否允许任意工作目录。 */
    public final boolean allowAnyCwd;
    /** workspace-write 时是否允许命令联网（Gradle 拉依赖等）。 */
    public final boolean networkAccess;
    /** 是否开启 app-server 的实验性接口（挂项目、可用决定列表等需要）。 */
    public final boolean experimental;
    /** 要挂载的 Codex 项目：项目 id（UUID）或项目名（从 Codex state 库解析），空表示不挂。 */
    public final String project;
    /** 显式项目 id：配置后不再查 Codex 状态库（项目改名/库被锁时更稳）。 */
    public final String projectId;
    /** 允许的工作目录（至少包含 {@link #cwd}）。 */
    public final List<File> roots;
    /** 是否把用户消息也回放给调用方（一般不需要）。 */
    public final boolean includeUserMessage;
    /** 追加到默认开发者指令后面的自定义说明。 */
    public final String instructions;

    private CodexConfig(JsonObject codex, File saveFolder) {
        this.enabled = optBool(codex, "enabled", "codex_enabled", true);
        String base = saveFolder == null ? "save" : saveFolder.getAbsolutePath();
        File workspace = new File(base, "codex-workspace");
        String cwdValue = trimToNull(opt(codex, "cwd", "codex_cwd", null));
        if (cwdValue == null) {
            File project = defaultProject();
            this.cwd = (project != null && project.isDirectory() ? project : workspace).getAbsoluteFile();
        } else {
            this.cwd = new File(expandHome(cwdValue)).getAbsoluteFile();
        }
        this.launcher = expandHome(trimToNull(opt(codex, "launcher", "codex_launcher", null)));
        this.home = expandHome(trimToNull(opt(codex, "home", "codex_home", null)));
        File data = new File(expandHome(opt(codex, "data", "codex_data",
                new File(base, "codex-mcp").getAbsolutePath())));
        this.dataFolder = data.getAbsoluteFile();
        this.model = opt(codex, "model", "codex_model", "").trim();
        this.effort = opt(codex, "effort", "codex_effort", "").trim();
        this.sandbox = normalizeSandbox(opt(codex, "sandbox", "codex_sandbox", "workspace-write"));
        this.approval = normalizeApproval(opt(codex, "approval", "codex_approval", "ask"));
        String policy = trimToNull(opt(codex, "approvalPolicy", "codex_approval_policy", null));
        this.approvalPolicy = policy == null ? defaultPolicy(this.approval) : normalizePolicy(policy);
        this.approvalsReviewer = normalizeReviewer(
                opt(codex, "approvalsReviewer", "codex_approvals_reviewer", "user"));
        this.aiReview = optBool(codex, "aiReview", "codex_ai_review", true);
        this.openApproval = optBool(codex, "openApproval", "codex_open_approval", false);
        this.reviewTimeoutMs = Math.max(5000,
                optLong(codex, "reviewTimeoutMs", "codex_review_timeout_ms", 30000));
        this.approvalTimeoutMs = Math.max(5000, optLong(codex, "approvalTimeoutMs", "codex_approval_timeout_ms", 120000));
        this.requestTimeoutMs = Math.max(10000, optLong(codex, "requestTimeoutMs", "codex_request_timeout_ms", 60000));
        this.turnTimeoutMs = Math.max(30000, optLong(codex, "turnTimeoutMs", "codex_turn_timeout_ms", 900000));
        this.maxWaitSeconds = (int) Math.max(0, Math.min(25, optLong(codex, "maxWaitSeconds", "codex_max_wait_seconds", 20)));
        this.maxItems = (int) Math.max(1, Math.min(200, optLong(codex, "maxItems", "codex_max_items", 100)));
        this.maxEventChars = (int) Math.max(500, optLong(codex, "maxEventChars", "codex_max_event_chars", 8000));
        this.historyTurns = (int) Math.max(0, Math.min(20,
                optLong(codex, "historyTurns", "codex_history_turns", 5)));
        this.ephemeral = optBool(codex, "ephemeral", "codex_ephemeral", false);
        this.allowAnyCwd = optBool(codex, "allowAnyCwd", "codex_allow_any_cwd", false);
        this.networkAccess = optBool(codex, "networkAccess", "codex_network_access", false);
        this.project = opt(codex, "project", "codex_project", "").trim();
        this.projectId = opt(codex, "projectId", "codex_project_id", "").trim();
        // 挂项目必须开实验性接口（thread/start.projectId 在 experimentalApi 后面）
        this.experimental = optBool(codex, "experimental", "codex_experimental",
                !this.project.isEmpty() || !this.projectId.isEmpty());
        this.includeUserMessage = optBool(codex, "includeUserMessage", "codex_include_user_message", false);
        this.instructions = opt(codex, "instructions", "codex_instructions", "").trim();

        List<File> configured = new ArrayList<>();
        addRoot(configured, this.cwd);
        // 默认项目目录始终允许：即使 cwd 被改成别处，也能用 codex_start 切回默认项目
        File project = defaultProject();
        if (project != null && project.isDirectory()) {
            addRoot(configured, project);
        }
        String rootsProperty = System.getProperty("codex_roots");
        if (rootsProperty != null && !rootsProperty.trim().isEmpty()) {
            for (String part : rootsProperty.split("[;,]")) {
                addRoot(configured, new File(part.trim()));
            }
        } else if (codex != null && codex.has("roots") && codex.get("roots").isJsonArray()) {
            JsonArray array = codex.getAsJsonArray("roots");
            for (JsonElement element : array) {
                if (element != null && element.isJsonPrimitive()) {
                    addRoot(configured, new File(element.getAsString()));
                }
            }
        }
        this.roots = Collections.unmodifiableList(configured);
    }

    /**
     * 从 {@code mcp.json} 或 agent 配置里解析。
     *
     * @param saveFolder 数据根目录
     * @param section    可能包含 {@code codex} 段的配置对象，可为 null
     * @return 配置；未启用或解析失败返回 null
     */
    public static CodexConfig from(File saveFolder, JsonObject section) {
        try {
            JsonObject codex = null;
            if (section != null && section.has("codex") && section.get("codex").isJsonObject()) {
                codex = section.getAsJsonObject("codex");
            } else if (section != null) {
                codex = section;
            }
            CodexConfig config = new CodexConfig(codex, saveFolder);
            return config.enabled ? config : null;
        } catch (Throwable t) {
            System.out.println("[Codex] 配置解析失败，已禁用：" + t);
            return null;
        }
    }

    /**
     * 从 QQ agent 配置（{@code config/agents.json} 的一项）解析：只认其中的 {@code codex} 段，
     * 没有该段时用默认值 + {@code -D} 覆盖（技能已启用即视为需要接入）。
     *
     * @param saveFolder  数据根目录
     * @param agentConfig agent 配置，可为 null
     * @return 配置；未启用或解析失败返回 null
     */
    public static CodexConfig fromAgent(File saveFolder, JsonObject agentConfig) {
        try {
            JsonObject codex = null;
            if (agentConfig != null && agentConfig.has("codex") && agentConfig.get("codex").isJsonObject()) {
                codex = agentConfig.getAsJsonObject("codex");
            }
            if (codex == null) {
                codex = new JsonObject();
            }
            // 开放审核权限也允许写在 agent 顶层，方便按 agent 开关
            if (!codex.has("openApproval") && agentConfig != null && agentConfig.has("openApproval")
                    && agentConfig.get("openApproval").isJsonPrimitive()) {
                codex.addProperty("openApproval", agentConfig.get("openApproval").getAsBoolean());
            }
            CodexConfig config = new CodexConfig(codex, saveFolder);
            return config.enabled ? config : null;
        } catch (Throwable t) {
            System.out.println("[Codex] agent 配置解析失败，已禁用：" + t);
            return null;
        }
    }

    /** 是否已配置启动器（未配置时由 {@link #detectLauncher()} 兜底）。 */
    public String launcherOrDetected() {
        return launcher != null ? launcher : detectLauncher();
    }

    /** 实际要用的 CODEX_HOME（null 表示不设置，沿用用户默认）。 */
    public File homeFolder() {
        return home == null ? null : new File(home);
    }

    /** 单轮超时秒数，用于工具提示。 */
    public int turnTimeoutSeconds() {
        return (int) (turnTimeoutMs / 1000L);
    }

    /**
     * 解析并校验工作目录：只允许配置范围内的目录。
     *
     * @param requested 请求的目录，可为 null
     * @return 绝对路径
     * @throws IllegalArgumentException 目录越界
     */
    public File resolveCwd(String requested) {
        File target = requested == null || requested.trim().isEmpty() ? cwd : new File(requested.trim());
        target = target.getAbsoluteFile();
        try {
            target = target.getCanonicalFile();
        } catch (Exception ignored) {
            // 目录不存在时保留绝对路径
        }
        if (!allowAnyCwd) {
            boolean allowed = false;
            for (File root : roots) {
                if (isUnder(target, root)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IllegalArgumentException("工作目录不在允许范围内：" + target.getPath()
                        + "（可用 -Dcodex_roots=... 追加）");
            }
        }
        if (!target.exists() && !target.mkdirs()) {
            throw new IllegalArgumentException("无法创建工作目录：" + target.getPath());
        }
        if (!target.isDirectory()) {
            throw new IllegalArgumentException("工作目录不是目录：" + target.getPath());
        }
        return target;
    }

    // ==================== 探测与解析 ====================

    /**
     * 自动探测 codex 可执行文件。
     * <p>
     * 依次尝试：{@code LOCALAPPDATA\OpenAI\Codex\bin\*\codex.exe}（桌面版内置 CLI）、
     * PATH 上的 codex.exe / codex.cmd、{@code APPDATA\npm\codex.cmd}、
     * {@code LOCALAPPDATA\Programs\codex\codex.exe}。
     * </p>
     *
     * @return 路径；找不到返回 null
     */
    public static String detectLauncher() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            File bin = new File(localAppData, "OpenAI/Codex/bin");
            File[] versions = bin.listFiles();
            if (versions != null) {
                File best = null;
                for (File dir : versions) {
                    File exe = new File(dir, "codex.exe");
                    if (exe.isFile() && (best == null || exe.lastModified() > best.lastModified())) {
                        best = exe;
                    }
                }
                if (best != null) {
                    return best.getPath();
                }
            }
            File programs = new File(localAppData, "Programs/codex/codex.exe");
            if (programs.isFile()) {
                return programs.getPath();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                if (dir == null || dir.trim().isEmpty()) {
                    continue;
                }
                for (String name : new String[] {"codex.exe", "codex.cmd", "codex.bat"}) {
                    File candidate = new File(dir.trim(), name);
                    if (candidate.isFile()) {
                        return candidate.getPath();
                    }
                }
            }
        }
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            File npm = new File(appData, "npm/codex.cmd");
            if (npm.isFile()) {
                return npm.getPath();
            }
        }
        return null;
    }

    /**
     * 读取 codex 版本（会启动一次 {@code codex --version}，结果缓存）。
     *
     * @return 版本号；不可用返回 null
     */
    public static String probeVersion(String launcher) {
        if (launcher == null || launcher.trim().isEmpty()) {
            return null;
        }
        try {
            List<String> command = new ArrayList<>();
            if (isBatch(launcher)) {
                command.add("cmd.exe");
                command.add("/c");
            }
            command.add(launcher);
            command.add("--version");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.toLowerCase().contains("warning")) {
                        out.append(line).append('\n');
                    }
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy();
                return null;
            }
            return out.toString().trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否需要通过 cmd.exe 执行。 */
    public static boolean isBatch(String launcher) {
        String lower = launcher.toLowerCase();
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    private static void addRoot(List<File> roots, File file) {
        if (file == null) {
            return;
        }
        File target = file.getAbsoluteFile();
        try {
            target = target.getCanonicalFile();
        } catch (Exception ignored) {
            // 保留绝对路径
        }
        for (File existing : roots) {
            if (existing.getPath().equalsIgnoreCase(target.getPath())) {
                return;
            }
        }
        roots.add(target);
    }

    private static boolean isUnder(File target, File root) {
        String path = target.getPath().replace('\\', '/').toLowerCase();
        String parent = root.getPath().replace('\\', '/').toLowerCase();
        while (parent.endsWith("/")) {
            parent = parent.substring(0, parent.length() - 1);
        }
        return path.equals(parent) || path.startsWith(parent + "/");
    }

    private static String defaultPolicy(String approval) {
        return "ask".equals(approval) || "deny".equals(approval) ? "on-request" : "never";
    }

    private static String normalizePolicy(String value) {
        String policy = value.trim().toLowerCase();
        if ("untrusted".equals(policy) || "on-request".equals(policy) || "never".equals(policy)) {
            return policy;
        }
        return "on-request";
    }

    private static String normalizeSandbox(String value) {
        String sandbox = value == null ? "" : value.trim().toLowerCase();
        if ("read-only".equals(sandbox) || "workspace-write".equals(sandbox)
                || "danger-full-access".equals(sandbox)) {
            return sandbox;
        }
        return "workspace-write";
    }

    private static String normalizeApproval(String value) {
        String approval = value == null ? "" : value.trim().toLowerCase();
        if ("allow".equals(approval) || "deny".equals(approval) || "off".equals(approval)
                || "ask".equals(approval) || "qq".equals(approval)) {
            return "qq".equals(approval) ? "ask" : approval;
        }
        return "ask";
    }

    private static String normalizeReviewer(String value) {
        String reviewer = value == null ? "" : value.trim().toLowerCase();
        if ("auto_review".equals(reviewer) || "guardian_subagent".equals(reviewer)) {
            return "auto_review";
        }
        return "user";
    }

    private static String opt(JsonObject object, String key, String property, String def) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return fromProperty.trim();
        }
        return JsonConfig.getNonBlankString(object, key, def);
    }

    private static boolean optBool(JsonObject object, String key, String property, boolean def) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return Boolean.parseBoolean(fromProperty.trim());
        }
        return JsonConfig.getBool(object, key, def);
    }

    private static long optLong(JsonObject object, String key, String property, long def) {
        String value = System.getProperty(property);
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

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * 默认项目目录：由 {@code -Dcodex_default_project=<绝对路径>} 指定，
     * 只有该目录存在时才作为默认工作目录；未指定或不存在时回退到 {@code <数据目录>/codex-workspace}。
     *
     * @return 未配置时返回 {@code null}
     */
    private static File defaultProject() {
        String value = trimToNull(System.getProperty("codex_default_project"));
        return value == null ? null : new File(expandHome(value));
    }

    /** 展开开头的 {@code ~} / {@code ~/}；Linux 服务里 cwd 写 ~ 时不展开会变成字面量目录。 */
    private static String expandHome(String value) {
        String path = trimToNull(value);
        if (path == null) {
            return null;
        }
        String home = System.getProperty("user.home", "");
        if (home.isEmpty()) {
            return path;
        }
        if ("~".equals(path)) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return new File(home, path.substring(2)).getPath();
        }
        return path;
    }
}
