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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Harness MCP 的高层业务实现。
 * <p>
 * 把 {@link HarnessBridge} 的低层请求包装成 MCP 工具需要的能力：
 * </p>
 * <ul>
 *   <li>列举配置的工作区，以及工作区下的聊天；</li>
 *   <li>新建聊天、向聊天投递任务；</li>
 *   <li>读取聊天最新输出与思维链（支持“没有变更就返回未变更”的游标语义）；</li>
 *   <li>接收 DSH 侧的提问/审批任务，并允许外部 AI 通过工具反馈。</li>
 * </ul>
 * <p>
 * 本类不直接依赖 HTTP/MCP 协议，便于单独测试；所有方法都返回面向模型的 JSON 文本。
 * </p>
 */
public class HarnessMcpService implements Closeable {

    /** 一次待外部 AI 处理的提问或审批。 */
    public static final class PendingInteraction {
        public final String id;
        public final String kind;
        public final String chatId;
        public final String sessionId;
        public final String title;
        public final String toolName;
        public final String reason;
        public final String callId;
        public final JsonArray questions;
        public final long createdAt = System.currentTimeMillis();
        /** 插件侧标记的原始负载，回传时原样使用。 */
        public final JsonObject payload;

        PendingInteraction(JsonObject payload) {
            this.payload = payload;
            this.id = JsonConfig.getString(payload, "id");
            this.kind = JsonConfig.getString(payload, "kind");
            this.chatId = JsonConfig.getString(payload, "chatId");
            this.sessionId = JsonConfig.getString(payload, "sessionId");
            this.title = JsonConfig.getString(payload, "title");
            this.toolName = JsonConfig.getString(payload, "toolName");
            this.reason = JsonConfig.getString(payload, "reason");
            this.callId = JsonConfig.getString(payload, "callId");
            JsonElement q = payload.get("questions");
            this.questions = q != null && q.isJsonArray() ? q.getAsJsonArray() : new JsonArray();
        }

        /** 转成给模型看的摘要。 */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("task_id", id);
            json.addProperty("kind", kind);
            json.addProperty("chat_id", chatId);
            if (sessionId != null) {
                json.addProperty("session_id", sessionId);
            }
            if (title != null) {
                json.addProperty("chat_title", title);
            }
            if (toolName != null && !toolName.isEmpty()) {
                json.addProperty("tool", toolName);
            }
            if (reason != null && !reason.isEmpty()) {
                json.addProperty("reason", reason);
            }
            if (callId != null && !callId.isEmpty()) {
                json.addProperty("call_id", callId);
            }
            if (questions.size() > 0) {
                json.add("questions", questions);
            }
            json.addProperty("created_at", createdAt);
            return json;
        }

