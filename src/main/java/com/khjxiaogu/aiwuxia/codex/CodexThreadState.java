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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 一个 Codex 线程（会话）的运行时状态。
 * <p>
 * Codex 的 thread 就是「一段可续聊的对话」，这里保存三类东西：
 * </p>
 * <ul>
 *   <li>线程元信息：id、工作目录、模型、是否已加载；</li>
 *   <li>事件流：把 {@code item/*}、{@code turn/*}、{@code error} 通知归一化成带序号的
 *       {@code seq} 事件，供上层用 cursor 增量读取（正文与推理按「同类型相邻合并」聚合）；</li>
 *   <li>待处理交互：Codex 发起的审批/提问，等待上层（QQ 管理员或 agent）答复。</li>
 * </ul>
 * <p>
 * 事件列表有上限，超出后丢弃最旧的；丢弃会造成 cursor 断档，读取时用 {@code truncated} 标记。
 * </p>
 */
final class CodexThreadState {

    /** 事件列表上限。 */
    private static final int MAX_EVENTS = 400;

    /** 线程 id。 */
    final String threadId;
    /** 归属键（QQ 会话、MCP 用户等），用于隔离不同来源的线程。 */
    final String owner;
    /** 工作目录。 */
    final String cwd;
    /** 创建时间。 */
    final long createdAt = System.currentTimeMillis();

    /** 模型 id（thread/start 返回）。 */
    volatile String model = "";
    /** 挂载的 Codex 项目 id（未挂项目时为空）。 */
    volatile String projectId = "";
    /** 是否正在跑一轮。 */
    volatile boolean running;
    /** 当前轮次 id。 */
    volatile String activeTurnId = "";
    /** 线程状态：idle / active / systemError / notLoaded。 */
    volatile String status = "idle";
    /** 最近一次错误。 */
    volatile String lastError = "";
    /** 是否已经在本进程里 thread/resume 过。 */
    volatile boolean loaded;
    /** 是否已从 Codex 持久化记录回放历史事件（续聊时供 AI 直接读取）。 */
    volatile boolean historyLoaded;
    /** 最后一次活动时间。 */
    volatile long lastActive = System.currentTimeMillis();
    /** 最近一次用量。 */
    volatile JsonObject usage;
    /** 最近结束的轮次 id。 */
    volatile String lastTurnId = "";
    /** 最近结束的轮次状态（completed / failed / interrupted）。 */
    volatile String lastTurnStatus = "";
    /** 最近结束轮次的错误。 */
    volatile String lastTurnError = "";
    /** 最近一条 assistant 正文（完成通知里给模型看的摘要）。 */
    volatile String lastAssistantText = "";

    private long cursor;
    private final List<JsonObject> events = new ArrayList<>();
    /** 最近几次工具活动的简短描述（完成通知里给模型看）。 */
    private final List<String> recentTools = new ArrayList<>();
    /** 本轮/本线程最近产生的图片（imageView / imageGeneration / MCP 图片结果）。 */
    private final List<JsonObject> images = new ArrayList<>();
    /** 图片去重键。 */
    private final java.util.Set<String> imageKeys = new java.util.LinkedHashSet<>();
    /** 本轮已经处理过的 markdown 图片路径。 */
    private final java.util.Set<String> scannedMarkdown = new java.util.LinkedHashSet<>();
    /** 本轮 markdown 图片的上传映射（路径 → 附件 id），完成通知里补在正文末尾。 */
    private final List<String> markdownNotes = new ArrayList<>();
    /** 待处理交互：taskId -> 交互。 */
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    CodexThreadState(String threadId, String owner, String cwd) {
        this.threadId = threadId;
        this.owner = owner;
        this.cwd = cwd;
        this.loaded = true;
    }

    /** 当前游标（最后一条事件的序号）。 */
    synchronized long getCursor() {
        return cursor;
    }

    /** 开始新一轮：清掉上一轮遗留的「运行中」标记与图片缓存。 */
    synchronized void beginTurn(String turnId) {
        running = true;
        activeTurnId = turnId == null ? "" : turnId;
        status = "active";
        clearImages();
        scannedMarkdown.clear();
        lastActive = System.currentTimeMillis();
    }

    /** 结束一轮。 */
    synchronized void endTurn() {
        running = false;
        activeTurnId = "";
        status = "idle";
        lastActive = System.currentTimeMillis();
    }

    /**
     * 正常结束一轮：记录状态与最终正文，供「完成后触发 AI」的完成通知使用。
     *
     * @param turnId 轮次 id
     * @param status completed / failed / interrupted
     * @param error  失败原因，可为空
     */
    synchronized void finishTurn(String turnId, String status, String error) {
        running = false;
        activeTurnId = "";
        this.status = "idle";
        this.lastTurnId = turnId == null ? "" : turnId;
        this.lastTurnStatus = status == null ? "completed" : status;
        this.lastTurnError = error == null ? "" : error;
        this.lastActive = System.currentTimeMillis();
    }

    /**
     * 新增一条事件。
     *
     * @param kind 事件类型：assistant / reasoning / tool / turn / error / status / approval / notice
     * @return 事件对象，已加入列表并分配 seq
     */
    synchronized JsonObject addEvent(String kind) {
        JsonObject event = new JsonObject();
        event.addProperty("seq", ++cursor);
        event.addProperty("kind", kind);
        event.addProperty("at", System.currentTimeMillis());
        events.add(event);
        trim();
        lastActive = System.currentTimeMillis();
        return event;
    }

    /**
     * 追加文本事件：与最后一条同类型事件合并，避免逐字增量把事件列表撑爆。
     *
     * @param kind 事件类型（assistant / reasoning）
     * @param text 增量文本
     * @param maxChars 单条事件的文本上限
     */
    synchronized void appendText(String kind, String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonObject last = events.isEmpty() ? null : events.get(events.size() - 1);
        if (last != null && kind.equals(JsonConfig.getString(last, "kind"))) {
            String existing = JsonConfig.getString(last, "text");
            String merged = (existing == null ? "" : existing) + text;
            if (merged.length() > maxChars) {
                merged = merged.substring(0, maxChars) + "…（已截断）";
                last.addProperty("truncated", true);
            }
            last.addProperty("text", merged);
            last.addProperty("at", System.currentTimeMillis());
            if ("assistant".equals(kind)) {
                lastAssistantText = merged;
            }
            lastActive = System.currentTimeMillis();
            return;
        }
        JsonObject event = addEvent(kind);
        String value = text.length() > maxChars ? text.substring(0, maxChars) + "…（已截断）" : text;
        event.addProperty("text", value);
        if ("assistant".equals(kind)) {
            lastAssistantText = value;
        }
        if (value.length() != text.length()) {
            event.addProperty("truncated", true);
        }
    }

    /**
     * 用权威文本替换最后一条同类型事件（Codex 的 item/completed 会给出完整正文）。
     *
     * @param kind 事件类型
     * @param text 完整文本
     * @param maxChars 文本上限
     */
    synchronized void replaceText(String kind, String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonObject last = events.isEmpty() ? null : events.get(events.size() - 1);
        if (last != null && kind.equals(JsonConfig.getString(last, "kind"))) {
            last.addProperty("text", text.length() > maxChars ? text.substring(0, maxChars) + "…（已截断）" : text);
            last.addProperty("at", System.currentTimeMillis());
            if ("assistant".equals(kind)) {
                lastAssistantText = JsonConfig.getString(last, "text");
            }
            return;
        }
        appendText(kind, text, maxChars);
    }

    /** 记录一次工具活动。 */
    synchronized void addTool(String tool, String summary, String status, String itemId, String turnId) {
        JsonObject event = addEvent("tool");
        event.addProperty("tool", tool == null ? "" : tool);
        event.addProperty("summary", summary == null ? "" : summary);
        event.addProperty("status", status == null ? "" : status);
        if (itemId != null) {
            event.addProperty("item_id", itemId);
        }
        if (turnId != null) {
            event.addProperty("turn_id", turnId);
        }
        String line = (tool == null ? "" : tool) + "：" + (summary == null ? "" : summary);
        recentTools.add(line.length() > 200 ? line.substring(0, 200) : line);
        while (recentTools.size() > 8) {
            recentTools.remove(0);
        }
    }

    /**
     * 轮次完成通知的载荷：状态、最终正文、错误、最近工具、待处理数量与游标。
     * <p>
     * 异步工具用它生成「后台任务完成」的结果，交给会话触发新一轮 AI 汇总。
     * </p>
     *
     * @return 载荷
     */
    synchronized JsonObject completionPayload() {
        JsonObject json = new JsonObject();
        json.addProperty("thread_id", threadId);
        json.addProperty("turn_id", lastTurnId);
        json.addProperty("status", lastTurnStatus == null || lastTurnStatus.isEmpty() ? "unknown" : lastTurnStatus);
        if (lastTurnError != null && !lastTurnError.isEmpty()) {
            json.addProperty("error", lastTurnError);
        }
        String text = lastAssistantText == null ? "" : lastAssistantText;
        if (!markdownNotes.isEmpty() && !text.contains("已上传为附件")) {
            StringBuilder footer = new StringBuilder("\n\n（已上传为附件：");
            for (int i = 0; i < markdownNotes.size(); i++) {
                if (i > 0) {
                    footer.append("；");
                }
                footer.append(markdownNotes.get(i));
            }
            footer.append("）");
            text = text + footer;
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000) + "…";
        }
        json.addProperty("text", text);
        json.addProperty("pending", pending.size());
        json.addProperty("cursor", cursor);
        JsonArray tools = new JsonArray();
        for (String line : recentTools) {
            tools.add(line);
        }
        json.add("recent_tools", tools);
        JsonArray imageArray = imageMeta();
        if (imageArray.size() > 0) {
            json.add("images", imageArray);
        }
        return json;
    }

    /**
     * 增量读取事件。
     *
     * @param since            上次读到的游标，null 或负数表示从头（受 maxItems 限制）
     * @param includeReasoning 是否包含推理事件
     * @param maxItems         最多返回多少条
     * @return 结果对象：items / cursor / truncated
     */
    synchronized JsonObject snapshot(Long since, boolean includeReasoning, int maxItems) {
        JsonArray items = new JsonArray();
        long from = since == null ? -1 : since.longValue();
        long firstAvailable = events.isEmpty() ? cursor : JsonConfig.getLong(events.get(0), "seq");
        boolean truncated = from >= 0 && from < firstAvailable - 1;
        long lastSeq = from;
        int emitted = 0;
        for (JsonObject event : events) {
            long seq = JsonConfig.getLong(event, "seq");
            if (seq <= from) {
                continue;
            }
            if (!includeReasoning && "reasoning".equals(JsonConfig.getString(event, "kind"))) {
                lastSeq = seq;
                continue;
            }
            if (emitted >= maxItems) {
                break;
            }
            items.add(event.deepCopy());
            emitted++;
            lastSeq = seq;
        }
        JsonObject result = new JsonObject();
        result.add("items", items);
        result.addProperty("cursor", lastSeq < 0 ? cursor : lastSeq);
        result.addProperty("latest_cursor", cursor);
        result.addProperty("truncated", truncated);
        return result;
    }

    /** 当前待处理交互数量。 */
    synchronized int pendingCount() {
        return pending.size();
    }

    /** 按 id 查待处理交互。 */
    synchronized Pending pendingOf(String taskId) {
        return pending.get(taskId);
    }

    /** 最早的待处理交互。 */
    synchronized Pending oldestPending() {
        Iterator<Pending> iterator = pending.values().iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    /** 按条件找最早的待处理交互。 */
    synchronized Pending oldestPending(String kind, String adminId) {
        for (Pending item : pending.values()) {
            if (kind != null && !kind.equals(item.kind)) {
                continue;
            }
            if (adminId != null && !adminId.isEmpty() && !adminId.equals(item.adminId)) {
                continue;
            }
            return item;
        }
        return null;
    }

    /** 加入一条待处理交互。 */
    synchronized void addPending(Pending item) {
        pending.put(item.taskId, item);
    }

    /** 移除一条待处理交互。 */
    synchronized Pending removePending(String taskId) {
        return pending.remove(taskId);
    }

    /** 待处理交互列表的快照（给模型看）。 */
    synchronized JsonArray pendingArray() {
        JsonArray array = new JsonArray();
        for (Pending item : pending.values()) {
            array.add(item.toJson());
        }
        return array;
    }

    /**
     * 记录一张图片（截图 / 生成图 / MCP 图片结果）。
     * <p>
     * 优先只存对象存储 id；没有存储时才退回 data URL（降级，仍能发到 QQ）。
     * 事件流里只放 {@code source/id/mime}，避免把 base64 塞进给模型看的 JSON。
     * </p>
     *
     * @param source  来源：view / generation / mcp
     * @param id      对象存储 id，可为空
     * @param mime    MIME 类型，可为空
     * @param dataUrl 降级用的 data URL，可为空
     * @param itemId  Codex 条目 id，用于去重，可为空
     * @param history 是否是历史回放（历史图片不再重复发到群里）
     */
    synchronized void addImage(String source, String id, String mime, String dataUrl, String itemId,
            boolean history) {
        String key = itemId != null && !itemId.isEmpty() ? "id:" + itemId
                : id != null && !id.isEmpty() ? "img:" + id
                : "data:" + (dataUrl == null ? "" : Integer.toHexString(dataUrl.hashCode()));
        // 同一个对象存储 id 只记一次（例如 markdown 路径与 imageView 指向同一张图）
        if (id != null && !id.isEmpty()) {
            for (JsonObject existing : images) {
                if (id.equals(JsonConfig.getString(existing, "id"))) {
                    return;
                }
            }
        }
        if (!imageKeys.add(key)) {
            return;
        }
        JsonObject image = new JsonObject();
        image.addProperty("source", source == null ? "" : source);
        if (id != null && !id.isEmpty()) {
            image.addProperty("id", id);
        }
        if (mime != null && !mime.isEmpty()) {
            image.addProperty("mime", mime);
        }
        if (dataUrl != null && !dataUrl.isEmpty()) {
            image.addProperty("data", dataUrl);
        }
        image.addProperty("history", history);
        images.add(image);
        while (images.size() > 6) {
            images.remove(0);
        }
        JsonObject event = addEvent("image");
        event.addProperty("source", image.get("source").getAsString());
        if (id != null && !id.isEmpty()) {
            event.addProperty("id", id);
        }
        if (mime != null && !mime.isEmpty()) {
            event.addProperty("mime", mime);
        }
        if (itemId != null && !itemId.isEmpty()) {
            event.addProperty("item_id", itemId);
        }
        if (history) {
            event.addProperty("history", true);
        }
    }

    /** 图片元信息（不含 base64），给模型/完成通知看。 */
    synchronized JsonArray imageMeta() {
        JsonArray array = new JsonArray();
        for (JsonObject image : images) {
            JsonObject copy = image.deepCopy();
            copy.remove("data");
            array.add(copy);
        }
        return array;
    }

    /** 图片完整载荷（含 data URL），给发送 QQ 用；历史回放的图片不重复发送。 */
    synchronized List<JsonObject> imagePayloads() {
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject image : images) {
            if (JsonConfig.getBool(image, "history")) {
                continue;
            }
            result.add(image.deepCopy());
        }
        return result;
    }

    /** 清空图片缓存（新一轮开始时调用，避免把上一轮截图再发一次）。 */
    synchronized void clearImages() {
        images.clear();
        imageKeys.clear();
        markdownNotes.clear();
    }

    /** 记录一条 markdown 图片上传映射。 */
    synchronized void addMarkdownNote(String note) {
        if (note != null && !note.isEmpty() && markdownNotes.size() < 8) {
            markdownNotes.add(note);
        }
    }

    /** 本轮是否第一次处理这个 markdown 图片路径。 */
    synchronized boolean claimMarkdown(String path) {
        return path != null && scannedMarkdown.add(path);
    }

    /** 记录一条历史轮次（回放用）。 */
    synchronized void markHistoryTurn(String turnId, String status) {
        lastTurnId = turnId == null ? "" : turnId;
        lastTurnStatus = status == null ? "completed" : status;
    }

    /** 回放结束：打一条分隔线，后续实时事件从新事件开始，也标记历史已载入。 */
    synchronized void finishHistorySeed() {
        addEvent("notice").addProperty("text", "—— 以上为历史记录（来自 Codex 会话）——");
        historyLoaded = true;
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /**
     * 一条等待答复的交互（审批或提问）。
     * <p>
     * 审批来自 {@code item/commandExecution/requestApproval} 与
     * {@code item/fileChange/requestApproval}；提问来自 {@code item/tool/requestUserInput}。
     * {@code rpcId} 必须原样回传，否则 Codex 会一直等下去。
     * </p>
     */
    static final class Pending {
        final String taskId;
        final String kind;
        /** 原始请求类型：command / file（预审转人工时用）。 */
        final String requestKind;
        final JsonElement rpcId;
        final String threadId;
        final String turnId;
        final String title;
        final String detail;
        final String adminId;
        /** 全部审核人；adminId 是其中的第一个，空串表示没有。 */
        final List<String> adminIds;
        final long createdAt = System.currentTimeMillis();
        /** 审批可选项（accept/acceptForSession/decline/cancel）。 */
        final JsonArray options;
        /** 提问的问题列表（仅 kind=question）。 */
        final JsonArray questions;
        /** 已答复标记，防止重复回包。 */
        boolean answered;

        Pending(String taskId, String kind, JsonElement rpcId, String threadId, String turnId,
                String title, String detail, String adminId, JsonArray options, JsonArray questions) {
            this(taskId, kind, null, rpcId, threadId, turnId, title, detail, adminId, options, questions);
        }

        Pending(String taskId, String kind, String requestKind, JsonElement rpcId, String threadId,
                String turnId, String title, String detail, String adminId, JsonArray options,
                JsonArray questions) {
            this(taskId, kind, requestKind, rpcId, threadId, turnId, title, detail,
                    adminId == null || adminId.isEmpty() ? java.util.Collections.<String>emptyList()
                            : java.util.Collections.singletonList(adminId),
                    options, questions);
        }

        Pending(String taskId, String kind, JsonElement rpcId, String threadId, String turnId,
                String title, String detail, List<String> adminIds, JsonArray options, JsonArray questions) {
            this(taskId, kind, null, rpcId, threadId, turnId, title, detail, adminIds, options, questions);
        }

        Pending(String taskId, String kind, String requestKind, JsonElement rpcId, String threadId,
                String turnId, String title, String detail, List<String> adminIds, JsonArray options,
                JsonArray questions) {
            this.taskId = taskId;
            this.kind = kind;
            this.requestKind = requestKind;
            this.rpcId = rpcId;
            this.threadId = threadId;
            this.turnId = turnId;
            this.title = title;
            this.detail = detail;
            this.adminIds = java.util.Collections.unmodifiableList(
                    adminIds == null ? new ArrayList<String>() : new ArrayList<>(adminIds));
            String first = "";
            for (String id : this.adminIds) {
                if (id != null && !id.isEmpty()) {
                    first = id;
                    break;
                }
            }
            this.adminId = first;
            this.options = options == null ? new JsonArray() : options;
            this.questions = questions == null ? new JsonArray() : questions;
        }

        /** 转成给模型/管理员看的摘要。 */
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("task_id", taskId);
            json.addProperty("kind", kind);
            json.addProperty("thread_id", threadId);
            if (turnId != null && !turnId.isEmpty()) {
                json.addProperty("turn_id", turnId);
            }
            if (title != null && !title.isEmpty()) {
                json.addProperty("title", title);
            }
            if (detail != null && !detail.isEmpty()) {
                json.addProperty("detail", detail);
            }
            if (options.size() > 0) {
                json.add("options", options);
            }
            if (questions.size() > 0) {
                json.add("questions", questions);
            }
            json.addProperty("created_at", createdAt);
            return json;
        }
    }
}
