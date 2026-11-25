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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * Codex 服务：管理一个常驻的 {@code codex app-server} 进程，并把它包装成「线程 + 轮次 + 事件流」。
 * <p>
 * 与 {@code HarnessMcpService} 的定位相同：上层（MCP 工具、QQ 连接器、agent 应用）只调用本类的
 * 高层方法，不接触 JSON-RPC 细节；本类负责：
 * </p>
 * <ul>
 *   <li>懒启动子进程、握手、崩溃后重试；</li>
 *   <li>线程（会话）创建、续聊、归属隔离（QQ 群 / MCP 用户各看各的）；</li>
 *   <li>把 {@code item/*}、{@code turn/*}、{@code error} 通知归一化成带序号的增量事件；</li>
 *   <li>把 Codex 的审批/提问转成待处理任务，按配置自动放行/拒绝/问管理员；</li>
 *   <li>单轮超时自动 {@code turn/interrupt}，线程映射落盘。</li>
 * </ul>
 * <p>
 * 所有公开方法都不抛受检异常以外的东西：失败统一返回 {@code {"ok":false,"error":"..."}} 形态的
 * JSON，方便直接回给模型或写进 MCP 结果。
 * </p>
 */
public final class CodexService implements Closeable {

    private final CodexConfig config;
    private final CodexProcess process;
    private final File stateFile;
    private final ScheduledExecutorService scheduler;

    /** threadId -> 线程状态。 */
    private final Map<String, CodexThreadState> threads = new LinkedHashMap<>();
    /** 归属键 -> 默认 threadId。 */
    private final Map<String, String> ownerDefault = new LinkedHashMap<>();
    /** taskId -> 待处理交互。 */
    private final Map<String, CodexThreadState.Pending> pendingByTask = new LinkedHashMap<>();
    /** threadId -> 等待本轮结束的将来（异步工具用）。 */
    private final Map<String, CompletableFuture<JsonObject>> turnWaiters = new java.util.concurrent.ConcurrentHashMap<>();
    /** 归属键 -> 审批管理员。 */
    private final Map<String, List<String>> ownerAdmin = new LinkedHashMap<>();
    /** 已打印“对话开始”的轮次，避免 send / turn started 重复。 */
    private final java.util.Set<String> loggedTurnStarts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 轮次开始时间，用于结束日志耗时。 */
    private final Map<String, Long> turnStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
    /** 归属键 → 该会话默认工作目录（多 agent 里每个用户可不同）。 */
    private final Map<String, String> ownerCwd = new java.util.concurrent.ConcurrentHashMap<>();
    /** 归属键 → Codex 项目名/项目 id（多 agent 里每个用户可不同）。 */
    private final Map<String, String> ownerProject = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> ownerProjectId = new java.util.concurrent.ConcurrentHashMap<>();
    /** 归属键 → 是否开放审核权限（允许 AI 指定建议审核人）。 */
    private final Map<String, Boolean> ownerOpenApproval = new LinkedHashMap<>();

    private final Object startLock = new Object();
    private final AtomicLong taskSeq = new AtomicLong();

    private volatile CodexApprovalBridge bridge;
    /** 对话 AI 预审回调：审批先交给当前会话的 agent 判断。 */
    private volatile CodexReviewHandler reviewHandler;
    /** 对象存储：Codex 产出的图片先存这里换成 id。 */
    private volatile ObjectStorageProvider storage;
    private volatile String startError = "";
    private volatile long lastStartAttempt;
    private volatile String version;
    private volatile boolean versionProbed;
    private volatile boolean closed;

    public CodexService(CodexConfig config) {
        this(config, null);
    }

    /**
     * @param config  配置
     * @param storage 对象存储；Codex 产出的图片会先存这里并换成图片 id，可为 null（退回 data URL）
     */
    public CodexService(CodexConfig config, ObjectStorageProvider storage) {
        this.config = config;
        this.storage = storage;
        this.stateFile = new File(config.dataFolder, "threads.json");
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "codex-service");
            thread.setDaemon(true);
            return thread;
        });
        this.process = new CodexProcess(config, new CodexProcess.Listener() {
            @Override
            public void onNotification(String method, JsonObject params) {
                handleNotification(method, params);
            }

            @Override
            public void onServerRequest(JsonElement id, String method, JsonObject params) {
                handleServerRequest(id, method, params);
            }

            @Override
            public void onClosed(String reason) {
                startError = reason;
                resetLoaded();
                endRunningTurns(reason);
            }
        });
        loadState();
    }

    /** 配置。 */
    public CodexConfig getConfig() {
        return config;
    }

    /** 对象存储（可为 null）。 */
    public ObjectStorageProvider getStorage() {
        return storage;
    }

    /** 设置对象存储；已创建的服务也可以后补。 */
    public void setStorage(ObjectStorageProvider storage) {
        this.storage = storage;
    }

    /** 设置审批桥（QQ 私聊等）；可重复设置。 */
    public void setApprovalBridge(CodexApprovalBridge bridge) {
        this.bridge = bridge;
    }

    /** 设置对话 AI 预审回调；未设置时审批直接转人工。 */
    public void setReviewHandler(CodexReviewHandler reviewHandler) {
        this.reviewHandler = reviewHandler;
    }

    /** 设置某个归属键的默认工作目录；startThread 未显式给 cwd 时使用。 */
    public void setOwnerCwd(String owner, File cwd) {
        if (owner == null || cwd == null) {
            return;
        }
        ownerCwd.put(owner, cwd.getAbsolutePath());
    }

    /** 设置某个归属键的 Codex 项目（多 agent 里每个用户不同项目时使用）。 */
    public void setOwnerProject(String owner, String project, String projectId) {
        if (owner == null) {
            return;
        }
        if (project != null && !project.trim().isEmpty()) {
            ownerProject.put(owner, project.trim());
        }
        if (projectId != null && !projectId.trim().isEmpty()) {
            ownerProjectId.put(owner, projectId.trim());
        }
    }

    /** 解析线程工作目录：显式 cwd 优先，其次该归属键的默认 cwd，最后全局 cwd。 */
    private File resolveOwnerCwd(String owner, String cwd) throws java.io.IOException {
        if (cwd != null && !cwd.trim().isEmpty()) {
            return config.resolveCwd(cwd);
        }
        String ownerPath = owner == null ? null : ownerCwd.get(owner);
        if (ownerPath != null && !ownerPath.trim().isEmpty()) {
            File file = new File(ownerPath).getAbsoluteFile();
            if (!file.isDirectory()) {
                throw new java.io.IOException("该用户的工作目录不存在：" + file);
            }
            return file;
        }
        return config.resolveCwd(null);
    }

    /** 设置某个归属键对应的审批管理员；null/空表示没有管理员。 */
    public void setOwnerAdmin(String owner, String adminId) {
        setOwnerAdmins(owner, adminId == null || adminId.trim().isEmpty()
                ? null : java.util.Collections.singletonList(adminId.trim()));
    }

    /** 兼容旧调用：数字 QQ 号转字符串；0 表示没有管理员。 */
    public void setOwnerAdmin(String owner, long adminId) {
        setOwnerAdmin(owner, adminId > 0 ? String.valueOf(adminId) : "");
    }

    /** 设置某个归属键对应的多个审批管理员；null/空表示没有管理员。 */
    public void setOwnerAdmins(String owner, List<String> adminIds) {
        if (owner == null) {
            return;
        }
        List<String> clean = new ArrayList<>();
        if (adminIds != null) {
            for (String raw : adminIds) {
                String id = raw == null ? "" : raw.trim();
                if (!id.isEmpty() && !"0".equals(id) && !clean.contains(id)) {
                    clean.add(id);
                }
            }
        }
        synchronized (ownerAdmin) {
            if (clean.isEmpty()) {
                ownerAdmin.remove(owner);
            } else {
                ownerAdmin.put(owner, java.util.Collections.unmodifiableList(clean));
            }
        }
    }

    /** 设置某个归属键是否开放审核权限（允许 AI 指定建议审核人）。 */
    public void setOwnerOpenApproval(String owner, boolean open) {
        if (owner == null) {
            return;
        }
        synchronized (ownerOpenApproval) {
            ownerOpenApproval.put(owner, open);
        }
    }

    /** 该归属键是否开放审核权限；未单独设置时退回全局配置。 */
    private boolean isOpenApproval(String owner) {
        synchronized (ownerOpenApproval) {
            Boolean value = ownerOpenApproval.get(owner);
            return value == null ? config.openApproval : value.booleanValue();
        }
    }

    /** 后台启动，不阻塞调用方。 */
    public void startAsync() {
        Thread thread = new Thread(() -> {
            try {
                ensureStarted();
            } catch (Throwable t) {
                log("后台启动失败：" + t.getMessage());
            }
        }, "codex-boot");
        thread.setDaemon(true);
        thread.start();
    }

    // ==================== 状态与线程 ====================

    /**
     * 运行状态。
     *
     * @return 状态 JSON
     */
    public JsonObject status() {
        String launcher = config.launcherOrDetected();
        if (!versionProbed) {
            versionProbed = true;
            version = launcher == null ? null : CodexConfig.probeVersion(launcher);
        }
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("installed", launcher != null);
        json.addProperty("launcher", launcher == null ? "" : launcher);
        json.addProperty("version", version == null ? "" : version);
        json.addProperty("running", process.isReady());
        json.addProperty("process_alive", process.isAlive());
        json.addProperty("last_error", startError);
        json.addProperty("cwd", config.cwd.getPath());
        json.addProperty("model", config.model);
        json.addProperty("sandbox", config.sandbox);
        json.addProperty("approval", config.approval);
        json.addProperty("approval_policy", config.approvalPolicy);
        json.addProperty("experimental", config.experimental);
        json.addProperty("project", config.project);
        String projectId = CodexProjects.resolve(config);
        json.addProperty("project_id", projectId == null ? "" : projectId);
        if (!config.project.isEmpty() && projectId == null) {
            json.addProperty("project_hint", "没有解析到项目「" + config.project
                    + "」，线程将不挂项目；可改用 -Dcodex_project=<项目id>");
        }
        json.addProperty("data_folder", config.dataFolder.getPath());
        json.addProperty("turn_timeout_seconds", config.turnTimeoutSeconds());
        synchronized (this) {
            json.addProperty("thread_count", threads.size());
            json.addProperty("pending_count", pendingByTask.size());
        }
        if (launcher == null) {
            json.addProperty("hint", "未找到 codex 可执行文件：请安装 Codex CLI，或用 -Dcodex_launcher=... 指定");
        } else if (version == null) {
            json.addProperty("hint", "codex 存在但 --version 没有输出，请检查安装是否完整");
        }
        return json;
    }

    /**
     * 列出某个归属键下的线程。
     *
     * @param owner 归属键
     * @return 线程列表
     */
    public synchronized JsonObject threads(String owner) {
        JsonArray array = new JsonArray();
        String current = ownerDefault.get(owner);
        for (CodexThreadState state : threads.values()) {
            if (owner != null && !owner.equals(state.owner)) {
                continue;
            }
            array.add(summary(state, state.threadId.equals(current)));
        }
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("count", array.size());
        json.add("threads", array);
        return json;
    }

    /**
     * 新建一个线程（会话）。
     *
     * @param owner 归属键
     * @param cwd   工作目录，可为 null
     * @param title 标题，可为 null
     * @return 线程摘要
     */
    public JsonObject startThread(String owner, String cwd, String title) {
        try {
            ensureStarted();
            File target = resolveOwnerCwd(owner, cwd);
            String project = owner == null ? null : ownerProject.get(owner);
            if (project == null) {
                project = config.project;
            }
            String explicitProjectId = owner == null ? null : ownerProjectId.get(owner);
            if (explicitProjectId == null) {
                explicitProjectId = config.projectId;
            }
            String projectId = CodexProjects.resolve(config, project, explicitProjectId);
            if (projectId == null && project != null && !project.trim().isEmpty()) {
                // 项目名不存在时按当前线程目录自动创建，避免每个用户都要先去 Codex UI 建项目
                projectId = createProject(project.trim(), target);
            }
            JsonObject result;
            try {
                result = process.request("thread/start", threadStartParams(target, projectId),
                        Math.max(config.requestTimeoutMs, 180000L));
            } catch (IOException ex) {
                if (projectId == null) {
                    throw ex;
                }
                // 项目被删/没有权限时退化成不挂项目，保证会话仍可用
                log("挂项目失败（" + ex.getMessage() + "），改为不挂项目重试");
                projectId = null;
                result = process.request("thread/start", threadStartParams(target, null),
                        Math.max(config.requestTimeoutMs, 180000L));
            }
            JsonObject thread = result.has("thread") && result.get("thread").isJsonObject()
                    ? result.getAsJsonObject("thread") : new JsonObject();
            String id = JsonConfig.getString(thread, "id");
            if (id == null || id.isEmpty()) {
                throw new IOException("thread/start 没有返回 thread.id");
            }
            CodexThreadState state = new CodexThreadState(id, owner, target.getAbsolutePath());
            state.model = JsonConfig.getString(result, "model");
            state.historyLoaded = true;
            String attached = JsonConfig.getString(thread, "projectId");
            state.projectId = attached != null ? attached : (projectId == null ? "" : projectId);
            synchronized (this) {
                threads.put(id, state);
                ownerDefault.put(owner, id);
            }
            if (title != null && !title.trim().isEmpty()) {
                nameThread(id, title.trim());
            }
            state.addEvent("notice").addProperty("text",
                    "已创建 Codex 会话 " + id + "（工作目录 " + target.getPath() + "）");
            saveState();
            log("新建线程 " + id + "（归属 " + owner + "，cwd " + target.getPath() + "）");
            JsonObject json = summary(state, true);
            json.addProperty("ok", true);
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    // ==================== 投递与读取 ====================

    /**
     * 向线程投递一条任务；不指定 threadId 时用该归属键的默认线程，没有就新建。
     *
     * @param owner       归属键
     * @param threadId    线程 id，可为空
     * @param text        任务正文
     * @param waitSeconds 可选等待秒数（0-25）
     * @return 投递结果
     */
    public JsonObject send(String owner, String threadId, String text, int waitSeconds) {
        return send(owner, threadId, text, null, waitSeconds);
    }

    /**
     * 向线程投递一条任务（可带图片）。
     *
     * @param owner       归属键
     * @param threadId    线程 id，可为空
     * @param text        任务正文
     * @param imageIds    对象存储图片 id（与其它工具一致），最多 4 个；会解析成 Codex 可访问的 URL
     * @param waitSeconds 可选等待秒数（0-25）
     * @return 投递结果
     */
    public JsonObject send(String owner, String threadId, String text, List<String> imageIds, int waitSeconds) {
        if ((text == null || text.trim().isEmpty()) && (imageIds == null || imageIds.isEmpty())) {
            return error("缺少任务内容 text");
        }
        try {
            CodexThreadState state = resolveState(owner, threadId);
            synchronized (state) {
                if (state.running) {
                    JsonObject busy = summary(state, false);
                    busy.addProperty("ok", false);
                    busy.addProperty("busy", true);
                    busy.addProperty("error", "该会话正在执行上一轮任务；可用 codex_latest 继续读输出，或 codex_interrupt 中断");
                    return busy;
                }
            }
            long before = state.getCursor();
            JsonObject params = new JsonObject();
            params.addProperty("threadId", state.threadId);
            JsonArray input = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text == null ? "" : text);
            input.add(block);
            int attached = 0;
            if (imageIds != null) {
                for (String imageId : imageIds) {
                    if (attached >= MAX_IMAGES_PER_TURN) {
                        break;
                    }
                    String url = resolveImageUrl(imageId);
                    if (url == null) {
                        log("跳过无法解析的图片 id：" + shorten(imageId, 120));
                        continue;
                    }
                    JsonObject image = new JsonObject();
                    image.addProperty("type", "image");
                    image.addProperty("url", url);
                    input.add(image);
                    attached++;
                }
            }
            params.add("input", input);
            params.addProperty("approvalPolicy", config.approvalPolicy);
            params.addProperty("approvalsReviewer", config.approvalsReviewer);
            params.add("sandboxPolicy", sandboxPolicy(state));
            if (!config.effort.isEmpty()) {
                params.addProperty("effort", config.effort);
            }
            JsonObject result = process.request("turn/start", params,
                    Math.max(config.requestTimeoutMs, 120000L));
            JsonObject turn = result.has("turn") && result.get("turn").isJsonObject()
                    ? result.getAsJsonObject("turn") : new JsonObject();
            String turnId = JsonConfig.getString(turn, "id");
            if (turnId == null || turnId.isEmpty()) {
                // 响应缺 turn.id 时保留 turn/started 通知里已经记录的 id
                turnId = state.activeTurnId == null ? "" : state.activeTurnId;
            }
            state.beginTurn(turnId);
            logTurnStart(state, turnId);
            if (!turnId.isEmpty()) {
                scheduleTurnTimeout(state, turnId);
            }
            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("thread_id", state.threadId);
            json.addProperty("turn_id", turnId == null ? "" : turnId);
            json.addProperty("accepted", true);
            json.addProperty("cursor", state.getCursor());
            if (attached > 0) {
                json.addProperty("images_attached", attached);
            }
            json.addProperty("hint", "任务已投递；用 codex_latest 传 thread_id 与 since(cursor) 读取输出，"
                    + "通常先 wait_seconds=10~20 再读。");
            int wait = clampWait(waitSeconds);
            if (wait > 0) {
                awaitChange(state, before, wait * 1000L);
                json.add("latest", latestOf(state, before, true, config.maxItems));
            }
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * 把对象存储图片 id 解析成 Codex 可访问的地址。
     * <p>
     * 优先公网 URL；本地存储只能给 data URL 时也接受（Codex 的图片输入支持内联 URL）。
     * 已经是 http(s)/data URL 的输入原样返回。
     * </p>
     */
    private String resolveImageUrl(String imageId) {
        if (imageId == null || imageId.trim().isEmpty()) {
            return null;
        }
        String value = imageId.trim();
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
            return value;
        }
        ObjectStorageProvider current = storage;
        if (current == null) {
            return null;
        }
        try {
            String url = current.getPreferredUrl(value, null);
            return normalizeImageUrl(url);
        } catch (Throwable t) {
            log("解析图片地址失败：" + value + "：" + t.getMessage());
            return null;
        }
    }

    /**
     * 把存储层返回的 data URL 修正成真正的图片 MIME。
     * <p>
     * 本地存储的 {@code getDataUrl} 往往给 {@code data:application/octet-stream;base64,...}，
     * 部分模型接口不接受这种类型；这里按文件头嗅探成 image/png、image/jpeg 等。
     * </p>
     */
    private static String normalizeImageUrl(String url) {
        if (url == null || !url.startsWith("data:")) {
            return url;
        }
        int comma = url.indexOf(',');
        if (comma < 0) {
            return url;
        }
        String meta = url.substring(5, comma);
        if (meta.startsWith("image/")) {
            return url;
        }
        byte[] bytes = decodeDataUrl(url);
        if (bytes == null) {
            return url;
        }
        String mime = sniffImageMime(bytes);
        if (mime == null) {
            return url;
        }
        return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** 按文件头嗅探常见图片 MIME。 */
    private static String sniffImageMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * 增量读取线程输出。
     *
     * @param owner            归属键
     * @param threadId         线程 id，可为空
     * @param since            上次的 cursor，可为 null
     * @param includeReasoning 是否包含推理摘要
     * @param maxItems         最多返回多少条
     * @param waitSeconds      等待新内容的秒数（0-25）
     * @param onlyIfChanged    没有新内容时是否只返回「未变更」
     * @return 输出 JSON
     */
    public JsonObject latest(String owner, String threadId, Long since, boolean includeReasoning,
            int maxItems, int waitSeconds, boolean onlyIfChanged) {
        try {
            CodexThreadState state = resolveState(owner, threadId);
            long before = since == null ? -1L : since.longValue();
            int wait = clampWait(waitSeconds);
            if (wait > 0) {
                awaitChange(state, Math.max(before, 0L), wait * 1000L);
            }
            JsonObject json = latestOf(state, before, includeReasoning, maxItems);
            boolean unchanged = since != null && JsonConfig.getLong(json, "cursor") <= since.longValue()
                    && state.pendingCount() == 0;
            if (unchanged && onlyIfChanged) {
                json.addProperty("unchanged", true);
                json.addProperty("hint", "没有新内容；可稍后再查，或用 wait_seconds 长轮询。");
            }
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * 列出待处理交互（审批/提问）。
     *
     * @param owner    归属键
     * @param threadId 线程 id，可为空（空表示该归属键下全部）
     * @return 待处理 JSON
     */
    public JsonObject pending(String owner, String threadId) {
        try {
            CodexThreadState state = resolveState(owner, threadId);
            JsonArray array = state.pendingArray();
            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("count", array.size());
            json.add("tasks", array);
            json.addProperty("hint", array.size() == 0
                    ? "当前没有待审批/待回答的交互。"
                    : "审批用 codex_feedback 的 decision=allow/allow_session/deny/cancel；提问用 text 回答。");
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * 答复一条待处理交互。
     *
     * @param owner    归属键
     * @param taskId   任务 id
     * @param decision 审批决定，可为空
     * @param text     提问的自由文本答案，或拒绝原因
     * @param selected 提问选项，多个用 {@code |} 分隔
     * @param answers  完整答案 JSON（可选）
     * @return 结果 JSON
     */
    public JsonObject feedback(String owner, String taskId, String decision, String text, String selected,
            String answers) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return error("缺少 task_id");
        }
        CodexThreadState.Pending pending;
        synchronized (this) {
            pending = pendingByTask.get(taskId.trim());
        }
        if (pending == null) {
            return error("任务不存在、已超时或已被处理：" + taskId);
        }
        CodexThreadState state = threadOf(pending.threadId);
        if (state == null || (owner != null && !owner.equals(state.owner))) {
            return error("任务不属于当前会话");
        }
        if ("approval".equals(pending.kind)) {
            String parsed = parseDecision(decision != null ? decision : text);
            if (parsed == null) {
                return error("无法识别的审批决定：" + decision + "（可用 allow/allow_session/deny/cancel）");
            }
            respondApproval(state, pending, parsed, "agent");
            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("task_id", pending.taskId);
            json.addProperty("kind", pending.kind);
            json.addProperty("decision", parsed);
            json.addProperty("accepted", true);
            return json;
        }
        JsonObject payload = buildAnswers(pending, text, selected, answers);
        process.respond(pending.rpcId, payload);
        finishPending(state, pending, "answered", "agent");
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("task_id", pending.taskId);
        json.addProperty("kind", pending.kind);
        json.addProperty("answered", true);
        return json;
    }

    /**
     * 处理管理员在私聊里的审批答复。
     * <p>
     * 多机器人部署时同一个管理员可能同时有多个群的待审批：用收到私聊的机器人账号过滤，
     * 只回答「这个机器人名下」的请求，避免批错群。账号统一用字符串（QQ 号 / openid）。
     * </p>
     *
     * @param botId   收到私聊的机器人账号 id（事件里的 self_id），空串表示不限制
     * @param adminId 发消息的账号 id
     * @param text    消息正文
     * @return true 表示这条消息已经被当作审批答复消费掉
     */
    public boolean answerApproval(String botId, String adminId, String text) {
        return answerApproval(botId, adminId, text, null);
    }

    /**
     * 处理指定任务的审批答复（按钮回调用）。
     *
     * @param botId   收到互动的机器人账号 id
     * @param adminId 操作者账号 id
     * @param text    决定文本（1/2/3/4）
     * @param taskId  任务 id；为空时退回“该管理员最早的一条”
     * @return true 表示已命中并处理
     */
    public boolean answerApproval(String botId, String adminId, String text, String taskId) {
        String decision = parseDecision(text);
        String sender = adminId == null ? "" : adminId.trim();
        if (decision == null || sender.isEmpty()) {
            return false;
        }
        String bot = botId == null ? "" : botId.trim();
        CodexThreadState.Pending target = null;
        synchronized (this) {
            if (taskId != null && !taskId.trim().isEmpty()) {
                CodexThreadState.Pending item = pendingByTask.get(taskId.trim());
                if (item != null && "approval".equals(item.kind) && item.adminIds.contains(sender)
                        && matchesBot(item, bot)) {
                    target = item;
                }
            } else {
                for (CodexThreadState.Pending item : pendingByTask.values()) {
                    if (!"approval".equals(item.kind) || !item.adminIds.contains(sender)
                            || !matchesBot(item, bot)) {
                        continue;
                    }
                    target = item;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        CodexThreadState state = threadOf(target.threadId);
        if (state == null) {
            return false;
        }
        respondApproval(state, target, decision, "qq");
        return true;
    }

    private boolean matchesBot(CodexThreadState.Pending item, String bot) {
        if (bot.isEmpty()) {
            return true;
        }
        CodexThreadState owner = threads.get(item.threadId);
        return owner != null && owner.owner.startsWith("qq:" + bot + ":");
    }

    /** 兼容旧调用：数字账号转字符串。 */
    public boolean answerApproval(long botId, long adminId, String text) {
        return answerApproval(botId > 0 ? String.valueOf(botId) : "",
                adminId > 0 ? String.valueOf(adminId) : "", text);
    }

    /**
     * 中断当前轮次。
     *
     * @param owner    归属键
     * @param threadId 线程 id，可为空
     * @return 结果 JSON
     */
    public JsonObject interrupt(String owner, String threadId) {
        try {
            CodexThreadState state = resolveState(owner, threadId);
            String turnId;
            synchronized (state) {
                turnId = state.activeTurnId;
                if (!state.running || turnId == null || turnId.isEmpty()) {
                    JsonObject json = new JsonObject();
                    json.addProperty("ok", true);
                    json.addProperty("interrupted", false);
                    json.addProperty("hint", "当前没有正在执行的轮次。");
                    return json;
                }
            }
            JsonObject params = new JsonObject();
            params.addProperty("threadId", state.threadId);
            params.addProperty("turnId", turnId);
            process.request("turn/interrupt", params, config.requestTimeoutMs);
            state.endTurn();
            state.addEvent("notice").addProperty("text", "已请求中断当前轮次。");
            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("interrupted", true);
            json.addProperty("thread_id", state.threadId);
            json.addProperty("turn_id", turnId);
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * 等待某一轮结束（异步工具用）。
     * <p>
     * Codex 的事件是推过来的，工具不需要轮询：这里返回的 future 在
     * {@code turn/completed} 时完成，载荷见 {@link CodexThreadState#completionPayload()}。
     * 超时只完成 future、不中断任务（单轮超时由 {@code scheduleTurnTimeout} 负责）。
     * </p>
     *
     * @param threadId  线程 id
     * @param turnId    轮次 id，可为空（为空时接受该线程最近一次结束的轮次）
     * @param timeoutMs 最长等待毫秒
     * @return 完成时给出载荷；线程不存在时立即返回错误载荷
     */
    public CompletableFuture<JsonObject> awaitTurn(String threadId, String turnId, long timeoutMs) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        CodexThreadState state = threadOf(threadId);
        if (state == null) {
            future.complete(error("未知线程：" + threadId));
            return future;
        }
        synchronized (state) {
            boolean finished = !state.running
                    && (turnId == null || turnId.isEmpty() || turnId.equals(state.lastTurnId));
            if (finished) {
                future.complete(state.completionPayload());
                return future;
            }
            CompletableFuture<JsonObject> previous = turnWaiters.put(threadId, future);
            if (previous != null && previous != future) {
                // 同一线程只保留一个等待者：旧的直接按当前状态完成，避免悬挂
                previous.complete(state.completionPayload());
            }
        }
        long safeTimeout = Math.max(30000L, timeoutMs);
        java.util.concurrent.ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (turnWaiters.remove(threadId, future)) {
                JsonObject payload = state.completionPayload();
                payload.addProperty("status", "timeout");
                payload.addProperty("still_running", state.running);
                future.complete(payload);
            }
        }, safeTimeout, TimeUnit.MILLISECONDS);
        future.whenComplete((value, failure) -> timer.cancel(false));
        return future;
    }

    /** 完成并移除某线程的等待者。 */
    private void completeTurnWaiter(CodexThreadState state) {
        CompletableFuture<JsonObject> waiter = turnWaiters.remove(state.threadId);
        if (waiter != null) {
            waiter.complete(state.completionPayload());
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        process.close();
    }

    // ==================== 通知处理 ====================

    private void handleNotification(String method, JsonObject params) {
        String threadId = JsonConfig.getString(params, "threadId");
        CodexThreadState state = threadId == null ? null : threadOf(threadId);
        switch (method) {
            case "thread/started": {
                if (state == null && params.has("thread") && params.get("thread").isJsonObject()) {
                    JsonObject thread = params.getAsJsonObject("thread");
                    String id = JsonConfig.getString(thread, "id");
                    if (id != null) {
                        state = threadOf(id);
                        if (state != null) {
                            state.model = JsonConfig.getString(thread, "model");
                        }
                    }
                }
                return;
            }
            case "turn/started": {
                if (state == null) {
                    return;
                }
                JsonObject turn = params.has("turn") && params.get("turn").isJsonObject()
                        ? params.getAsJsonObject("turn") : new JsonObject();
                state.beginTurn(JsonConfig.getString(turn, "id"));
                logTurnStart(state, JsonConfig.getString(turn, "id"));
                return;
            }
            case "item/started":
            case "item/completed": {
                if (state == null) {
                    return;
                }
                JsonObject item = params.has("item") && params.get("item").isJsonObject()
                        ? params.getAsJsonObject("item") : new JsonObject();
                handleItem(state, item, "item/started".equals(method), JsonConfig.getString(params, "turnId"));
                return;
            }
            case "item/agentMessage/delta": {
                if (state != null) {
                    state.appendText("assistant", JsonConfig.getString(params, "delta"), config.maxEventChars);
                }
                return;
            }
            case "item/reasoning/summaryTextDelta": {
                if (state != null) {
                    state.appendText("reasoning", JsonConfig.getString(params, "delta"), config.maxEventChars);
                }
                return;
            }
            case "turn/completed": {
                if (state == null) {
                    return;
                }
                JsonObject turn = params.has("turn") && params.get("turn").isJsonObject()
                        ? params.getAsJsonObject("turn") : new JsonObject();
                String status = JsonConfig.getString(turn, "status");
                String turnId = JsonConfig.getString(turn, "id");
                String errorMessage = "";
                JsonElement error = turn.get("error");
                if (error != null && !error.isJsonNull()) {
                    JsonObject errorObject = error.isJsonObject() ? error.getAsJsonObject() : new JsonObject();
                    String message = JsonConfig.getString(errorObject, "message");
                    errorMessage = message == null ? error.toString() : message;
                    state.lastError = errorMessage;
                }
                logTurnEnd(state, turnId, status, errorMessage);
                state.finishTurn(turnId, status, errorMessage);
                JsonObject event = state.addEvent("turn");
                event.addProperty("status", status == null ? "completed" : status);
                if (!errorMessage.isEmpty()) {
                    event.addProperty("error", errorMessage);
                }
                completeTurnWaiter(state);
                return;
            }
            case "error": {
                if (state == null) {
                    return;
                }
                JsonObject error = params.has("error") && params.get("error").isJsonObject()
                        ? params.getAsJsonObject("error") : new JsonObject();
                String message = JsonConfig.getString(error, "message");
                boolean retry = JsonConfig.getBool(params, "willRetry");
                state.lastError = message == null ? "未知错误" : message;
                JsonObject event = state.addEvent("error");
                event.addProperty("message", state.lastError);
                event.addProperty("retry", retry);
                String details = JsonConfig.getString(error, "additionalDetails");
                if (details != null) {
                    event.addProperty("detail", shorten(details, config.maxEventChars));
                }
                return;
            }
            case "thread/status/changed": {
                if (state == null) {
                    return;
                }
                JsonObject status = params.has("status") && params.get("status").isJsonObject()
                        ? params.getAsJsonObject("status") : new JsonObject();
                state.status = JsonConfig.getString(status, "type");
                JsonArray flags = status.has("activeFlags") && status.get("activeFlags").isJsonArray()
                        ? status.getAsJsonArray("activeFlags") : new JsonArray();
                boolean waiting = false;
                for (JsonElement flag : flags) {
                    if (flag != null && !flag.isJsonNull() && "waitingOnApproval".equals(flag.getAsString())) {
                        waiting = true;
                    }
                }
                if (waiting) {
                    state.addEvent("notice").addProperty("text", "Codex 正在等待审批。");
                }
                return;
            }
            case "thread/tokenUsage/updated": {
                if (state != null && params.has("tokenUsage") && params.get("tokenUsage").isJsonObject()) {
                    state.usage = params.getAsJsonObject("tokenUsage");
                }
                return;
            }
            case "serverRequest/resolved": {
                if (state != null) {
                    String requestId = JsonConfig.getString(params, "requestId");
                    CodexThreadState.Pending target = null;
                    synchronized (this) {
                        for (CodexThreadState.Pending item : pendingByTask.values()) {
                            if (requestId != null && requestId.equals(JsonConfig.getString(item.rpcId))) {
                                target = item;
                                break;
                            }
                        }
                    }
                    if (target != null) {
                        finishPending(state, target, "resolved", "server");
                    }
                }
                return;
            }
            case "warning":
            case "configWarning":
            case "deprecationNotice":
            case "guardianWarning":
                log(shorten(JsonConfig.getString(params, "message"), 300));
                return;
            default:
                // 其余通知（mcpServer 状态、token 用量等）不进入事件流，避免噪音
                return;
        }
    }

    private void handleItem(CodexThreadState state, JsonObject item, boolean started, String turnId) {
        String type = JsonConfig.getString(item, "type");
        if (type == null) {
            return;
        }
        String itemId = JsonConfig.getString(item, "id");
        switch (type) {
            case "agentMessage": {
                if (!started) {
                    String text = JsonConfig.getString(item, "text");
                    if (text != null && !text.isEmpty()) {
                        state.replaceText("assistant", text, config.maxEventChars);
                        // 只负责把 markdown 图片上传成附件；是否提醒 Codex 用 markdown 由 AI 判断
                        scanMarkdownImages(state, text);
                    }
                }
                return;
            }
            case "reasoning": {
                if (!started) {
                    JsonArray summary = item.has("summary") && item.get("summary").isJsonArray()
                            ? item.getAsJsonArray("summary") : new JsonArray();
                    StringBuilder joined = new StringBuilder();
                    for (JsonElement part : summary) {
                        if (part != null && !part.isJsonNull()) {
                            if (joined.length() > 0) {
                                joined.append('\n');
                            }
                            joined.append(part.getAsString());
                        }
                    }
                    if (joined.length() > 0) {
                        state.replaceText("reasoning", joined.toString(), config.maxEventChars);
                    }
                }
                return;
            }
            case "commandExecution": {
                String command = JsonConfig.getString(item, "command");
                String status = JsonConfig.getString(item, "status");
                String summary = shorten(command == null ? "" : command.replace('\n', ' '), 160);
                if (!started) {
                    Integer exitCode = JsonConfig.getIntOrNull(item.get("exitCode"));
                    if (exitCode != null) {
                        summary = summary + "（exit " + exitCode + "）";
                    }
                }
                state.addTool("command", summary, status == null ? (started ? "inProgress" : "completed") : status,
                        itemId, turnId);
                return;
            }
            case "fileChange": {
                JsonArray changes = item.has("changes") && item.get("changes").isJsonArray()
                        ? item.getAsJsonArray("changes") : new JsonArray();
                List<String> paths = new ArrayList<>();
                for (JsonElement change : changes) {
                    if (change != null && change.isJsonObject()) {
                        String path = JsonConfig.getString(change.getAsJsonObject(), "path");
                        if (path != null) {
                            paths.add(path);
                        }
                    }
                }
                String summary = paths.isEmpty() ? "修改文件" : join(paths, 3);
                state.addTool("file", summary, JsonConfig.getString(item, "status"), itemId, turnId);
                return;
            }
            case "mcpToolCall": {
                String server = JsonConfig.getString(item, "server");
                String tool = JsonConfig.getString(item, "tool");
                state.addTool("mcp", (server == null ? "" : server + "/") + (tool == null ? "" : tool),
                        JsonConfig.getString(item, "status"), itemId, turnId);
                if (!started) {
                    collectMcpImages(state, item, itemId);
                }
                return;
            }
            case "webSearch": {
                state.addTool("web", JsonConfig.getString(item, "query"), started ? "inProgress" : "completed", itemId, turnId);
                return;
            }
            case "plan": {
                if (!started) {
                    String text = JsonConfig.getString(item, "text");
                    state.addTool("plan", shorten(text == null ? "计划已更新" : text.replace('\n', ' '), 200),
                            "completed", itemId, turnId);
                }
                return;
            }
            case "contextCompaction": {
                state.addTool("compact", "上下文已压缩", "completed", itemId, turnId);
                return;
            }
            case "imageView": {
                if (!started) {
                    handleImage(state, "view", JsonConfig.getString(item, "path"), null, null, itemId, false);
                }
                return;
            }
            case "imageGeneration": {
                if (!started) {
                    String status = JsonConfig.getString(item, "status");
                    String saved = JsonConfig.getString(item, "savedPath");
                    if (saved != null && (status == null || "completed".equalsIgnoreCase(status))) {
                        handleImage(state, "generation", saved, null, null, itemId, false);
                    }
                }
                return;
            }
            case "enteredReviewMode":
            case "exitedReviewMode": {
                state.addTool("review", JsonConfig.getString(item, "review"), "completed", itemId, turnId);
                return;
            }
            case "userMessage": {
                if (!started && config.includeUserMessage) {
                    JsonArray content = item.has("content") && item.get("content").isJsonArray()
                            ? item.getAsJsonArray("content") : new JsonArray();
                    StringBuilder text = new StringBuilder();
                    for (JsonElement part : content) {
                        if (part != null && part.isJsonObject()) {
                            String value = JsonConfig.getString(part.getAsJsonObject(), "text");
                            if (value != null) {
                                text.append(value);
                            }
                        }
                    }
                    if (text.length() > 0) {
                        state.appendText("user", text.toString(), config.maxEventChars);
                    }
                }
                return;
            }
            default:
                return;
        }
    }

    // ==================== 服务端请求（审批/提问） ====================

    private void handleServerRequest(JsonElement rpcId, String method, JsonObject params) {
        try {
            if ("item/commandExecution/requestApproval".equals(method)
                    || "item/fileChange/requestApproval".equals(method)) {
                handleApproval(rpcId, method, params);
                return;
            }
            if ("item/tool/requestUserInput".equals(method)) {
                handleQuestion(rpcId, params);
                return;
            }
            process.respondError(rpcId, -32601, "本程序未实现的服务端请求：" + method);
        } catch (Throwable t) {
            log("处理服务端请求失败：" + t);
            process.respondError(rpcId, -32603, "本程序内部错误：" + t.getMessage());
        }
    }

    private void handleApproval(JsonElement rpcId, String method, JsonObject params) {
        boolean command = method.startsWith("item/commandExecution");
        String threadId = JsonConfig.getString(params, "threadId");
        String turnId = JsonConfig.getString(params, "turnId");
        String itemId = JsonConfig.getString(params, "itemId");
        CodexThreadState state = threadId == null ? null : threadOf(threadId);
        String title;
        String detail;
        String kind = command ? "command" : "file";
        if (command) {
            String rawCommand = JsonConfig.getString(params, "command");
            title = rawCommand == null ? "执行命令（未提供命令行）" : shorten(rawCommand.replace('\n', ' '), 300);
            StringBuilder builder = new StringBuilder();
            String cwd = JsonConfig.getString(params, "cwd");
            if (cwd != null) {
                builder.append("目录：").append(cwd);
            }
            String reason = JsonConfig.getString(params, "reason");
            if (reason != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("原因：").append(reason);
            }
            JsonObject network = params.has("networkApprovalContext") && params.get("networkApprovalContext").isJsonObject()
                    ? params.getAsJsonObject("networkApprovalContext") : null;
            if (network != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("网络：").append(JsonConfig.getString(network, "protocol")).append(" ")
                        .append(JsonConfig.getString(network, "host"));
            }
            detail = builder.toString();
        } else {
            JsonArray changes = params.has("changes") && params.get("changes").isJsonArray()
                    ? params.getAsJsonArray("changes") : new JsonArray();
            List<String> paths = new ArrayList<>();
            for (JsonElement change : changes) {
                if (change != null && change.isJsonObject()) {
                    String path = JsonConfig.getString(change.getAsJsonObject(), "path");
                    if (path != null) {
                        paths.add(path);
                    }
                }
            }
            title = paths.isEmpty() ? "修改文件" : join(paths, 5);
            String root = JsonConfig.getString(params, "grantRoot");
            String reason = JsonConfig.getString(params, "reason");
            detail = (root == null ? "" : "申请写入：" + root + "\n")
                    + (reason == null ? "" : "原因：" + reason);
        }
        List<String> adminIds = adminOf(state);
        String mode = config.approval;
        if ("allow".equals(mode)) {
            respondApprovalDirect(rpcId, "accept", state, kind, title, "auto-allow");
            return;
        }
        if ("deny".equals(mode) || "off".equals(mode)) {
            respondApprovalDirect(rpcId, "decline", state, kind, title, "auto-" + mode);
            return;
        }
        if (config.aiReview) {
            // 先交给当前对话的 AI 预审：合理就自动放行/拒绝，只有不确定或危险才转人工
            scheduleAiReview(state, rpcId, kind, title, detail, threadId, turnId, adminIds);
            return;
        }
        escalateToAdmin(state, rpcId, kind, title, detail, threadId, turnId, adminIds, "");
    }

    /**
     * 把审批请求交给当前对话的 AI 预审。
     * <p>
     * 不直接问模型：把请求登记成 review 任务，通过 {@link CodexReviewHandler} 唤醒对话 agent；
     * agent 用 {@code codex_review_approval} 回答 approve / reject / ask_user。
     * 超时或没有对话 agent 时转人工。
     * </p>
     */
    private void scheduleAiReview(final CodexThreadState state, final JsonElement rpcId, final String kind,
            final String title, final String detail, final String threadId, final String turnId,
            final List<String> adminIds) {
        String taskId = "codex-review-" + taskSeq.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        CodexThreadState.Pending pending = new CodexThreadState.Pending(taskId, "review", kind, rpcId,
                threadId, turnId, title, detail, adminIds, new JsonArray(), null);
        registerPending(state, pending);
        final CodexThreadState.Pending target = pending;
        CodexReviewHandler handler = reviewHandler;
        if (handler == null) {
            log("没有可用的对话 AI 预审，转人工：" + shorten(title, 120));
            dropReviewPending(state, target, "no-ai");
            escalateToAdmin(state, rpcId, kind, title, detail, threadId, turnId, adminIds, "no-ai");
            return;
        }
        handler.onReview(new CodexReviewHandler.Notice(taskId, state == null ? "" : state.owner, threadId,
                kind, title, detail, firstAdmin(adminIds)));
        scheduler.schedule(() -> {
            if (!target.answered) {
                log("对话 AI 预审超时，转人工：" + shorten(title, 120));
                dropReviewPending(state, target, "ai-timeout");
                escalateToAdmin(state, rpcId, kind, title, detail, threadId, turnId, adminIds, "ai-timeout");
            }
        }, config.reviewTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 对话 AI 答复预审结果。
     *
     * @param owner    归属键（必须与线程一致）
     * @param taskId   预审任务 id
     * @param decision approve / reject / ask_user
     * @param reason   理由
     * @return 结果 JSON
     */
    public JsonObject reviewApproval(String owner, String taskId, String decision, String reason) {
        return reviewApproval(owner, taskId, decision, reason, "");
    }

    /** 兼容旧调用：数字账号转字符串；0 表示不指定。 */
    public JsonObject reviewApproval(String owner, String taskId, String decision, String reason, long reviewer) {
        return reviewApproval(owner, taskId, decision, reason,
                reviewer > 0 ? String.valueOf(reviewer) : "");
    }

    /**
     * 对话 AI 答复预审结果，并可为转人工指定建议审核人。
     * <p>
     * {@code reviewer} 只在 {@code decision=ask_user}、agent 开启
     * {@code codex_open_approval} 且传入非空账号时生效；否则一律交给管理员。
     * </p>
     *
     * @param owner    归属键（必须与线程一致）
     * @param taskId   预审任务 id
     * @param decision approve / reject / ask_user
     * @param reason   理由
     * @param reviewer 建议审核人账号（QQ 号 / openid），空串表示不指定
     * @return 结果 JSON
     */
    public JsonObject reviewApproval(String owner, String taskId, String decision, String reason, String reviewer) {
        String reviewerId = reviewer == null ? "" : reviewer.trim();
        while (reviewerId.startsWith("@")) {
            reviewerId = reviewerId.substring(1).trim();
        }
        CodexThreadState.Pending pending;
        synchronized (this) {
            pending = pendingByTask.get(taskId);
        }
        if (pending == null || !"review".equals(pending.kind)) {
            return error("预审任务不存在、已处理或已超时：" + taskId);
        }
        CodexThreadState state = threadOf(pending.threadId);
        if (state == null || (owner != null && !owner.equals(state.owner))) {
            return error("预审任务不属于当前会话");
        }
        String normalized = normalizeReviewDecision(decision);
        String note = "ai:" + shorten(reason == null ? "" : reason, 120);
        if ("approve".equals(normalized)) {
            respondApproval(state, pending, "accept", note);
            log("对话 AI 预审放行：" + shorten(pending.title, 120));
            return reviewResult(true, pending.taskId, "accept", reason);
        }
        if ("reject".equals(normalized)) {
            respondApproval(state, pending, "decline", note);
            log("对话 AI 预审拒绝：" + shorten(pending.title, 120));
            return reviewResult(true, pending.taskId, "decline", reason);
        }
        List<String> targetAdmins = pending.adminIds;
        if (isOpenApproval(state.owner) && !reviewerId.isEmpty()) {
            targetAdmins = java.util.Collections.singletonList(reviewerId);
            note = note + "；建议审核人：" + reviewerId;
            log("对话 AI 指定建议审核人：" + reviewerId + "（" + shorten(pending.title, 80) + "）");
        } else if (!reviewerId.isEmpty()) {
            log("忽略建议审核人 " + reviewerId + "：agent 未开启开放审核权限");
        }
        String kind = pending.requestKind == null ? "command" : pending.requestKind;
        dropReviewPending(state, pending, "escalated");
        escalateToAdmin(state, pending.rpcId, kind, pending.title, pending.detail, pending.threadId,
                pending.turnId, targetAdmins, note);
        log("对话 AI 将审批转人工：" + shorten(pending.title, 120));
        return reviewResult(true, pending.taskId, "ask_user", reason);
    }

    /** 转人工兜底：对话 AI 不可用或通知失败时由连接器调用。 */
    public void escalateReview(String taskId, String reason) {
        CodexThreadState.Pending pending;
        synchronized (this) {
            pending = pendingByTask.get(taskId);
        }
        if (pending == null || !"review".equals(pending.kind)) {
            return;
        }
        CodexThreadState state = threadOf(pending.threadId);
        String kind = pending.requestKind == null ? "command" : pending.requestKind;
        dropReviewPending(state, pending, "no-ai:" + reason);
        escalateToAdmin(state, pending.rpcId, kind, pending.title, pending.detail, pending.threadId,
                pending.turnId, pending.adminIds, "ai:" + (reason == null ? "" : reason));
    }

    private static JsonObject reviewResult(boolean ok, String taskId, String decision, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", ok);
        json.addProperty("task_id", taskId);
        json.addProperty("decision", decision);
        if (reason != null && !reason.isEmpty()) {
            json.addProperty("reason", reason);
        }
        return json;
    }

    /** 预审结束但未回包（超时/转人工）：从待办里移除并记一条事件。 */
    private void dropReviewPending(CodexThreadState state, CodexThreadState.Pending pending, String note) {
        synchronized (pending) {
            if (pending.answered) {
                return;
            }
            pending.answered = true;
        }
        synchronized (this) {
            pendingByTask.remove(pending.taskId);
        }
        if (state != null) {
            state.removePending(pending.taskId);
            JsonObject event = state.addEvent("approval");
            event.addProperty("task_id", pending.taskId);
            event.addProperty("kind", "review");
            event.addProperty("status", note);
            event.addProperty("title", pending.title);
        }
    }

    private static String firstAdmin(List<String> adminIds) {
        if (adminIds != null) {
            for (String id : adminIds) {
                if (id != null && !id.isEmpty()) {
                    return id;
                }
            }
        }
        return "";
    }

    /** 规范化预审决定；无法识别按 ask_user 处理。 */
    private static String normalizeReviewDecision(String value) {
        if (value == null) {
            return "ask_user";
        }
        String text = value.trim().toLowerCase();
        if ("approve".equals(text) || "allow".equals(text) || "accept".equals(text) || "同意".equals(text)
                || "允许".equals(text)) {
            return "approve";
        }
        if ("reject".equals(text) || "deny".equals(text) || "decline".equals(text) || "拒绝".equals(text)) {
            return "reject";
        }
        return "ask_user";
    }

    /**
     * 通过 app-server 的 {@code project/create} 自动创建 Codex 项目。
     * <p>
     * 需要 {@code experimentalApi}（配置 {@code experimental=true}，有 project/projectId 时默认开启）；
     * 失败只记日志并退回“不挂项目”，不影响线程用 cwd 正常工作。
     * </p>
     */
    private String createProject(String name, File cwd) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.addProperty("idempotencyKey", UUID.randomUUID().toString());
            JsonArray roots = new JsonArray();
            JsonObject root = new JsonObject();
            root.addProperty("path", cwd.getAbsolutePath());
            roots.add(root);
            params.add("roots", roots);
            JsonObject result = process.request("project/create", params,
                    Math.max(config.requestTimeoutMs, 30000L));
            JsonObject project = result != null && result.has("project") && result.get("project").isJsonObject()
                    ? result.getAsJsonObject("project") : null;
            String id = project == null ? null : JsonConfig.getString(project, "id");
            if (id != null && !id.isEmpty()) {
                log("已自动创建 Codex 项目「" + name + "」：" + id + "（" + cwd.getPath() + "）");
                return id;
            }
            log("自动创建 Codex 项目「" + name + "」没有返回 id");
        } catch (Throwable t) {
            log("自动创建 Codex 项目「" + name + "」失败（将不挂项目）：" + t.getMessage());
        }
        return null;
    }

    /** 转人工：优先 QQ 私聊管理员；没有管理员/审批桥时失败关闭。 */
    private void escalateToAdmin(final CodexThreadState state, final JsonElement rpcId, final String kind,
            final String title, final String detail, final String threadId, final String turnId,
            final List<String> adminIds, final String note) {
        if (bridge == null || adminIds == null || adminIds.isEmpty()) {
            log("需要人工审批但没有可用的管理员/审批桥，按拒绝处理：" + shorten(title, 120));
            respondApprovalDirect(rpcId, "decline", state, kind, title, "no-admin");
            return;
        }
        String taskId = "codex-" + taskSeq.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        JsonArray options = new JsonArray();
        for (String option : Arrays.asList("accept", "acceptForSession", "decline", "cancel")) {
            options.add(option);
        }
        String questionDetail = detail == null ? "" : detail;
        if (note != null && !note.isEmpty()) {
            questionDetail = questionDetail + (questionDetail.isEmpty() ? "" : "\n") + "预审：" + note;
        }
        CodexThreadState.Pending pending = new CodexThreadState.Pending(taskId, "approval", rpcId,
                threadId, turnId, title, questionDetail, adminIds, options, null);
        registerPending(state, pending);
        final CodexThreadState.Pending target = pending;
        final CodexApprovalBridge.Notice notice = new CodexApprovalBridge.Notice(taskId, threadId, turnId,
                kind, title, questionDetail, adminIds, Arrays.asList("accept", "acceptForSession", "decline", "cancel"));
        scheduler.execute(() -> {
            try {
                bridge.onAsked(notice);
            } catch (Throwable t) {
                log("审批提问发送失败，按拒绝处理：" + t);
                if (state != null) {
                    respondApproval(state, target, "decline", "notify-failed");
                }
            }
        });
        scheduler.schedule(() -> {
            if (!target.answered && state != null) {
                respondApproval(state, target, "decline", "timeout");
            }
        }, config.approvalTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void handleQuestion(JsonElement rpcId, JsonObject params) {
        String threadId = JsonConfig.getString(params, "threadId");
        String turnId = JsonConfig.getString(params, "turnId");
        CodexThreadState state = threadId == null ? null : threadOf(threadId);
        JsonArray questions = params.has("questions") && params.get("questions").isJsonArray()
                ? params.getAsJsonArray("questions") : new JsonArray();
        String title = "Codex 需要补充信息";
        if (questions.size() > 0 && questions.get(0).isJsonObject()) {
            String question = JsonConfig.getString(questions.get(0).getAsJsonObject(), "question");
            if (question != null) {
                title = shorten(question, 300);
            }
        }
        String taskId = "codex-q-" + taskSeq.incrementAndGet();
        CodexThreadState.Pending pending = new CodexThreadState.Pending(taskId, "question", rpcId,
                threadId, turnId, title, "", "", null, questions);
        registerPending(state, pending);
        final CodexThreadState.Pending target = pending;
        scheduler.schedule(() -> {
            if (!target.answered) {
                JsonObject empty = new JsonObject();
                empty.add("answers", new JsonObject());
                process.respond(target.rpcId, empty);
                if (state != null) {
                    finishPending(state, target, "timeout", "timeout");
                }
            }
        }, config.approvalTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void respondApprovalDirect(JsonElement rpcId, String decision, CodexThreadState state,
            String kind, String title, String note) {
        JsonObject result = new JsonObject();
        result.addProperty("decision", decision);
        process.respond(rpcId, result);
        if (state != null) {
            JsonObject event = state.addEvent("approval");
            event.addProperty("decision", decision);
            event.addProperty("title", title);
            event.addProperty("note", note);
        }
    }

    private void respondApproval(CodexThreadState state, CodexThreadState.Pending pending, String decision,
            String note) {
        synchronized (pending) {
            if (pending.answered) {
                return;
            }
            pending.answered = true;
        }
        JsonObject result = new JsonObject();
        result.addProperty("decision", decision);
        process.respond(pending.rpcId, result);
        finishPending(state, pending, decision, note);
    }

    private void finishPending(CodexThreadState state, CodexThreadState.Pending pending, String decision,
            String note) {
        synchronized (this) {
            pendingByTask.remove(pending.taskId);
        }
        if (state != null) {
            state.removePending(pending.taskId);
            JsonObject event = state.addEvent("approval");
            event.addProperty("task_id", pending.taskId);
            event.addProperty("kind", pending.kind);
            event.addProperty("decision", decision);
            if (note != null) {
                event.addProperty("note", note);
            }
        }
        CodexApprovalBridge current = bridge;
        if (current != null && "approval".equals(pending.kind)) {
            try {
                current.onResolved(new CodexApprovalBridge.Notice(pending.taskId, pending.threadId,
                        pending.turnId, "approval".equals(pending.kind) ? "command" : "question",
                        pending.title, pending.detail, pending.adminIds, new ArrayList<String>()), decision, note);
            } catch (Throwable t) {
                log("审批结果回调失败：" + t);
            }
        }
        CodexReviewHandler handler = reviewHandler;
        if (handler != null && state != null) {
            try {
                handler.onResolved(new CodexReviewHandler.Notice(pending.taskId, state.owner,
                        pending.threadId, pending.kind, pending.title, pending.detail,
                        firstAdmin(pending.adminIds)), decision, note);
            } catch (Throwable t) {
                log("审批结果写入上下文失败：" + t);
            }
        }
    }

    private void registerPending(CodexThreadState state, CodexThreadState.Pending pending) {
        synchronized (this) {
            pendingByTask.put(pending.taskId, pending);
        }
        if (state != null) {
            state.addPending(pending);
            JsonObject event = state.addEvent("approval");
            event.addProperty("task_id", pending.taskId);
            event.addProperty("kind", pending.kind);
            event.addProperty("status", "pending");
            event.addProperty("title", pending.title);
            if (pending.detail != null && !pending.detail.isEmpty()) {
                event.addProperty("detail", shorten(pending.detail, 500));
            }
        }
    }

    // ==================== 线程解析与工具方法 ====================

    private CodexThreadState resolveState(String owner, String threadId) throws IOException {
        ensureStarted();
        CodexThreadState state;
        if (threadId != null && !threadId.trim().isEmpty()) {
            state = threadOf(threadId.trim());
            if (state == null) {
                throw new IOException("未知线程：" + threadId + "（可用 codex_threads 查看）");
            }
            if (owner != null && !owner.equals(state.owner)) {
                throw new IOException("线程不属于当前会话：" + threadId);
            }
        } else {
            String current;
            synchronized (this) {
                current = ownerDefault.get(owner);
            }
            state = current == null ? null : threadOf(current);
            if (state == null) {
                JsonObject created = startThread(owner, null, null);
                String id = JsonConfig.getString(created, "thread_id");
                state = id == null ? null : threadOf(id);
                if (state == null) {
                    throw new IOException("创建线程失败：" + JsonConfig.getString(created, "error"));
                }
                return state;
            }
        }
        if (!state.loaded) {
            try {
                resumeThread(state);
            } catch (IOException ex) {
                log("续聊失败，自动新建线程：" + ex.getMessage());
                JsonObject created = startThread(owner, state.cwd, null);
                String id = JsonConfig.getString(created, "thread_id");
                CodexThreadState replacement = id == null ? null : threadOf(id);
                if (replacement == null) {
                    throw new IOException("续聊失败且新建线程失败：" + ex.getMessage());
                }
                replacement.addEvent("notice").addProperty("text",
                        "原会话已失效（" + ex.getMessage() + "），已自动新建。");
                return replacement;
            }
        }
        return state;
    }

    private void resumeThread(CodexThreadState state) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", state.threadId);
        params.addProperty("cwd", state.cwd);
        params.addProperty("approvalPolicy", config.approvalPolicy);
        params.addProperty("approvalsReviewer", config.approvalsReviewer);
        params.addProperty("sandbox", config.sandbox);
        if (!config.model.isEmpty()) {
            params.addProperty("model", config.model);
        }
        process.request("thread/resume", params, Math.max(config.requestTimeoutMs, 120000L));
        state.loaded = true;
        loadHistory(state);
        log("已续聊线程 " + state.threadId);
    }

    /**
     * 续聊时把 Codex 持久化的最近几轮回放进事件流。
     * <p>
     * 进程重启后事件列表是空的；如果不回放，AI 想了解「之前做到哪」就只能给 Codex 发一条新消息。
     * 这里用 {@code thread/turns/list}（倒序取最近 N 轮、{@code itemsView=full}）读回来，
     * 归一化成与实时事件相同的 assistant / reasoning / tool / turn 事件，cursor 也从 0 开始连续编号。
     * </p>
     * <p>
     * 只读、尽力而为：失败只记日志（{@code historyLoaded=true} 防止反复重试），不影响续聊。
     * </p>
     *
     * @param state 线程状态
     */
    private void loadHistory(CodexThreadState state) {
        if (state.historyLoaded) {
            return;
        }
        if (config.historyTurns <= 0) {
            state.historyLoaded = true;
            return;
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("threadId", state.threadId);
            params.addProperty("limit", config.historyTurns);
            params.addProperty("itemsView", "full");
            params.addProperty("sortDirection", "desc");
            JsonObject result = process.request("thread/turns/list", params,
                    Math.max(config.requestTimeoutMs, 60000L));
            JsonArray data = result.has("data") && result.get("data").isJsonArray()
                    ? result.getAsJsonArray("data") : new JsonArray();
            List<JsonObject> turns = new ArrayList<>();
            for (JsonElement element : data) {
                if (element != null && element.isJsonObject()) {
                    turns.add(element.getAsJsonObject());
                }
            }
            // 接口按最新在前返回，回放要按时间正序
            Collections.reverse(turns);
            for (JsonObject turn : turns) {
                seedTurn(state, turn);
            }
            state.finishHistorySeed();
            log("已回放线程 " + state.threadId + " 的 " + turns.size() + " 轮历史");
        } catch (Throwable t) {
            state.historyLoaded = true;
            log("回放历史失败（不影响续聊）：" + t.getMessage());
        }
    }

    /** 回放一轮。 */
    private void seedTurn(CodexThreadState state, JsonObject turn) {
        String turnId = JsonConfig.getString(turn, "id");
        JsonArray items = turn.has("items") && turn.get("items").isJsonArray()
                ? turn.getAsJsonArray("items") : new JsonArray();
        for (JsonElement element : items) {
            if (element != null && element.isJsonObject()) {
                seedItem(state, element.getAsJsonObject(), turnId);
            }
        }
        String status = JsonConfig.getString(turn, "status");
        JsonObject event = state.addEvent("turn");
        event.addProperty("status", status == null ? "completed" : status);
        event.addProperty("history", true);
        JsonElement error = turn.get("error");
        if (error != null && !error.isJsonNull()) {
            JsonObject errorObject = error.isJsonObject() ? error.getAsJsonObject() : new JsonObject();
            String message = JsonConfig.getString(errorObject, "message");
            if (message != null) {
                event.addProperty("error", message);
            }
        }
        state.markHistoryTurn(turnId, status);
    }

    /** 回放一个条目（与实时 {@code handleItem} 的展示口径一致）。 */
    private void seedItem(CodexThreadState state, JsonObject item, String turnId) {
        String type = JsonConfig.getString(item, "type");
        if (type == null) {
            return;
        }
        String itemId = JsonConfig.getString(item, "id");
        switch (type) {
            case "agentMessage": {
                String text = JsonConfig.getString(item, "text");
                if (text != null && !text.isEmpty()) {
                    state.replaceText("assistant", text, config.maxEventChars);
                }
                return;
            }
            case "reasoning": {
                JsonArray summary = item.has("summary") && item.get("summary").isJsonArray()
                        ? item.getAsJsonArray("summary") : new JsonArray();
                StringBuilder joined = new StringBuilder();
                for (JsonElement part : summary) {
                    if (part != null && !part.isJsonNull()) {
                        if (joined.length() > 0) {
                            joined.append('\n');
                        }
                        joined.append(part.getAsString());
                    }
                }
                if (joined.length() > 0) {
                    state.replaceText("reasoning", joined.toString(), config.maxEventChars);
                }
                return;
            }
            case "commandExecution": {
                String command = JsonConfig.getString(item, "command");
                state.addTool("command", shorten(command == null ? "" : command.replace('\n', ' '), 160),
                        JsonConfig.getString(item, "status"), itemId, turnId);
                return;
            }
            case "fileChange": {
                JsonArray changes = item.has("changes") && item.get("changes").isJsonArray()
                        ? item.getAsJsonArray("changes") : new JsonArray();
                List<String> paths = new ArrayList<>();
                for (JsonElement change : changes) {
                    if (change != null && change.isJsonObject()) {
                        String path = JsonConfig.getString(change.getAsJsonObject(), "path");
                        if (path != null) {
                            paths.add(path);
                        }
                    }
                }
                state.addTool("file", paths.isEmpty() ? "修改文件" : join(paths, 3),
                        JsonConfig.getString(item, "status"), itemId, turnId);
                return;
            }
            case "mcpToolCall": {
                String server = JsonConfig.getString(item, "server");
                String tool = JsonConfig.getString(item, "tool");
                state.addTool("mcp", (server == null ? "" : server + "/") + (tool == null ? "" : tool),
                        JsonConfig.getString(item, "status"), itemId, turnId);
                return;
            }
            case "webSearch": {
                state.addTool("web", JsonConfig.getString(item, "query"), "completed", itemId, turnId);
                return;
            }
            case "contextCompaction": {
                state.addTool("compact", "上下文已压缩", "completed", itemId, turnId);
                return;
            }
            case "imageView": {
                handleImage(state, "view", JsonConfig.getString(item, "path"), null, null, itemId, true);
                return;
            }
            case "imageGeneration": {
                String status = JsonConfig.getString(item, "status");
                String saved = JsonConfig.getString(item, "savedPath");
                if (saved != null && (status == null || "completed".equalsIgnoreCase(status))) {
                    handleImage(state, "generation", saved, null, null, itemId, true);
                }
                return;
            }
            default:
                // userMessage 等不进入回放，避免与聊天记录重复
                return;
        }
    }

    // ==================== 图片（截图 / 生成图 / MCP 图片结果） ====================

    /** 单轮最多回传多少张图片。 */
    private static final int MAX_IMAGES_PER_TURN = 4;
    /** 单张图片的字节上限。 */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** markdown 图片：{@code ![alt](path)}，也接受 {@code ![alt](<path with space>)}。 */
    private static final java.util.regex.Pattern MD_IMAGE = java.util.regex.Pattern.compile(
            "!\\[([^\\]]*)\\]\\(\\s*(?:<([^>]+)>|([^)\\s]+))");

    /**
     * 记录一张 Codex 产出的图片。
     *
     * @param state   线程
     * @param source  来源：view / generation / mcp
     * @param path    本地路径，可为空
     * @param dataUrl data URL，可为空（MCP 内联图片）
     * @param mime    MIME，可为空
     * @param itemId  条目 id（去重）
     * @param history 是否历史回放
     */
    private void handleImage(CodexThreadState state, String source, String path, String dataUrl, String mime,
            String itemId, boolean history) {
        if (state == null) {
            return;
        }
        if (path != null && !path.isEmpty() && !isAllowedImagePath(state, path)) {
            log("忽略工作区外的图片：" + shorten(path, 200));
            return;
        }
        byte[] bytes = dataUrl != null && !dataUrl.isEmpty() ? decodeDataUrl(dataUrl) : readImageBytes(path);
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            log("忽略过大的图片（" + bytes.length + " 字节）");
            return;
        }
        String type = mime != null && !mime.isEmpty() ? mime
                : guessMime(path != null && !path.isEmpty() ? path : dataUrl);
        String id = null;
        ObjectStorageProvider current = storage;
        if (current != null) {
            try {
                // 与其它工具一致：先存对象存储拿 id，agent/QQ 都只传 id
                id = current.uploadIfNotExists(bytes, null);
            } catch (Throwable t) {
                log("图片上传对象存储失败，退回 data URL：" + t.getMessage());
            }
        }
        state.addImage(source, id, type, id == null ? toDataUrl(type, bytes) : null, itemId, history);
    }

    /** 只允许工作目录、允许的根目录、临时目录与 CODEX_HOME 下的图片，避免把任意文件发出去。 */
    private boolean isAllowedImagePath(CodexThreadState state, String path) {
        try {
            String normalized = path.startsWith("\\\\?\\") ? path.substring(4) : path;
            File file = new File(normalized).getCanonicalFile();
            if (!file.isFile()) {
                return false;
            }
            List<File> allowed = new ArrayList<>();
            allowed.add(new File(state.cwd).getCanonicalFile());
            for (File root : config.roots) {
                allowed.add(root.getCanonicalFile());
            }
            allowed.add(new File(System.getProperty("java.io.tmpdir", ".")).getCanonicalFile());
            File home = config.homeFolder();
            if (home == null) {
                home = new File(System.getProperty("user.home", "."), ".codex");
            }
            allowed.add(home.getCanonicalFile());
            String target = file.getPath().toLowerCase();
            for (File root : allowed) {
                String base = root.getPath().toLowerCase();
                while (base.endsWith("\\") || base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                if (target.equals(base) || target.startsWith(base + File.separator)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            log("检查图片路径失败：" + t.getMessage());
        }
        return false;
    }

    /** 从 MCP 工具结果里提取内联图片（MCP content 的 image 块）。 */
    private void collectMcpImages(CodexThreadState state, JsonObject item, String itemId) {
        if (!item.has("result") || !item.get("result").isJsonObject()) {
            return;
        }
        JsonObject result = item.getAsJsonObject("result");
        if (!result.has("content") || !result.get("content").isJsonArray()) {
            return;
        }
        JsonArray content = result.getAsJsonArray("content");
        int index = 0;
        for (JsonElement block : content) {
            if (block == null || !block.isJsonObject()) {
                continue;
            }
            JsonObject object = block.getAsJsonObject();
            if (!"image".equals(JsonConfig.getString(object, "type"))) {
                continue;
            }
            String mime = JsonConfig.getString(object, "mimeType");
            String data = JsonConfig.getString(object, "data");
            if (data == null || data.isEmpty()) {
                continue;
            }
            String dataUrl = "data:" + (mime == null ? "image/png" : mime) + ";base64," + data;
            handleImage(state, "mcp", null, dataUrl, mime,
                    (itemId == null ? "mcp" : itemId) + "#" + (index++), false);
        }
    }

    /**
     * 扫描 Codex 最终回复里的 markdown 图片路径，把本地图片上传成附件，并在正文末尾注明「文件 → 附件 id」。
     * <p>
     * Codex 经常在回答里写 {@code ![截图](screenshots/a.png)} 而不是单独发 imageView；
     * 这里把这类本地路径补成附件，群里就能直接看到图。
     * </p>
     *
     * @param state 线程
     * @param text  Codex 的最终正文
     */
    private void scanMarkdownImages(CodexThreadState state, String text) {
        if (text == null || text.indexOf("![") < 0) {
            return;
        }
        java.util.regex.Matcher matcher = MD_IMAGE.matcher(text);
        List<String> uploaded = new ArrayList<>();
        while (matcher.find() && uploaded.size() < MAX_IMAGES_PER_TURN) {
            String target = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (target == null) {
                continue;
            }
            String path = localImagePath(state, target.trim());
            if (path == null || !state.claimMarkdown(path)) {
                continue;
            }
            if (!isAllowedImagePath(state, path)) {
                log("忽略 markdown 里的工作区外图片：" + shorten(path, 200));
                continue;
            }
            byte[] bytes = readImageBytes(path);
            if (bytes == null || bytes.length > MAX_IMAGE_BYTES) {
                continue;
            }
            ObjectStorageProvider current = storage;
            if (current == null) {
                log("没有对象存储，跳过 markdown 图片：" + shorten(path, 160));
                continue;
            }
            String id;
            try {
                id = current.uploadIfNotExists(bytes, null);
            } catch (Throwable t) {
                log("markdown 图片上传失败：" + t.getMessage());
                continue;
            }
            if (id == null || id.isEmpty()) {
                continue;
            }
            state.addImage("markdown", id, guessMime(path), null, "md:" + path, false);
            state.addMarkdownNote(shorten(path, 160) + " → " + id);
            uploaded.add(shorten(path, 160) + " → " + id);
        }
        if (uploaded.isEmpty()) {
            return;
        }
        StringBuilder footer = new StringBuilder("\n\n（已上传为附件：");
        for (int i = 0; i < uploaded.size(); i++) {
            if (i > 0) {
                footer.append("；");
            }
            footer.append(uploaded.get(i));
        }
        footer.append("）");
        state.replaceText("assistant", text + footer, config.maxEventChars);
    }

    /** 把 markdown 里的目标解析成本地绝对路径；网络图片、data URL、锚点返回 null。 */
    private String localImagePath(CodexThreadState state, String target) {
        if (target == null) {
            return null;
        }
        String value = target.trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")
                || lower.startsWith("mailto:") || lower.startsWith("#")) {
            return null;
        }
        if (lower.startsWith("file://")) {
            value = value.substring("file://".length());
            // file:///C:/x → /C:/x
            if (value.startsWith("/") && value.length() > 2 && value.charAt(2) == ':') {
                value = value.substring(1);
            }
        }
        try {
            value = java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Throwable ignored) {
            // 保留原样
        }
        File file = new File(value);
        if (!file.isAbsolute()) {
            file = new File(state.cwd, value);
        }
        try {
            file = file.getCanonicalFile();
        } catch (Throwable ignored) {
            // 保留绝对路径
        }
        return file.getPath();
    }

    /**
     * 取某线程本轮产生的图片（对象存储 id，或降级 data URL），供异步工具作为附件发到 QQ。
     * <p>
     * 历史回放的图片不在这里返回，避免续聊时把旧截图重新发一遍。
     * </p>
     *
     * @param threadId 线程 id
     * @return 图片数组，元素含 source/id/mime，必要时含 data
     */
    public JsonArray collectImages(String threadId) {
        JsonArray result = new JsonArray();
        CodexThreadState state = threadOf(threadId);
        if (state == null) {
            return result;
        }
        for (JsonObject image : state.imagePayloads()) {
            if (result.size() >= MAX_IMAGES_PER_TURN) {
                break;
            }
            result.add(image.deepCopy());
        }
        return result;
    }

    // ==================== 文件传输（对象存储 ↔ 项目工作目录） ====================

    /** 单次文件传输的字节上限：对象存储接口按整块读写，避免误传超大文件撑爆内存。 */
    private static final long MAX_TRANSFER_BYTES = 64L * 1024 * 1024;
    /** 对象存储 id 允许的字符：内容哈希、UUID 或带扩展名的键；不允许路径分隔符，防止越出存储目录。 */
    private static final java.util.regex.Pattern STORAGE_FILE_ID = java.util.regex.Pattern
            .compile("[A-Za-z0-9._-]{1,200}");
    /**
     * 文件传输的固定目录（相对线程工作目录）。
     * <p>
     * AI 不能选目录，只能在这个目录下放文件；需要换位置时改这一行常量。
     * </p>
     */
    private static final String TRANSFER_DIR = "ai_files";
    /** 文件名里不允许的字符：路径分隔符、Windows 保留字符与控制字符。 */
    private static final java.util.regex.Pattern INVALID_FILE_NAME = java.util.regex.Pattern
            .compile("[\\\\/:*?\"<>|\\x00-\\x1F]");
    /** Windows 保留设备名，不能用作文件名。 */
    private static final java.util.regex.Pattern RESERVED_FILE_NAME = java.util.regex.Pattern
            .compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    /**
     * 从对象存储取文件，写到 Codex 线程固定接收目录 {@value #TRANSFER_DIR} 下。
     * <p>
     * 目录由代码固定，AI 只能给纯文件名（不能带目录）；这样既不会覆盖项目源码，也不会越出项目。
     * 目标文件已存在时默认报错，确实要覆盖传 {@code overwrite=true}。
     * </p>
     *
     * @param owner     归属键（QQ 会话或 MCP 用户）
     * @param threadId  线程 id，可为空（用该归属键的默认线程；还没有线程时用默认项目目录）
     * @param fileId    对象存储文件 id（不能是链接或本地路径）
     * @param fileName  目标文件名（纯文件名，例如 icon.png；不能带目录）
     * @param overwrite 目标已存在时是否覆盖
     * @return {@code {ok, file_id, path, relative, bytes, overwritten}} 或 {@code {ok:false, error}}
     */
    public JsonObject pullFile(String owner, String threadId, String fileId, String fileName, boolean overwrite) {
        try {
            String id = fileId == null ? "" : fileId.trim();
            if (id.isEmpty()) {
                return error("缺少 file_id（对象存储文件 id）");
            }
            if (!STORAGE_FILE_ID.matcher(id).matches()) {
                return error("file_id 只能是对象存储 id（字母、数字、点、下划线、短横线），"
                        + "不要传链接、本地路径或带目录的键");
            }
            String name = checkFileName(fileName);
            ObjectStorageProvider store = storage;
            if (store == null) {
                return error("没有可用的对象存储，无法取文件（检查 MCP/QQ 的对象存储配置）");
            }
            File dir = transferDir(fileBaseDir(owner, threadId));
            File target = transferTarget(dir, name);
            boolean existed = target.exists();
            if (existed && !overwrite) {
                return error("固定目录里已有同名文件：" + target.getPath() + "；确实要覆盖请传 overwrite=true");
            }
            byte[] data;
            try {
                data = store.download(id, null);
            } catch (Throwable t) {
                return error("对象存储下载失败：" + describe(t)
                        + "（确认 file_id 正确、文件仍然存在；图片/视频 id 通常是 72 位哈希）");
            }
            if (data == null || data.length == 0) {
                return error("对象存储返回了空文件：" + id);
            }
            if (data.length > MAX_TRANSFER_BYTES) {
                return error("文件过大：" + data.length + " 字节，单次上限 " + MAX_TRANSFER_BYTES + " 字节");
            }
            FileUtil.transfer(data, target);
            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("file_id", id);
            json.addProperty("path", target.getAbsolutePath());
            json.addProperty("relative", TRANSFER_DIR + "/" + name);
            json.addProperty("bytes", data.length);
            json.addProperty("overwritten", existed);
            json.addProperty("next", "文件已就位；如需让 Codex 使用，请在 codex_send 的任务里写相对路径 "
                    + TRANSFER_DIR + "/" + name);
            log("已从对象存储取文件 " + shorten(id, 80) + " → " + target.getPath());
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * 把固定接收目录 {@value #TRANSFER_DIR} 里的文件上传到对象存储，返回可供其它工具使用的文件 id。
     * <p>
     * 目录由代码固定，AI 只能给纯文件名；项目其它位置的文件不能通过本工具外传。
     * 上传不带扩展名，键与现有图片/媒体 id 约定一致（72 位内容哈希）。
     * </p>
     *
     * @param owner    归属键
     * @param threadId 线程 id，可为空（用该线程的默认目录）
     * @param fileName 固定目录里的文件名（纯文件名，不能带目录）
     * @return {@code {ok, file_id, path, relative, bytes}} 或 {@code {ok:false, error}}
     */
    public JsonObject pushFile(String owner, String threadId, String fileName) {
        try {
            String name = checkFileName(fileName);
            ObjectStorageProvider store = storage;
            if (store == null) {
                return error("没有可用的对象存储，无法上传文件");
            }
            File dir = transferDir(fileBaseDir(owner, threadId));
            File source = transferTarget(dir, name);
            if (!source.isFile()) {
                return error("固定目录里没有这个文件：" + source.getPath() + "（目录：" + TRANSFER_DIR + "）");
            }
            long length = source.length();
            if (length <= 0) {
                return error("文件是空的，无法上传：" + source.getPath());
            }
            if (length > MAX_TRANSFER_BYTES) {
                return error("文件过大：" + length + " 字节，单次上限 " + MAX_TRANSFER_BYTES + " 字节");
            }
            byte[] data = java.nio.file.Files.readAllBytes(source.toPath());
            // 不带扩展名：与其它工具一致，id 就是 72 位内容哈希
            String id = store.uploadIfNotExists(data, null, null);
            if (id == null || id.isEmpty()) {
                return error("上传对象存储后没有返回文件 id");
            }
            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("file_id", id);
            json.addProperty("path", source.getAbsolutePath());
            json.addProperty("relative", TRANSFER_DIR + "/" + name);
            json.addProperty("bytes", data.length);
            log("已把固定目录文件上传到对象存储：" + source.getPath() + " → " + shorten(id, 80));
            return json;
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * 文件传输的基准目录：优先使用指定线程/默认线程的 {@code cwd}；
     * 还没有线程时退回该归属键的默认项目目录（不为了传文件去启动 Codex）。
     *
     * @param owner    归属键
     * @param threadId 线程 id，可为空
     * @return 规范化后的项目目录
     * @throws IOException 目录不存在或线程不属于当前归属键
     */
    private File fileBaseDir(String owner, String threadId) throws IOException {
        CodexThreadState state;
        if (threadId != null && !threadId.trim().isEmpty()) {
            String id = threadId.trim();
            state = threadOf(id);
            if (state == null) {
                throw new IOException("未知线程：" + id + "（可用 codex_threads 查看）");
            }
            if (owner != null && !owner.equals(state.owner)) {
                throw new IOException("线程不属于当前会话：" + id);
            }
        } else {
            String current;
            synchronized (this) {
                current = ownerDefault.get(owner);
            }
            state = current == null ? null : threadOf(current);
        }
        File base = state != null ? new File(state.cwd) : resolveOwnerCwd(owner, null);
        if (!base.isDirectory()) {
            throw new IOException("项目工作目录不存在：" + base.getPath());
        }
        return base.getCanonicalFile();
    }

    /**
     * 固定接收目录：{@code <项目cwd>/ai_files}，不存在时自动创建。
     * <p>
     * 目录名由常量 {@link #TRANSFER_DIR} 固定，AI 无法指定其它目录。
     * </p>
     *
     * @param base 项目工作目录（已规范化）
     * @return 已创建的固定目录
     * @throws IOException 无法创建或目录越出项目
     */
    private static File transferDir(File base) throws IOException {
        File dir = new File(base, TRANSFER_DIR).getCanonicalFile();
        java.nio.file.Path basePath = base.toPath();
        java.nio.file.Path dirPath = dir.toPath();
        if (dirPath.equals(basePath) || !dirPath.startsWith(basePath)) {
            throw new IOException("固定接收目录越出了项目工作目录：" + dir);
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建固定接收目录：" + dir.getPath());
        }
        return dir;
    }

    /**
     * 解析固定目录里的目标文件，并挡住通过软链指向目录外的文件。
     *
     * @param dir  固定接收目录（已规范化）
     * @param name 已校验的纯文件名
     * @return 规范化后的目标文件（可能不存在）
     * @throws IOException 目标越出固定目录
     */
    private static File transferTarget(File dir, String name) throws IOException {
        File target = new File(dir, name).getCanonicalFile();
        if (!dir.equals(target.getParentFile())) {
            throw new IOException("目标文件越出了固定接收目录：" + name);
        }
        return target;
    }

    /**
     * 校验调用方给的文件名：只允许纯文件名，不能带目录、盘符或 Windows 保留字符。
     *
     * @param fileName 文件名
     * @return 去掉首尾空白后的文件名
     * @throws IOException 文件名不合法
     */
    private static String checkFileName(String fileName) throws IOException {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            throw new IOException("缺少 name（文件名，例如 icon.png）");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IOException("name 不能是 . 或 ..");
        }
        if (name.length() > 120) {
            throw new IOException("name 太长（最多 120 个字符）");
        }
        if (INVALID_FILE_NAME.matcher(name).find()) {
            throw new IOException("name 只能是纯文件名，不能带目录或这些字符：\\ / : * ? \" < > |");
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw new IOException("name 不能以点或空格结尾");
        }
        if (RESERVED_FILE_NAME.matcher(name).matches()) {
            throw new IOException("name 是系统保留名，请换一个：" + name);
        }
        return name;
    }

    /** 异常转一行可读文本，供工具结果使用。 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    /** 读取本地图片字节；失败或过大返回 null。 */
    private byte[] readImageBytes(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            String normalized = path.startsWith("\\\\?\\") ? path.substring(4) : path;
            File file = new File(normalized);
            if (!file.isFile() || file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) {
                return null;
            }
            return java.nio.file.Files.readAllBytes(file.toPath());
        } catch (Throwable t) {
            log("读取图片失败：" + t.getMessage());
            return null;
        }
    }

    /** 解 data URL 里的 base64 字节。 */
    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.substring(5, comma).contains(";base64")) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 字节转 data URL（没有对象存储时的降级路径）。 */
    private static String toDataUrl(String mime, byte[] bytes) {
        String type = mime == null || mime.isEmpty() ? "image/png" : mime;
        return "data:" + type + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** 按扩展名猜 MIME；识别不了用 image/png。 */
    private static String guessMime(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    private void nameThread(String threadId, String name) {        try {
            JsonObject params = new JsonObject();
            params.addProperty("threadId", threadId);
            params.addProperty("name", name);
            process.request("thread/name/set", params, 20000L);
        } catch (Throwable ignored) {
            // 命名失败不影响主流程
        }
    }

    private JsonObject latestOf(CodexThreadState state, long since, boolean includeReasoning, int maxItems) {
        JsonObject snapshot = state.snapshot(since < 0 ? null : Long.valueOf(since), includeReasoning, maxItems);
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("thread_id", state.threadId);
        json.addProperty("owner", state.owner);
        json.addProperty("cwd", state.cwd);
        json.addProperty("model", state.model == null ? "" : state.model);
        json.addProperty("running", state.running);
        json.addProperty("status", state.status);
        json.addProperty("history_loaded", state.historyLoaded);
        json.addProperty("cursor", JsonConfig.getLong(snapshot, "cursor"));
        json.addProperty("latest_cursor", JsonConfig.getLong(snapshot, "latest_cursor"));
        if (JsonConfig.getBool(snapshot, "truncated")) {
            json.addProperty("truncated", true);
            json.addProperty("hint_truncated", "中间有事件因缓冲上限被丢弃，可从 cursor 继续读后续内容。");
        }
        if (state.lastError != null && !state.lastError.isEmpty()) {
            json.addProperty("last_error", shorten(state.lastError, 500));
        }
        json.add("items", snapshot.getAsJsonArray("items"));
        JsonArray pending = state.pendingArray();
        if (pending.size() > 0) {
            json.add("pending", pending);
            json.addProperty("hint", "有待审批/待回答的交互：用 codex_feedback 处理。");
        }
        if (state.usage != null) {
            json.add("usage", state.usage);
        }
        return json;
    }

    private JsonObject summary(CodexThreadState state, boolean current) {
        JsonObject json = new JsonObject();
        json.addProperty("thread_id", state.threadId);
        json.addProperty("owner", state.owner);
        json.addProperty("cwd", state.cwd);
        json.addProperty("model", state.model == null ? "" : state.model);
        if (state.projectId != null && !state.projectId.isEmpty()) {
            json.addProperty("project_id", state.projectId);
        }
        json.addProperty("created_at", state.createdAt);
        json.addProperty("running", state.running);
        json.addProperty("status", state.status);
        json.addProperty("cursor", state.getCursor());
        json.addProperty("pending", state.pendingCount());
        json.addProperty("history_loaded", state.historyLoaded);
        json.addProperty("current", current);
        if (state.lastError != null && !state.lastError.isEmpty()) {
            json.addProperty("last_error", shorten(state.lastError, 300));
        }
        return json;
    }

    private JsonObject threadStartParams(File cwd, String projectId) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd.getAbsolutePath());
        params.addProperty("approvalPolicy", config.approvalPolicy);
        params.addProperty("approvalsReviewer", config.approvalsReviewer);
        params.addProperty("sandbox", config.sandbox);
        params.addProperty("ephemeral", config.ephemeral);
        params.addProperty("serviceName", "codex-connector");
        params.addProperty("developerInstructions", instructions());
        if (!config.model.isEmpty()) {
            params.addProperty("model", config.model);
        }
        if (projectId != null && !projectId.isEmpty()) {
            params.addProperty("projectId", projectId);
        }
        return params;
    }

    private JsonObject sandboxPolicy(CodexThreadState state) {
        JsonObject policy = new JsonObject();
        if ("read-only".equals(config.sandbox)) {
            policy.addProperty("type", "readOnly");
            policy.addProperty("networkAccess", false);
        } else if ("danger-full-access".equals(config.sandbox)) {
            policy.addProperty("type", "dangerFullAccess");
        } else {
            policy.addProperty("type", "workspaceWrite");
            JsonArray roots = new JsonArray();
            roots.add(state.cwd);
            policy.add("writableRoots", roots);
            policy.addProperty("networkAccess", config.networkAccess);
            policy.addProperty("excludeTmpdirEnvVar", false);
            policy.addProperty("excludeSlashTmp", false);
        }
        return policy;
    }

    private String instructions() {
        if (config.instructions.isEmpty()) {
            return CodexConfig.DEFAULT_INSTRUCTIONS;
        }
        return CodexConfig.DEFAULT_INSTRUCTIONS + "\n" + config.instructions;
    }

    private void ensureStarted() throws IOException {
        if (process.isReady()) {
            return;
        }
        if (closed) {
            throw new IOException("Codex 服务已关闭");
        }
        synchronized (startLock) {
            if (process.isReady()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!startError.isEmpty() && now - lastStartAttempt < 5000L) {
                throw new IOException(startError);
            }
            lastStartAttempt = now;
            try {
                process.start();
                resetLoaded();
                startError = "";
            } catch (IOException ex) {
                startError = ex.getMessage();
                throw ex;
            }
        }
    }

    private void resetLoaded() {
        synchronized (this) {
            for (CodexThreadState state : threads.values()) {
                state.loaded = false;
            }
        }
    }

    private void endRunningTurns(String reason) {
        List<CodexThreadState> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(threads.values());
        }
        for (CodexThreadState state : snapshot) {
            if (state.running) {
                String turnId = state.activeTurnId;
                state.finishTurn(turnId, "disconnected", reason == null ? "codex 进程已退出" : reason);
                state.lastError = reason == null ? "codex 进程已退出" : reason;
                state.addEvent("error").addProperty("message", state.lastError);
            }
            completeTurnWaiter(state);
        }
    }

    private void scheduleTurnTimeout(CodexThreadState state, String turnId) {
        scheduler.schedule(() -> {
            if (!state.running || !turnId.equals(state.activeTurnId)) {
                return;
            }
            state.addEvent("notice").addProperty("text",
                    "单轮执行超过 " + config.turnTimeoutSeconds() + " 秒，已请求中断。");
            try {
                JsonObject params = new JsonObject();
                params.addProperty("threadId", state.threadId);
                params.addProperty("turnId", turnId);
                process.request("turn/interrupt", params, 30000L);
            } catch (Throwable t) {
                log("超时中断失败：" + t.getMessage());
            }
            state.endTurn();
        }, config.turnTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void awaitChange(CodexThreadState state, long before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (state.getCursor() > before || state.pendingCount() > 0 || !state.running) {
                return;
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<String> adminOf(CodexThreadState state) {
        if (state == null) {
            return java.util.Collections.emptyList();
        }
        synchronized (ownerAdmin) {
            List<String> admins = ownerAdmin.get(state.owner);
            return admins == null ? java.util.Collections.<String>emptyList() : admins;
        }
    }

    private int clampWait(int waitSeconds) {
        return Math.max(0, Math.min(waitSeconds, config.maxWaitSeconds));
    }

    private CodexThreadState threadOf(String threadId) {
        if (threadId == null) {
            return null;
        }
        synchronized (this) {
            return threads.get(threadId);
        }
    }

    // ==================== 持久化 ====================

    private void loadState() {
        try {
            if (!stateFile.isFile()) {
                return;
            }
            JsonObject root = JsonParser.parseString(FileUtil.readString(stateFile)).getAsJsonObject();
            if (root.has("threads") && root.get("threads").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("threads").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject item = entry.getValue().getAsJsonObject();
                    CodexThreadState state = new CodexThreadState(entry.getKey(),
                            JsonConfig.getString(item, "owner"), JsonConfig.getString(item, "cwd"));
                    state.model = JsonConfig.getString(item, "model");
                    state.loaded = false;
                    threads.put(entry.getKey(), state);
                }
            }
            if (root.has("owners") && root.get("owners").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("owners").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        ownerDefault.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            log("已载入 " + threads.size() + " 条 Codex 线程映射");
        } catch (Throwable t) {
            log("载入线程映射失败：" + t);
        }
    }

    private void saveState() {
        JsonObject root = new JsonObject();
        JsonObject owners = new JsonObject();
        JsonObject threadObjects = new JsonObject();
        synchronized (this) {
            for (Map.Entry<String, String> entry : ownerDefault.entrySet()) {
                owners.addProperty(entry.getKey(), entry.getValue());
            }
            for (CodexThreadState state : threads.values()) {
                JsonObject item = new JsonObject();
                item.addProperty("owner", state.owner);
                item.addProperty("cwd", state.cwd);
                item.addProperty("model", state.model == null ? "" : state.model);
                item.addProperty("created_at", state.createdAt);
                threadObjects.add(state.threadId, item);
            }
        }
        root.add("owners", owners);
        root.add("threads", threadObjects);
        try {
            config.dataFolder.mkdirs();
            FileUtil.transfer(root.toString(), stateFile);
        } catch (Throwable t) {
            log("保存线程映射失败：" + t);
        }
    }

    // ==================== 静态工具 ====================

    private static JsonObject failure(Throwable t) {
        String message = t == null ? "未知错误" : t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = t.getClass().getName();
        }
        return error(message);
    }

    private static JsonObject error(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", message);
        return json;
    }

    /**
     * 解析审批答复文本。
     *
     * @param value 用户文本
     * @return accept / acceptForSession / decline / cancel；无法识别返回 null
     */
    static String parseDecision(String value) {
        String text = value == null ? "" : value.trim().toLowerCase();
        if (text.isEmpty()) {
            return null;
        }
        if (text.equals("allow") || text.equals("accept") || text.equals("approve") || text.equals("yes")
                || text.equals("ok") || text.equals("1") || text.equals("允许") || text.equals("同意")
                || text.equals("批准")) {
            return "accept";
        }
        if (text.equals("allow_session") || text.equals("acceptforsession") || text.equals("session")
                || text.equals("记住") || text.equals("本会话允许") || text.equals("2")) {
            return "acceptForSession";
        }
        if (text.equals("deny") || text.equals("decline") || text.equals("reject") || text.equals("no")
                || text.equals("3") || text.equals("拒绝") || text.equals("不同意") || text.equals("驳回")) {
            return "decline";
        }
        if (text.equals("cancel") || text.equals("interrupt") || text.equals("中断") || text.equals("取消")
                || text.equals("4")) {
            return "cancel";
        }
        return null;
    }

    private JsonObject buildAnswers(CodexThreadState.Pending pending, String text, String selected,
            String answers) {
        if (answers != null && !answers.trim().isEmpty()) {
            try {
                JsonElement parsed = JsonParser.parseString(answers);
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
                if (parsed != null && parsed.isJsonArray()) {
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("answers", parsed);
                    return wrapper;
                }
            } catch (RuntimeException ignored) {
                // 不是 JSON 时按文本处理
            }
        }
        JsonObject result = new JsonObject();
        JsonObject map = new JsonObject();
        String custom = text == null ? "" : text.trim();
        List<String> labels = new ArrayList<>();
        if (selected != null) {
            for (String label : selected.split("\\|")) {
                if (!label.trim().isEmpty()) {
                    labels.add(label.trim());
                }
            }
        }
        for (JsonElement element : pending.questions) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            String id = JsonConfig.getString(element.getAsJsonObject(), "id");
            if (id == null) {
                continue;
            }
            JsonArray values = new JsonArray();
            if (!labels.isEmpty()) {
                for (String label : labels) {
                    values.add(label);
                }
            } else if (!custom.isEmpty()) {
                values.add(custom);
            }
            JsonObject answer = new JsonObject();
            answer.add("answers", values);
            map.add(id, answer);
        }
        if (map.size() == 0 && !custom.isEmpty()) {
            map.add("answer", buildAnswerValues(custom));
        }
        result.add("answers", map);
        return result;
    }

    private static JsonObject buildAnswerValues(String text) {
        JsonArray values = new JsonArray();
        values.add(text);
        JsonObject answer = new JsonObject();
        answer.add("answers", values);
        return answer;
    }

    private static String join(List<String> values, int max) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String value : values) {
            if (count > 0) {
                builder.append("、");
            }
            builder.append(value);
            count++;
            if (count >= max) {
                if (values.size() > max) {
                    builder.append("…");
                }
                break;
            }
        }
        return builder.toString();
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 轮次日志用的唯一 id：线程 id + turn id（turn id 缺失时用 activeTurnId/时间戳）。 */
    private static String turnLogId(CodexThreadState state, String turnId) {
        String turn = turnId;
        if ((turn == null || turn.isEmpty()) && state != null) {
            turn = state.activeTurnId;
        }
        if (turn == null || turn.isEmpty()) {
            turn = "pending-" + System.nanoTime();
        }
        String thread = state == null ? "" : state.threadId;
        return thread + "#" + turn;
    }

    /** 输出“对话开始”日志；send 与 turn/started 谁先到都只打印一次。 */
    private void logTurnStart(CodexThreadState state, String turnId) {
        if (state == null) {
            return;
        }
        String id = turnLogId(state, turnId);
        if (!loggedTurnStarts.add(id)) {
            return;
        }
        turnStartTimes.put(id, System.currentTimeMillis());
        log("对话开始 id=" + id + " thread=" + state.threadId
                + " owner=" + state.owner + " cwd=" + state.cwd);
    }

    /** 输出“对话结束”日志，带同一个唯一 id、状态和耗时。 */
    private void logTurnEnd(CodexThreadState state, String turnId, String status, String error) {
        if (state == null) {
            return;
        }
        String id = turnLogId(state, turnId);
        Long started = turnStartTimes.remove(id);
        loggedTurnStarts.remove(id);
        long duration = started == null ? 0L : System.currentTimeMillis() - started.longValue();
        String text = "对话结束 id=" + id + " thread=" + state.threadId
                + " status=" + (status == null || status.isEmpty() ? "completed" : status)
                + " duration=" + duration + "ms";
        if (error != null && !error.isEmpty()) {
            text += " error=" + shorten(error, 200);
        }
        log(text);
    }

    private static void log(String message) {
        System.out.println("[Codex] " + message);
    }
}