        /** 是否已超时。 */
        boolean expired(long timeoutMs) {
            return System.currentTimeMillis() - createdAt > timeoutMs + 30000L;
        }
    }

    private final HarnessMcpConfig config;
    private final HarnessBridge bridge;
    private final HarnessSession session;
    private final Map<String, PendingInteraction> pending = new LinkedHashMap<>();
    private final Object pendingLock = new Object();
    private final AtomicBoolean bootStarted = new AtomicBoolean(false);
    private volatile String bootError = "";
    private volatile Thread bootThread;

    /**
     * 创建服务。
     *
     * @param config 配置
     * @throws IOException 桥监听失败
     */
    public HarnessMcpService(HarnessMcpConfig config) throws IOException {
        this.config = config;
        this.bridge = new HarnessBridge(new HarnessBridge.Handler() {
            @Override
            public void onHello(JsonObject info) {
                bootError = "";
                System.out.println("[Harness MCP] DSH 插件已连接："
                        + (info.has("version") ? info.get("version").getAsString() : ""));
            }

            @Override
            public void onInteraction(JsonObject payload) {
                PendingInteraction item = new PendingInteraction(payload);
                if (item.id == null || item.id.isEmpty()) {
                    return;
                }
                synchronized (pendingLock) {
                    pending.put(item.id, item);
                    pendingLock.notifyAll();
                }
                System.out.println("[Harness MCP] 收到待处理任务：" + item.kind + " " + item.id
                        + "（聊天 " + item.chatId + "）");
            }

            @Override
            public void onInteractionClosed(JsonObject payload) {
                String id = JsonConfig.getString(payload, "id");
                if (id == null) {
                    return;
                }
                synchronized (pendingLock) {
                    pending.remove(id);
                }
            }

            @Override
            public void onChatEvent(JsonObject payload) {
                // 聊天内容由工具主动轮询，这里不需要缓存
            }

            @Override
            public void onDisconnected() {
                System.out.println("[Harness MCP] DSH 插件连接已断开");
            }
        });
        this.session = new HarnessSession(config, bridge);
    }

    /** 配置。 */
    public HarnessMcpConfig getConfig() {
        return config;
    }

    /** 桥（供测试或扩展使用）。 */
    public HarnessBridge getBridge() {
        return bridge;
    }

    /** DSH 会话。 */
    public HarnessSession getSession() {
        return session;
    }

    /** 在后台启动桥、DSH 与插件；不会阻塞 MCP 初始化。 */
    public void startAsync() {
        bridge.start();
        session.start();
        if (!bootStarted.compareAndSet(false, true)) {
            return;
        }
        bootThread = new Thread(() -> {
            try {
                session.ensureReady();
                bootError = "";
            } catch (Throwable t) {
                bootError = t.getMessage() == null ? t.toString() : t.getMessage();
                bootStarted.set(false);
                System.out.println("[Harness MCP] 启动 DeepSeek Harness 失败：" + bootError);
            }
        }, "harness-boot");
        bootThread.setDaemon(true);
        bootThread.start();
    }

    /**
     * 等待就绪（用于工具调用前）。
     *
     * @param timeoutMs 最多等待毫秒
     * @throws IOException 未就绪
     */
    private void awaitReady(long timeoutMs) throws IOException {
        if (bridge.isConnected()) {
            return;
        }
        startAsync();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (bridge.isConnected()) {
                return;
            }
            if (bootError != null && !bootError.isEmpty() && bootThread != null && !bootThread.isAlive()) {
                throw new IOException("DeepSeek Harness 启动失败：" + bootError);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 DeepSeek Harness 启动被中断");
            }
        }
        throw new IOException("DeepSeek Harness 正在启动，请稍后重试"
                + (bootError == null || bootError.isEmpty() ? "" : "（" + bootError + "）"));
    }

    /** 带默认超时的桥请求。 */
    private JsonObject request(String method, JsonObject params) throws IOException {
        return request(method, params, config.requestTimeoutMs);
    }

    private JsonObject request(String method, JsonObject params, long timeoutMs) throws IOException {
        awaitReady(15000L);
        return bridge.request(method, params, timeoutMs);
    }

    // ==================== 工作区与聊天 ====================

    /**
     * 列举配置的工作区。
     *
     * @return JSON 文本
     */
    public String listWorkspaces() {
        JsonObject result = new JsonObject();
        JsonArray workspaces = new JsonArray();
        JsonArray chats = null;
        JsonObject dshWorkspaces = new JsonObject();
        try {
            JsonObject response = request("chat/list", chatListParams(500));
            chats = response.has("chats") && response.get("chats").isJsonArray()
                    ? response.getAsJsonArray("chats") : new JsonArray();
        } catch (Throwable t) {
            result.addProperty("warning", "聊天列表暂时不可用：" + describe(t));
        }
        try {
            JsonObject response = request("workspace/list", new JsonObject(), 30000L);
            if (response.has("workspaces") && response.get("workspaces").isJsonArray()) {
                for (JsonElement element : response.getAsJsonArray("workspaces")) {
                    if (element != null && element.isJsonObject()) {
                        JsonObject workspace = element.getAsJsonObject();
                        String path = JsonConfig.getString(workspace, "path");
                        if (path != null) {
                            dshWorkspaces.add(normalizePath(path), workspace);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 工作区注册表不可用时只依赖配置路径
        }
        for (File root : config.workspaces) {
            JsonObject item = new JsonObject();
            String path = normalizePath(root.getAbsolutePath());
            item.addProperty("name", root.getName().isEmpty() ? root.getPath() : root.getName());
            item.addProperty("path", root.getAbsolutePath());
            item.addProperty("exists", root.isDirectory());
            int count = 0;
            if (chats != null) {
                for (JsonElement element : chats) {
                    if (element != null && element.isJsonObject()) {
                        String cwd = JsonConfig.getString(element.getAsJsonObject(), "cwd");
                        if (cwd != null && isUnder(cwd, path)) {
                            count++;
                        }
                    }
                }
            }
            item.addProperty("chat_count", count);
            JsonObject registered = dshWorkspaces.getAsJsonObject(path);
            if (registered != null && registered.has("title")) {
                item.addProperty("dsh_title", registered.get("title").getAsString());
            }
            workspaces.add(item);
        }
        result.addProperty("ok", true);
        result.add("workspaces", workspaces);
        result.addProperty("note", "聊天按会话记录的工作目录筛选；可用 -Ddsh_mcp_workspaces=路径1;路径2 调整");
        return result.toString();
    }

    /**
     * 列举聊天。
     *
     * @param workspaceFilter 工作区路径或名称，可为空
     * @param limit           最大条数
     * @return JSON 文本
     */
    public String listChats(String workspaceFilter, int limit) {
        JsonObject result = new JsonObject();
        try {
            JsonObject response = request("chat/list", chatListParams(500));
            JsonArray source = response.has("chats") && response.get("chats").isJsonArray()
                    ? response.getAsJsonArray("chats") : new JsonArray();
            List<JsonObject> filtered = new ArrayList<>();
            for (JsonElement element : source) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject chat = element.getAsJsonObject();
                String cwd = JsonConfig.getString(chat, "cwd");
                if (cwd == null || !underAnyWorkspace(cwd)) {
                    continue;
                }
                if (!matchesWorkspaceFilter(chat, cwd, workspaceFilter)) {
                    continue;
                }
                filtered.add(chat);
            }
            filtered.sort(Comparator.comparingLong((JsonObject chat) -> JsonConfig.getLong(chat, "updatedAt")).reversed());
            JsonArray chats = new JsonArray();
            int max = limit <= 0 ? 50 : Math.min(limit, 200);
            for (int i = 0; i < filtered.size() && i < max; i++) {
                chats.add(toChatSummary(filtered.get(i)));
            }
            result.addProperty("ok", true);
            result.addProperty("count", chats.size());
            result.add("chats", chats);
        } catch (Throwable t) {
            result.addProperty("ok", false);
            result.addProperty("error", describe(t));
        }
        return result.toString();
    }

    private JsonObject chatListParams(int limit) {
        JsonObject params = new JsonObject();
        params.addProperty("limit", limit);
        return params;
    }

    private JsonObject toChatSummary(JsonObject chat) {
        JsonObject item = new JsonObject();
        item.addProperty("chat_id", JsonConfig.getString(chat, "id"));
        item.addProperty("title", displayTitle(chat));
        String cwd = JsonConfig.getString(chat, "cwd");
        item.addProperty("workspace", cwd);
        item.addProperty("updated_at", JsonConfig.getLong(chat, "updatedAt"));
        item.addProperty("running", JsonConfig.getBool(chat, "running"));
        item.addProperty("blank", JsonConfig.getBool(chat, "blank"));
        if (chat.has("status") && !chat.get("status").isJsonNull()) {
            item.addProperty("status", chat.get("status").getAsString());
        }
        return item;
    }

    private String displayTitle(JsonObject chat) {
        String title = JsonConfig.getString(chat, "title");
        if (title == null || title.trim().isEmpty()) {
            String cwd = JsonConfig.getString(chat, "cwd");
            String name = cwd == null ? "" : new File(cwd).getName();
            title = name.isEmpty() ? "未命名聊天" : name + " 下的聊天";
        }
        return title;
    }

    /**
     * 新建聊天。
     *
     * @param workspace 工作区路径或名称，可为空（使用第一个配置的工作区）
     * @param title     标题，可为空
     * @param task      首条任务，可为空
     * @return JSON 文本
     */
    public String newChat(String workspace, String title, String task) {
        JsonObject result = new JsonObject();
        File root = resolveWorkspace(workspace);
        if (root == null) {
            result.addProperty("ok", false);
            result.addProperty("error", workspace == null || workspace.isEmpty()
                    ? "没有可用的工作区，请先配置 harness.workspaces"
                    : "工作区不存在或未配置：" + workspace);
            return result.toString();
        }
        if (!root.exists()) {
            root.mkdirs();
        }
        if (!root.isDirectory()) {
            result.addProperty("ok", false);
            result.addProperty("error", "工作区不是目录：" + root.getPath());
            return result.toString();
        }
        String chatId = "session-" + UUID.randomUUID();
        try {
            JsonObject params = new JsonObject();
            params.addProperty("chatId", chatId);
            params.addProperty("cwd", root.getAbsolutePath());
            if (title != null && !title.trim().isEmpty()) {
                params.addProperty("title", title.trim());
            }
            if (task != null && !task.trim().isEmpty()) {
                params.addProperty("task", task);
            }
            JsonObject response = request("chat/create", params, 180000L);
            result.addProperty("ok", true);
            result.addProperty("chat_id", response.has("chatId") ? response.get("chatId").getAsString() : chatId);
            result.addProperty("title", response.has("title") ? response.get("title").getAsString()
                    : (title == null ? "" : title));
            result.addProperty("workspace", root.getAbsolutePath());
            result.addProperty("task_sent", task != null && !task.trim().isEmpty());
            if (task != null && !task.trim().isEmpty()) {
                result.addProperty("hint", "任务已投递；用 harness_latest 传 chat_id 读取输出与思维链。");
            }
        } catch (Throwable t) {
            result.addProperty("ok", false);
            result.addProperty("error", describe(t));
        }
        return result.toString();
    }

    /**
     * 向聊天投递任务。
     *
     * @param chatId      聊天 id
     * @param text        任务文本
     * @param waitSeconds 等待轮次结束的秒数，0 表示立即返回
     * @return JSON 文本
     */
    public String sendTask(String chatId, String text, int waitSeconds) {
        JsonObject result = new JsonObject();
        if (chatId == null || chatId.trim().isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "缺少 chat_id");
            return result.toString();
        }
        if (text == null || text.trim().isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "缺少任务内容 text");
            return result.toString();
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("chatId", chatId.trim());
            params.addProperty("text", text);
            JsonObject response = request("chat/send", params, 60000L);
            result.addProperty("ok", true);
            result.addProperty("chat_id", chatId.trim());
            result.addProperty("accepted", true);
            if (response.has("messageId")) {
                result.addProperty("message_id", response.get("messageId").getAsString());
            }
            int wait = Math.max(0, Math.min(waitSeconds, config.maxWaitSeconds));
            if (wait > 0) {
                JsonObject latest = awaitChange(chatId.trim(), -1, true, 20, wait * 1000L);
                result.add("latest", latest);
            } else {
                result.addProperty("hint", "任务已投递；用 harness_latest 传 chat_id 读取输出与思维链。");
            }
        } catch (Throwable t) {
            result.addProperty("ok", false);
            result.addProperty("error", describe(t));
        }
        return result.toString();
    }

    // ==================== 读取最新输出 ====================

    /**
     * 读取聊天最新输出。
     *
     * @param chatId         聊天 id
     * @param sinceSeq       上次读到的游标，可为空
     * @param includeReasoning 是否包含思维链
     * @param maxMessages    最多返回多少条消息
     * @param onlyIfChanged  没有新内容时是否只返回“未变更”
     * @param waitSeconds    等待新内容的秒数
     * @return JSON 文本或“未变更”
     */
    public String latest(String chatId, Long sinceSeq, boolean includeReasoning, int maxMessages,
            boolean onlyIfChanged, int waitSeconds) {
        JsonObject result = new JsonObject();
        if (chatId == null || chatId.trim().isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "缺少 chat_id");
            return result.toString();
        }
        try {
            int wait = Math.max(0, Math.min(waitSeconds, config.maxWaitSeconds));
            long deadline = System.currentTimeMillis() + wait * 1000L;
            JsonObject latest;
            while (true) {
                JsonObject params = new JsonObject();
                params.addProperty("chatId", chatId.trim());
                if (sinceSeq != null && sinceSeq.longValue() >= 0) {
                    params.addProperty("since", sinceSeq.longValue());
                }
                params.addProperty("includeReasoning", includeReasoning);
                params.addProperty("maxMessages", maxMessages <= 0 ? 20 : Math.min(maxMessages, 100));
                latest = request("chat/latest", params, 60000L);
                long cursor = JsonConfig.getLong(latest, "cursor");
                boolean hasNew = sinceSeq == null || cursor > sinceSeq.longValue();
                boolean hasPending = hasPending(chatId.trim());
                if (hasNew || hasPending || System.currentTimeMillis() >= deadline) {
                    break;
                }
                Thread.sleep(500L);
            }
            long cursor = JsonConfig.getLong(latest, "cursor");
            boolean unchanged = sinceSeq != null && cursor <= sinceSeq.longValue() && !hasPending(chatId.trim());
            if (unchanged && onlyIfChanged) {
                return "未变更";
            }
            result.addProperty("ok", true);
            result.addProperty("chat_id", chatId.trim());
            if (latest.has("title") && !latest.get("title").isJsonNull()) {
                result.addProperty("title", latest.get("title").getAsString());
            }
            result.addProperty("running", JsonConfig.getBool(latest, "running"));
            if (latest.has("status") && !latest.get("status").isJsonNull()) {
                result.addProperty("status", latest.get("status").getAsString());
            }
            result.addProperty("cursor", cursor);
            JsonArray items = latest.has("items") && latest.get("items").isJsonArray()
                    ? latest.getAsJsonArray("items") : new JsonArray();
            result.add("items", items);
            JsonArray tasks = pendingArray(chatId.trim());
            if (tasks.size() > 0) {
                result.add("pending", tasks);
                result.addProperty("hint", "有待回答的提问/审批：用 harness_feedback 传 task_id 反馈。");
            }
        } catch (Throwable t) {
            result.addProperty("ok", false);
            result.addProperty("error", describe(t));
        }
        return result.toString();
    }

    /** 等待聊天出现新内容（用于 sendTask 的可选等待）。 */
    private JsonObject awaitChange(String chatId, long since, boolean includeReasoning, int maxMessages,
            long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonObject latest = new JsonObject();
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("chatId", chatId);
                if (since >= 0) {
                    params.addProperty("since", since);
                }
                params.addProperty("includeReasoning", includeReasoning);
                params.addProperty("maxMessages", maxMessages);
                latest = bridge.request("chat/latest", params, 30000L);
                if (JsonConfig.getLong(latest, "cursor") > since && (since < 0 || JsonConfig.getLong(latest, "cursor") > since)) {
                    // 至少拿到一帧；running 为 true 时继续等到轮次结束
                    if (!JsonConfig.getBool(latest, "running")) {
                        return latest;
                    }
                }
            } catch (Throwable t) {
                latest = new JsonObject();
                latest.addProperty("error", describe(t));
                break;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return latest;
    }

    // ==================== 待处理任务与反馈 ====================

    /**
     * 列举待处理任务。
     *
     * @param chatId 聊天过滤，可为空
     * @param kind   question / approval / 空
     * @return JSON 文本
     */
    public String pending(String chatId, String kind) {
        return pending(chatId, kind, 0);
    }

    /**
     * 列举待处理任务，可等待一段时间。
     *
     * @param chatId      聊天过滤，可为空
     * @param kind        question / approval / 空
     * @param waitSeconds 等待新任务的秒数（0-25）
     * @return JSON 文本
     */
    public String pending(String chatId, String kind, int waitSeconds) {
        JsonObject result = new JsonObject();
        int wait = Math.max(0, Math.min(waitSeconds, config.maxWaitSeconds));
        JsonArray array;
        long deadline = System.currentTimeMillis() + wait * 1000L;
        synchronized (pendingLock) {
            while (true) {
                array = pendingArray(chatId);
                if (array.size() > 0 || System.currentTimeMillis() >= deadline) {
                    break;
                }
                try {
                    pendingLock.wait(Math.max(1L, Math.min(1000L, deadline - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (kind != null && !kind.trim().isEmpty() && !"all".equalsIgnoreCase(kind.trim())) {
            JsonArray filtered = new JsonArray();
            for (JsonElement element : array) {
                if (element.isJsonObject()
                        && kind.trim().equalsIgnoreCase(JsonConfig.getString(element.getAsJsonObject(), "kind"))) {
                    filtered.add(element);
                }
            }
            array = filtered;
        }
        result.addProperty("ok", true);
        result.addProperty("count", array.size());
        result.add("tasks", array);
        if (array.size() == 0) {
            result.addProperty("hint", "当前没有待回答的提问或审批。");
        } else {
            result.addProperty("hint", "用 harness_feedback 传 task_id；审批用 decision=allow/deny，提问用 text 或 selected。");
        }
        return result.toString();
    }

    private JsonArray pendingArray(String chatId) {
        JsonArray array = new JsonArray();
        long timeout = Math.max(config.approvalTimeoutMs, config.questionTimeoutMs);
        synchronized (pendingLock) {
            Iterator<Map.Entry<String, PendingInteraction>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                PendingInteraction item = iterator.next().getValue();
                if (item.expired(timeout)) {
                    iterator.remove();
                    continue;
                }
                if (chatId != null && !chatId.trim().isEmpty()
                        && !chatId.trim().equals(item.chatId)) {
                    continue;
                }
                array.add(item.toJson());
            }
        }
        return array;
    }

    private boolean hasPending(String chatId) {
        synchronized (pendingLock) {
            for (PendingInteraction item : pending.values()) {
                if (chatId == null || chatId.equals(item.chatId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 对提问或审批进行反馈。
     *
     * @param taskId   任务 id
     * @param decision 审批决定：allow / deny（也接受 允许/拒绝/同意/不同意）
     * @param text     提问的自由文本答案，或拒绝原因
     * @param selected 提问的选项标签，多个用 {@code |} 分隔
     * @param answers  完整答案 JSON（数组），可为空
     * @return JSON 文本
     */
    public String feedback(String taskId, String decision, String text, String selected, String answers) {
        JsonObject result = new JsonObject();
        if (taskId == null || taskId.trim().isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "缺少 task_id");
            return result.toString();
        }
        PendingInteraction item;
        synchronized (pendingLock) {
            item = pending.get(taskId.trim());
        }
        if (item == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "任务不存在、已超时或已被处理：" + taskId);
            return result.toString();
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("id", item.id);
            JsonElement answer;
            if ("approval".equalsIgnoreCase(item.kind)) {
                answer = new com.google.gson.JsonPrimitive(parseDecision(decision, text));
            } else {
                answer = buildQuestionAnswer(item, decision, text, selected, answers);
            }
            params.add("answer", answer);
            JsonObject response = request("interaction/answer", params, 30000L);
            boolean accepted = !response.has("accepted") || response.get("accepted").getAsBoolean();
            synchronized (pendingLock) {
                pending.remove(item.id);
            }
            if (!accepted) {
                result.addProperty("ok", false);
                result.addProperty("error", "任务已失效（DSH 已重启或已超时）：" + item.id);
                return result.toString();
            }
            result.addProperty("ok", true);
            result.addProperty("task_id", item.id);
            result.addProperty("kind", item.kind);
            result.addProperty("chat_id", item.chatId);
            result.addProperty("accepted", true);
            result.addProperty("message", "反馈已提交。");
        } catch (Throwable t) {
            result.addProperty("ok", false);
            result.addProperty("error", describe(t));
        }
        return result.toString();
    }

    /** 解析审批决定。 */
    private static String parseDecision(String decision, String text) {
        String value = decision == null ? "" : decision.trim().toLowerCase();
        if (value.isEmpty() && text != null) {
            value = text.trim().toLowerCase();
        }
        if (value.equals("allow") || value.equals("allowed") || value.equals("allowed-once")
                || value.equals("允许") || value.equals("同意") || value.equals("批准")
                || value.equals("yes") || value.equals("y") || value.equals("ok") || value.equals("1")) {
            return "allow";
        }
        if (value.equals("deny") || value.equals("reject") || value.equals("rejected")
                || value.equals("拒绝") || value.equals("不同意") || value.equals("驳回")
                || value.equals("no") || value.equals("n") || value.equals("2")) {
            return "deny";
        }
        // 无法识别时按拒绝处理，避免误放行
        return "deny";
    }

    /** 构造提问答案。 */
    private JsonElement buildQuestionAnswer(PendingInteraction item, String decision, String text,
            String selected, String answers) {
        if (answers != null && !answers.trim().isEmpty()) {
            try {
                JsonElement parsed = JsonParser.parseString(answers);
                if (parsed != null && parsed.isJsonArray()) {
                    JsonObject batch = new JsonObject();
                    batch.add("answers", parsed);
                    return batch;
                }
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed;
                }
            } catch (RuntimeException ignored) {
                // 不是 JSON 时按文本处理
            }
        }
        JsonArray answerArray = new JsonArray();
        String custom = text == null ? "" : text.trim();
        String[] labels = selected == null ? new String[0] : selected.split("\\|");
        if (item.questions.size() == 1 && item.questions.get(0).isJsonObject()) {
            JsonObject question = item.questions.get(0).getAsJsonObject();
            JsonObject answer = new JsonObject();
            answer.addProperty("id", JsonConfig.getString(question, "id"));
            JsonArray selectedArray = new JsonArray();
            if (labels.length > 0 && !labels[0].trim().isEmpty()) {
                for (String label : labels) {
                    if (!label.trim().isEmpty()) {
                        selectedArray.add(label.trim());
                    }
                }
            }
            answer.add("selected", selectedArray);
            if (!custom.isEmpty()) {
                answer.addProperty("custom", custom);
            }
            answerArray.add(answer);
        } else {
            for (JsonElement element : item.questions) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject question = element.getAsJsonObject();
                JsonObject answer = new JsonObject();
                answer.addProperty("id", JsonConfig.getString(question, "id"));
                answer.add("selected", new JsonArray());
                if (!custom.isEmpty()) {
                    answer.addProperty("custom", custom);
                }
                answerArray.add(answer);
            }
        }
        JsonObject batch = new JsonObject();
        batch.add("answers", answerArray);
        return batch;
    }

    // ==================== 工具方法 ====================

    /** 解析工作区：路径、名称或空（第一个）。 */
    private File resolveWorkspace(String value) {
        if (value == null || value.trim().isEmpty()) {
            return config.workspaces.isEmpty() ? null : config.workspaces.get(0);
        }
        String wanted = value.trim();
        for (File root : config.workspaces) {
            if (root.getPath().equalsIgnoreCase(wanted)
                    || root.getName().equalsIgnoreCase(wanted)) {
                return root;
            }
        }
        File direct = new File(wanted);
        if (direct.isAbsolute() && underAnyWorkspace(direct.getAbsolutePath())) {
            return direct;
        }
        return null;
    }

    private boolean matchesWorkspaceFilter(JsonObject chat, String cwd, String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return true;
        }
        String wanted = filter.trim();
        if (cwd.equalsIgnoreCase(wanted) || isUnder(cwd, normalizePath(wanted))) {
            return true;
        }
        for (File root : config.workspaces) {
            if ((root.getPath().equalsIgnoreCase(wanted) || root.getName().equalsIgnoreCase(wanted))
                    && isUnder(cwd, normalizePath(root.getAbsolutePath()))) {
                return true;
            }
        }
        return false;
    }

    private boolean underAnyWorkspace(String cwd) {
        String normalized = normalizePath(cwd);
        for (File root : config.workspaces) {
            if (isUnder(normalized, normalizePath(root.getAbsolutePath()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnder(String path, String parent) {
        if (path == null || parent == null) {
            return false;
        }
        String normalizedPath = normalizePath(path);
        String normalizedParent = normalizePath(parent);
        return normalizedPath.equals(normalizedParent)
                || normalizedPath.startsWith(normalizedParent + "/");
    }

    private static String normalizePath(String value) {
        String path = value.replace('\\', '/');
        while (path.contains("/./")) {
            path = path.replace("/./", "/");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getName() : message;
    }

    @Override
    public void close() {
        synchronized (pendingLock) {
            pending.clear();
        }
        session.close();
        bridge.close();
    }
}
