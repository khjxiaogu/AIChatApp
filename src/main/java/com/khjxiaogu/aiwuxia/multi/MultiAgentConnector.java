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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.NapCatAIConnector;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.chat.ChatAdapter;
import com.khjxiaogu.aiwuxia.chat.IncomingContent;
import com.khjxiaogu.aiwuxia.chat.IncomingMessage;
import com.khjxiaogu.aiwuxia.chat.NapcatAdapter;
import com.khjxiaogu.aiwuxia.chat.QqOfficialAdapter;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.mcp.chat.PrivateChatHost;
import com.khjxiaogu.aiwuxia.mcp.chat.PrivateChatMcp;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;

/**
 * 多 agent 架构：一个公屏总览 agent（每个公开群一个）+ 每个私聊用户一个私人 agent。
 * <p>
 * 路由规则：
 * </p>
 * <ul>
 *   <li>配置里的公开群消息：追加到**所有** agent 的缓冲区，并触发该群的公屏 agent；</li>
 *   <li>配置里的私聊用户消息：追加并触发该用户的私人 agent；</li>
 *   <li>私聊接管（白名单、防抖、路由落盘、移交）也在这里；基类只管公屏；</li>
 *   <li>公屏/私聊都使用显式发送工具：公屏 {@code send_group}，私聊 {@code send_private}；</li>
 *   <li>缓冲区不设上限，并落盘到 {@code save/multi/buffers/}，重启可回放；</li>
 *   <li>私聊 agent 默认审核人 = 对应用户；公屏 agent 默认审核人 = 配置的 {@code adminId}。</li>
 * </ul>
 * <p>
 * 配置见 {@link MultiAgentConfig}（{@code save/config/multi.json}）。协议层由
 * {@link #createAdapter(File, MultiAgentConfig)} 决定：配置了 {@code qqbotAppId}
 * （或 {@code -Dqqbot_appid}）时走 QQ 官方机器人，否则走 NapCat。
 * </p>
 */
public class MultiAgentConnector extends NapCatAIConnector implements PrivateChatHost {

    private final MultiAgentConfig multi;
    private final File dataFolder;
    private final File appFolder;
    /** 私聊路由：`botId:qq` → 接管该用户的 agent（send_private / handover_private 之后自动接管）。 */
    private final Map<String, PrivateRoute> privateRoutes = new ConcurrentHashMap<>();
    /** 私聊防抖缓冲区：连续私聊合并成一次触发。 */
    private final Map<String, PrivateBuffer> privateBuffers = new ConcurrentHashMap<>();
    /** 会话当前私聊对象：send_private / handover_private 省略 user 时用。 */
    private final Map<AIGroupSession, String> privatePeers = new ConcurrentHashMap<>();
    /** 私聊用户显示名：用于该用户的 agent 在公屏发言时的署名。 */
    private final Map<String, String> privateUserNames = new ConcurrentHashMap<>();
    /** 私聊防抖调度器。 */
    private final ScheduledExecutorService privateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "multi-private");
        thread.setDaemon(true);
        return thread;
    });
    /** 私聊路由与临时白名单的落盘文件。 */
    private final File privateStateFile;
    /** 默认私聊防抖：10 秒。 */
    private static final long DEFAULT_PRIVATE_DEBOUNCE_MS = 10_000L;
    /** 默认临时白名单有效期：24 小时。 */
    private static final long DEFAULT_PRIVATE_WHITELIST_TTL_MS = 86_400_000L;
    /** 同一媒体 URL 只下载/上传一次（公屏广播给多个 agent 时复用）。 */
    private final Map<String, String> mediaCache = new ConcurrentHashMap<>();
    /** 每个公开群最近 200 条消息，供 agent 按需查询。 */
    private static final int GROUP_HISTORY_LIMIT = 200;
    /** 公屏 agent 默认只保留最近 10 条上下文。 */
    private static final int PUBLIC_CONTEXT_LIMIT = 10;
    private final Map<String, Deque<GroupEntry>> groupHistory = new ConcurrentHashMap<>();
    /** 每个 agent 对某个群最后一次取到的消息 id，用于“有更新”提醒。 */
    private final Map<String, String> lastGroupSeen = new ConcurrentHashMap<>();
    /** 公屏防抖：至少 10 条且间隔 5 秒，或 20 秒强制触发一次。 */
    private static final int PUBLIC_BATCH_MIN_MESSAGES = 10;
    private static final long PUBLIC_BATCH_MIN_INTERVAL_MS = 5000L;
    private static final long PUBLIC_BATCH_MAX_INTERVAL_MS = 20000L;
    /** @ 消息统一等待 2 秒再回复；期间再次 @ 不重复触发。 */
    private static final long PUBLIC_AT_DEBOUNCE_MS = 2000L;
    private final ScheduledExecutorService publicDebounce = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "multi-public-debounce");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, PublicBatch> publicBatches = new ConcurrentHashMap<>();
    /** 该群是否正在跑一轮 AI；期间新消息先累积，等本轮结束再计算防抖。 */
    private final Map<String, Boolean> publicRunning = new ConcurrentHashMap<>();
    /** 当前 AI 轮次的历史截止时间：只允许查到提交请求之前的内容。 */
    private final Map<AIGroupSession, Long> historyCutoff = new ConcurrentHashMap<>();

    private static final class PublicBatch {
        MultiAgentSession agent;
        int count;
        long firstAt;
        ScheduledFuture<?> timer;
        ScheduledFuture<?> atTimer;
        boolean atPending;

        PublicBatch(MultiAgentSession agent) {
            this.agent = agent;
        }
    }

    private static final class GroupEntry {
        final String id;
        final String senderId;
        final String senderName;
        final String text;
        final long time;

        GroupEntry(String id, String senderId, String senderName, String text) {
            this.id = id;
            this.senderId = senderId;
            this.senderName = senderName;
            this.text = text;
            this.time = System.currentTimeMillis();
        }
    }

    /** 私聊路由：`botId:qq` → 接管该用户的 agent。 */
    private static final class PrivateRoute {
        final String agent;
        final long expireAt;

        PrivateRoute(String agent, long expireAt) {
            this.agent = agent;
            this.expireAt = expireAt;
        }
    }

    /** 私聊防抖缓冲里的一条消息（按协议消息 id 保存，便于撤回删除）。 */
    private static final class PrivateEntry {
        final String messageId;
        final String text;

        PrivateEntry(String messageId, String text) {
            this.messageId = messageId;
            this.text = text;
        }
    }

    /** 私聊防抖缓冲：连续私聊合并成一次触发。 */
    private static final class PrivateBuffer {
        final AIGroupSession state;
        final String user;
        final String name;
        final List<PrivateEntry> entries = new ArrayList<>();
        ScheduledFuture<?> timer;

        PrivateBuffer(AIGroupSession state, String user, String name) {
            this.state = state;
            this.user = user;
            this.name = name;
        }
    }

    public MultiAgentConnector(File dataFolder, ChatAdapter adapter, MultiAgentConfig multi)
            throws IOException, InterruptedException {
        super(dataFolder, adapter, createSessions(dataFolder, multi));
        this.multi = multi;
        this.dataFolder = dataFolder;
        this.appFolder = resolveAppFolder(dataFolder, multi.app);
        this.privateStateFile = new File(new File(dataFolder, "config"), "private-chat.json");
        loadPrivateState();
        // 启动时确保所有已配置私聊会话都已创建（不依赖用户先发第一条私聊）
        for (MultiAgentConfig.Session session : multi.privateSessions) {
            if (session.enabled) {
                ensurePrivateSession(session.id);
            }
        }
        System.out.println("[Multi] 协议适配器：" + getAdapter().getName());
        System.out.println("[Multi] 已启动：公屏会话 " + multi.publicSessions.size()
                + " 个，私聊会话 " + multi.privateSessions.size() + " 个");
    }

    /**
     * 按用户 id 找私聊会话；没有且允许自动创建时，用默认配置创建一个。
     */
    private synchronized MultiAgentSession ensurePrivateSession(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        String id = userId.trim();
        for (AIGroupSession session : getSessions()) {
            if (session instanceof MultiAgentSession) {
                MultiAgentSession agent = (MultiAgentSession) session;
                if (!agent.isPublicAgent() && agent.getPeer().equals(id)) {
                    return agent;
                }
            }
        }
        if (!isPrivateUser(id) && !multi.autoCreatePrivate) {
            return null;
        }
        try {
            List<String> extraTools = new ArrayList<>();
            List<String> contextGroups = Collections.emptyList();
            boolean hasTemplate = false;
            for (MultiAgentConfig.Session template : multi.privateSessions) {
                for (String tool : template.extraTools) {
                    if (!extraTools.contains(tool)) {
                        extraTools.add(tool);
                    }
                }
                if (!hasTemplate) {
                    contextGroups = template.contextGroups;
                    hasTemplate = true;
                }
            }
            String name = "multi-private-" + id;
            MultiAgentConfig.Session session = new MultiAgentConfig.Session(id, false, true, name, name,
                    extraTools, multi.defaultCodex.deepCopy(), null, Collections.singletonList(id),
                    contextGroups, Collections.<String>emptyList(), false, "", "");
            JsonObject config = baseConfig(multi, session, "private:" + id, name, name,
                    Collections.singletonList(id), multi.toolsFor(session), multi.codexFor(session));
            config.addProperty("explicitSend", true);
            config.addProperty("privateChat", true);
            config.addProperty("privateChatMode", "private");
            JsonArray whitelist = new JsonArray();
            whitelist.add(id);
            config.add("privateWhitelist", whitelist);
            config.addProperty("personalKnowledge",
                    new File(new File(dataFolder, "multi"), "knowledge/" + id).getAbsolutePath());
            JsonObject codex = multi.codexFor(session);
            addKnowledgeRoot(config, codex);
            String prompt = systemPrompt(multi.commonPrompt, multi.privatePromptFor(session),
                    projectInfo(codex)
                            + "你正在和用户 " + id + " 私聊，只处理这个用户。回复必须调用 "
                            + "send_private(user=\"" + id + "\", text=...)，正文不会自动发出。");
            MultiAgentSession agent = newSession(dataFolder, appFolder, config, id, false, id,
                    prompt, bufferFile(dataFolder, "private-" + id));
            getSessions().add(agent);
            System.out.println("[Multi] 自动创建私人会话：" + id);
            return agent;
        } catch (Throwable t) {
            System.out.println("[Multi] 自动创建私人会话失败：" + id + "，" + t);
            return null;
        }
    }

    private boolean isPublicGroup(String groupId) {
        for (MultiAgentConfig.Session session : multi.publicSessions) {
            if (session.enabled && session.id.equals(groupId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrivateUser(String userId) {
        for (MultiAgentConfig.Session session : multi.privateSessions) {
            if (session.enabled && session.id.equals(userId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean handleMultiGroupMessage(IncomingMessage msg) {
        if (multi == null) {
            return false;
        }
        String groupId = msg.getChatId();
        if (!isPublicGroup(groupId)) {
            return false;
        }
        // 机器人自己发出的消息不再回灌，避免自激
        if (multi.botId.equals(msg.getSenderId())) {
            return true;
        }
        String text = buildBody(msg);
        recordGroup(groupId, msg.getMessageId(), msg.getSenderId(), msg.getSenderName(), text);
        MultiAgentSession target = findPublicAgent(groupId);
        for (AIGroupSession session : getSessions()) {
            if (!(session instanceof MultiAgentSession)) {
                continue;
            }
            MultiAgentSession agent = (MultiAgentSession) session;
            if (!agent.canSeeGroup(groupId)) {
                continue;
            }
            String line = groupUpdateNotice(agent, groupId) + "<message senderName=\""
                    + escape(msg.getSenderName()) + "\" senderId=\"" + escape(msg.getSenderId())
                    + "\" id=\"" + escape(msg.getMessageId()) + "\">"
                    + text + "</message>";
            agent.addMessage(msg.getMessageId(),
                    Collections.singletonList(() -> new PlainText(line)), false);
            agent.trimQueue(PUBLIC_CONTEXT_LIMIT);
        }
        if (target != null) {
            boolean mentioned = msg.isMentionsBot() || msg.isMentioned(multi.botId);
            if (!mentioned) {
                try {
                    mentioned = msg.getPlainText().contains("@" + target.getRoleName(Role.ASSISTANT));
                } catch (Throwable ignored) {
                    // 角色名取不到就只看 mention id / 适配器判定
                }
            }
            // @ 机器人必须回复；其他消息按 10 条/5 秒或 20 秒防抖，避免每条都触发
            schedulePublicAgent(target, groupId, mentioned);
        }
        return true;
    }

    /**
     * 公屏防抖：@ 立即触发；否则至少累计 10 条且间隔 5 秒，或者距首条满 20 秒触发一次。
     */
    private void schedulePublicAgent(MultiAgentSession agent, String groupId, boolean mentioned) {
        PublicBatch batch = publicBatches.computeIfAbsent(groupId, key -> new PublicBatch(agent));
        synchronized (batch) {
            batch.agent = agent;
            batch.count++;
            long now = System.currentTimeMillis();
            if (batch.firstAt == 0) {
                batch.firstAt = now;
            }
            // AI 还没跑完：只累积，等 onAiTurnFinished 后再开始计算防抖
            if (Boolean.TRUE.equals(publicRunning.get(groupId))) {
                if (mentioned) {
                    batch.atPending = true;
                }
                return;
            }
            if (!mentioned && agent.isMentionOnly()) {
                // 只 @ 才回复：普通消息只进上下文，不启动任何防抖/触发
                return;
            }
            if (mentioned) {
                batch.atPending = true;
                // @ 只防抖 2 秒：第一次 @ 安排 2 秒后统一回复，期间再次 @ 不重复触发
                if (batch.atTimer == null || batch.atTimer.isDone()) {
                    if (batch.timer != null) {
                        batch.timer.cancel(false);
                        batch.timer = null;
                    }
                    batch.atTimer = publicDebounce.schedule(() -> flushPublicBatch(groupId, batch),
                            PUBLIC_AT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                }
                return;
            }
            if (batch.atTimer != null && !batch.atTimer.isDone()) {
                // 已经在等 @ 的 2 秒窗口，普通消息只累积，不再触发
                return;
            }
            if (shouldTriggerPublic(batch, now)) {
                flushPublicBatch(groupId, batch);
                return;
            }
            if (batch.timer == null || batch.timer.isDone()) {
                batch.timer = publicDebounce.schedule(() -> checkPublicBatch(groupId),
                        PUBLIC_BATCH_MIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private boolean shouldTriggerPublic(PublicBatch batch, long now) {
        long elapsed = now - batch.firstAt;
        return (batch.count >= PUBLIC_BATCH_MIN_MESSAGES && elapsed >= PUBLIC_BATCH_MIN_INTERVAL_MS)
                || elapsed >= PUBLIC_BATCH_MAX_INTERVAL_MS;
    }

    private void checkPublicBatch(String groupId) {
        if (Boolean.TRUE.equals(publicRunning.get(groupId))) {
            return;
        }
        PublicBatch batch = publicBatches.get(groupId);
        if (batch == null) {
            return;
        }
        synchronized (batch) {
            if (batch.atTimer != null && !batch.atTimer.isDone()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (shouldTriggerPublic(batch, now)) {
                flushPublicBatch(groupId, batch);
                return;
            }
            long elapsed = now - batch.firstAt;
            long delay = batch.count >= PUBLIC_BATCH_MIN_MESSAGES
                    ? Math.max(1000L, PUBLIC_BATCH_MIN_INTERVAL_MS - elapsed)
                    : Math.max(1000L, PUBLIC_BATCH_MAX_INTERVAL_MS - elapsed);
            batch.timer = publicDebounce.schedule(() -> checkPublicBatch(groupId), delay, TimeUnit.MILLISECONDS);
        }
    }

    /** 触发一轮公屏 AI；提交时停止本群所有防抖，并记录历史截止时间。 */
    private void flushPublicBatch(String groupId, PublicBatch batch) {
        synchronized (batch) {
            publicBatches.remove(groupId, batch);
            if (batch.timer != null) {
                batch.timer.cancel(false);
                batch.timer = null;
            }
            if (batch.atTimer != null) {
                batch.atTimer.cancel(false);
                batch.atTimer = null;
            }
            batch.atPending = false;
            if (batch.agent != null) {
                publicRunning.put(groupId, true);
                historyCutoff.put(batch.agent, System.currentTimeMillis());
                submitMessage(batch.agent);
            }
        }
    }

    /** 一轮 AI 结束：如果期间有新消息，从这一刻开始重新计算防抖。 */
    @Override
    protected void onAiTurnFinished(AIGroupSession state) {
        if (!(state instanceof MultiAgentSession) || !((MultiAgentSession) state).isPublicAgent()) {
            return;
        }
        MultiAgentSession agent = (MultiAgentSession) state;
        String groupId = agent.getPeer();
        publicRunning.remove(groupId);
        historyCutoff.remove(state);
        PublicBatch batch = publicBatches.get(groupId);
        if (batch == null) {
            return;
        }
        synchronized (batch) {
            batch.agent = agent;
            if (batch.count <= 0) {
                publicBatches.remove(groupId, batch);
                return;
            }
            if (agent.isMentionOnly() && !batch.atPending) {
                // 只 @ 才回复：本轮期间没有 @ 就不启动防抖
                publicBatches.remove(groupId, batch);
                return;
            }
            // 新一轮从 AI 结束这一刻开始计时
            batch.firstAt = System.currentTimeMillis();
            if (batch.atTimer != null) {
                batch.atTimer.cancel(false);
                batch.atTimer = null;
            }
            if (batch.timer != null) {
                batch.timer.cancel(false);
                batch.timer = null;
            }
            if (batch.atPending) {
                batch.atTimer = publicDebounce.schedule(() -> flushPublicBatch(groupId, batch),
                        PUBLIC_AT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            } else {
                long delay = batch.count >= PUBLIC_BATCH_MIN_MESSAGES
                        ? PUBLIC_BATCH_MIN_INTERVAL_MS : PUBLIC_BATCH_MAX_INTERVAL_MS;
                batch.timer = publicDebounce.schedule(() -> checkPublicBatch(groupId), delay,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    /** 找某个公开群的公屏 agent。 */
    private MultiAgentSession findPublicAgent(String groupId) {        for (AIGroupSession session : getSessions()) {
            if (session instanceof MultiAgentSession) {
                MultiAgentSession agent = (MultiAgentSession) session;
                if (agent.isPublicAgent() && agent.getPeer().equals(groupId)) {
                    return agent;
                }
            }
        }
        return null;
    }

    /** 群有更新时在消息开头提醒群 id + 名称；首次见到该群时不提醒。 */
    private String groupUpdateNotice(AIGroupSession agent, String groupId) {
        String key = System.identityHashCode(agent) + ":" + groupId;
        boolean updated = lastGroupSeen.containsKey(key);
        lastGroupSeen.put(key, groupId + "@" + System.currentTimeMillis());
        return updated
                ? "<group-update group_id=\"" + escape(groupId) + "\" name=\""
                        + escape(groupName(groupId)) + "\" />\n"
                : "";
    }

    /** 群名称：优先公屏会话配置名，其次群 id。 */
    private String groupName(String groupId) {
        for (MultiAgentConfig.Session session : multi.publicSessions) {
            if (session.id.equals(groupId) && !session.name.isEmpty()) {
                return session.name;
            }
        }
        return groupId;
    }

    private void recordGroup(String groupId, String id, String senderId, String senderName, String text) {
        Deque<GroupEntry> queue = groupHistory.computeIfAbsent(groupId,
                key -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        queue.addLast(new GroupEntry(id, senderId, senderName, text));
        while (queue.size() > GROUP_HISTORY_LIMIT) {
            queue.pollFirst();
        }
    }

    /**
     * 按需查询某个群的历史（最多 200 条）；有更新时在结果开头提醒群 id/名称。
     */
    public String groupHistory(AIGroupSession from, String groupId, int limit, String keyword) {
        if (groupId == null || groupId.trim().isEmpty()) {
            return "缺少群 id（group_id）";
        }
        if (from instanceof MultiAgentSession && !((MultiAgentSession) from).canSeeGroup(groupId)) {
            return "当前会话没有该群的上下文权限，不能查询：" + groupId;
        }
        if (!isPublicGroup(groupId)) {
            return "不是已配置的公开群：" + groupId;
        }
        Deque<GroupEntry> queue = groupHistory.get(groupId);
        if (queue == null || queue.isEmpty()) {
            return "群 " + groupId + "（" + groupName(groupId) + "）暂时没有可查询的历史消息。";
        }
        int max = Math.max(1, Math.min(GROUP_HISTORY_LIMIT, limit <= 0 ? 20 : limit));
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        Long cutoff = historyCutoff.get(from);
        List<GroupEntry> selected = new ArrayList<>();
        for (GroupEntry entry : queue) {
            if (cutoff != null && entry.time > cutoff.longValue()) {
                continue;
            }
            if (needle.isEmpty() || entry.text.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                selected.add(entry);
            }
        }
        if (selected.size() > max) {
            selected = new ArrayList<>(selected.subList(selected.size() - max, selected.size()));
        }
        if (selected.isEmpty()) {
            return "群 " + groupId + "（" + groupName(groupId) + "）没有匹配「" + keyword + "」的历史消息。";
        }
        String key = "query:" + System.identityHashCode(from) + ":" + groupId;
        String last = lastGroupSeen.put(key, selected.get(selected.size() - 1).id);
        StringBuilder text = new StringBuilder();
        if (last != null && !last.equals(selected.get(selected.size() - 1).id)) {
            text.append("<group-update group_id=\"").append(escape(groupId)).append("\" name=\"")
                    .append(escape(groupName(groupId))).append("\" />\n");
        }
        text.append("群 ").append(groupId).append("（").append(groupName(groupId)).append("）最近 ")
                .append(selected.size()).append(" 条：\n");
        for (GroupEntry entry : selected) {
            String who = entry.senderName == null || entry.senderName.trim().isEmpty()
                    ? (entry.senderId == null ? "" : entry.senderId)
                    : (entry.senderId == null || entry.senderId.isEmpty()
                            ? entry.senderName
                            : entry.senderName + "(" + entry.senderId + ")");
            text.append("- ").append(who).append("：").append(entry.text).append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 任意 agent 成功向公屏发送消息后：记入该群历史；如果发送者是私人 agent，再补到该群公屏
     * agent 的上下文（私人 agent 之间不互相同步公屏内容）。
     */
    @Override
    protected void onMultiGroupMessageSent(AIGroupSession from, String text) {
        onMultiGroupMessageSent(from, from == null ? null : from.groupId, text);
    }

    @Override
    protected void onMultiGroupMessageSent(AIGroupSession from, String groupId, String text) {
        if (multi == null || from == null || text == null || text.trim().isEmpty()) {
            return;
        }
        if (groupId == null || groupId.startsWith("private:")) {
            return;
        }
        String name;
        String body = text;
        if (from instanceof MultiAgentSession && !((MultiAgentSession) from).isPublicAgent()) {
            // 私聊 agent 的公屏发言：正文已带「<用户名>的agent：」前缀，上下文里改用 senderName
            // 表示署名，避免同一条消息里前缀出现两次
            String label = privateAgentLabel(((MultiAgentSession) from).getPeer(), groupId);
            String prefix = label + "：";
            if (body.startsWith(prefix)) {
                body = body.substring(prefix.length()).trim();
            }
            name = label;
        } else {
            try {
                name = from.getRoleName(Role.ASSISTANT);
            } catch (Throwable t) {
                name = from.getAiapp().getName();
            }
        }
        if (body.isEmpty()) {
            return;
        }
        String id = "sent-" + System.nanoTime();
        recordGroup(groupId, id, from.botId, name, body);
        String line = "<message senderName=\"" + escape(name) + "\" senderId=\""
                + escape(from.botId) + "\" self=\"true\">" + escape(body) + "</message>";
        // 让配置了该群上下文的会话（如第一个群的私聊）也看到公屏发言；只记录，不额外触发
        for (AIGroupSession session : getSessions()) {
            if (session == from || !(session instanceof MultiAgentSession)) {
                continue;
            }
            MultiAgentSession agent = (MultiAgentSession) session;
            if (!agent.canSeeGroup(groupId)) {
                continue;
            }
            agent.addMessage(id, Collections.singletonList(() -> new PlainText(line)), false);
            agent.trimQueue(PUBLIC_CONTEXT_LIMIT);
        }
    }

    @Override
    protected boolean handleMultiPrivateMessage(IncomingMessage msg) {
        // 构造阶段（super 还没返回）先不接管：私聊状态尚未初始化
        if (multi == null || privateRoutes == null) {
            return false;
        }
        String userId = msg.getSenderId();
        if (multi.botId.equals(userId)) {
            return true;
        }
        rememberPrivateUserName(userId, msg.getSenderName());
        if (!isPrivateUser(userId) && ensurePrivateSession(userId) == null) {
            // 配置之外的私聊：可能是之前 send_private / handover_private 临时接管过的用户
            return handleRoutedPrivateMessage(msg);
        }
        MultiAgentSession target = null;
        for (AIGroupSession session : getSessions()) {
            if (session instanceof MultiAgentSession) {
                MultiAgentSession agent = (MultiAgentSession) session;
                if (!agent.isPublicAgent() && agent.getPeer().equals(userId)) {
                    target = agent;
                    break;
                }
            }
        }
        if (target == null) {
            return handleRoutedPrivateMessage(msg);
        }
        String text = buildBody(msg);
        String line = "<private user=\"" + escape(userId) + "\" name=\"" + escape(msg.getSenderName())
                + "\">" + text + "</private>";
        target.addMessage(msg.getMessageId(), Collections.singletonList(() -> new PlainText(line)), false);
        setCurrentPrivatePeer(target, userId);
        submitMessage(target);
        return true;
    }

    // ==================== 私聊路由 / 私聊接管 ====================

    /** 会话是否启用私聊（显式模式 + privateChat）。 */
    private boolean privateEnabled(AIGroupSession state) {
        return state.isExplicitSend() && JsonConfig.getBool(state.config, "privateChat", true);
    }

    /**
     * 私聊兜底路由：不是配置里的私聊用户，但之前被 send_private / handover_private 临时接管过的用户，
     * 按路由或白名单触发对应 agent（带防抖，连续私聊合并成一次）。
     *
     * @param msg 私聊消息
     * @return true 表示已接管
     */
    private boolean handleRoutedPrivateMessage(IncomingMessage msg) {
        String text = msg.getPlainText();
        String userId = msg.getSenderId();
        if (text == null || text.trim().isEmpty() || userId == null || userId.isEmpty()) {
            return false;
        }
        rememberPrivateUserName(userId, msg.getSenderName());
        final String selfId = msg.getSelfId().isEmpty() ? null : msg.getSelfId();
        AIGroupSession target = null;
        // 路由优先：按 `botId:qq` 找到之前接管的 agent；路由过期就顺手清掉
        String routeKey = key(selfId, userId);
        PrivateRoute route = privateRoutes.get(routeKey);
        if (route != null) {
            if (route.expireAt > 0 && route.expireAt < System.currentTimeMillis()) {
                privateRoutes.remove(routeKey);
                savePrivateState();
            } else {
                for (AIGroupSession state : getSessions()) {
                    if (state.botId.equals(selfId) && agentName(state).equalsIgnoreCase(route.agent)) {
                        if (privateEnabled(state)) {
                            target = state;
                        }
                        break;
                    }
                }
            }
        }
        // 没有可用路由时按白名单找：取第一个显式私聊且把该用户列进 privateWhitelist 的会话
        if (target == null) {
            for (AIGroupSession state : getSessions()) {
                if (!state.botId.equals(selfId) || !privateEnabled(state)) {
                    continue;
                }
                boolean whitelisted = false;
                JsonArray whitelist = JsonConfig.getArray(state.config, "privateWhitelist");
                if (whitelist != null) {
                    for (JsonElement element : whitelist) {
                        if (element != null && element.isJsonPrimitive()
                                && element.getAsString().trim().equals(userId)) {
                            whitelisted = true;
                            break;
                        }
                    }
                }
                if (whitelisted) {
                    rememberPrivateContact(state, userId);
                    target = state;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        final AIGroupSession session = target;
        privatePeers.put(session, userId);
        String bufferKey = key(selfId, userId);
        PrivateBuffer buffer = privateBuffers.computeIfAbsent(bufferKey,
                k -> new PrivateBuffer(session, userId, msg.getSenderName() == null ? "" : msg.getSenderName()));
        long debounce = Math.max(1000,
                JsonConfig.getLong(session.config, "privateDebounceMs", DEFAULT_PRIVATE_DEBOUNCE_MS));
        synchronized (buffer) {
            buffer.entries.add(new PrivateEntry(msg.getMessageId(), text));
            if (buffer.timer != null) {
                buffer.timer.cancel(false);
            }
            // debounce 到期：把累计的私聊合并成一条消息投给 agent
            buffer.timer = privateScheduler.schedule(() -> {
                PrivateBuffer flushed = privateBuffers.remove(bufferKey);
                if (flushed == null) {
                    return;
                }
                String joinedText;
                synchronized (flushed) {
                    StringBuilder joined = new StringBuilder();
                    for (PrivateEntry entry : flushed.entries) {
                        if (joined.length() > 0) {
                            joined.append('\n');
                        }
                        joined.append(entry.text);
                    }
                    joinedText = joined.toString();
                    flushed.timer = null;
                }
                if (joinedText.isEmpty()) {
                    return;
                }
                AIGroupSession state = flushed.state;
                privatePeers.put(state, flushed.user);
                String prompt = "<private user=\"" + flushed.user + "\" name=\"" + escape(flushed.name)
                        + "\">\n" + joinedText + "\n</private>";
                try {
                    state.addMessage(Collections.singletonList(() -> new PlainText(prompt)), true);
                    submitMessage(state, "private:" + state.botId + ":" + flushed.user);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }, debounce, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    @Override
    protected void handleMultiPrivateRecall(String userId, String messageId) {
        if (privateBuffers == null || userId == null || userId.isEmpty() || messageId == null
                || messageId.isEmpty()) {
            return;
        }
        // 私聊撤回：从防抖缓冲区移除；删空则本轮不再触发
        boolean removed = false;
        for (Map.Entry<String, PrivateBuffer> item : privateBuffers.entrySet()) {
            PrivateBuffer buffer = item.getValue();
            if (!userId.equals(buffer.user)) {
                continue;
            }
            boolean empty;
            synchronized (buffer) {
                java.util.Iterator<PrivateEntry> iterator = buffer.entries.iterator();
                removed = false;
                while (iterator.hasNext()) {
                    if (messageId.equals(iterator.next().messageId)) {
                        iterator.remove();
                        removed = true;
                    }
                }
                empty = buffer.entries.isEmpty();
                if (empty && buffer.timer != null) {
                    buffer.timer.cancel(false);
                    buffer.timer = null;
                }
            }
            if (empty) {
                privateBuffers.remove(item.getKey(), buffer);
            }
            break;
        }
        if (removed) {
            System.out.println("[Multi] 撤回私聊已从缓冲区移除：" + messageId + "（账号 " + userId + "）");
        }
    }

    @Override
    protected ToolProvider createChatTools(AIGroupSession state, JsonObject config) {
        // 显式发送模式：私聊/公屏都由工具发出，正文不自动发送
        if (!JsonConfig.getBool(config, "privateChat", true)) {
            return PrivateChatMcp.groupOnly(this, state);
        }
        if ("private".equalsIgnoreCase(JsonConfig.getNonBlankString(config, "privateChatMode", "both"))) {
            return PrivateChatMcp.privateOnly(this, state);
        }
        return PrivateChatMcp.privateAndGroup(this, state);
    }

    @Override
    public String currentPrivatePeer(AIGroupSession state) {
        String peer = privatePeers.get(state);
        return peer == null ? "" : peer;
    }

    public void setCurrentPrivatePeer(AIGroupSession state, String user) {
        if (state != null && user != null && !user.trim().isEmpty()) {
            privatePeers.put(state, user.trim());
        }
    }

    @Override
    public String sendExplicitPrivate(AIGroupSession state, String user, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("私聊内容为空");
        }
        if (user == null || user.trim().isEmpty()) {
            throw new IllegalArgumentException("私聊对象为空");
        }
        String target = user.trim();
        String mid = getAdapter().sendPrivateMessage(target, text).get(60, TimeUnit.SECONDS);
        privatePeers.put(state, target);
        rememberPrivateContact(state, target);
        appendMid(mid, state.botId);
        recordSentMessage(state, mid);
        return "已私聊发送给 " + target + (mid == null || mid.isEmpty() ? "" : "，消息id：" + mid);
    }

    /**
     * 私聊 agent 往公屏发消息时，在正文前加「{@code <用户名>的agent：}」署名，让群成员和其他
     * agent 知道这是哪个用户的 agent 在说话；公屏 agent 的发言不受影响。
     */
    @Override
    public String sendExplicitGroupTo(AIGroupSession state, String groupId, String text) throws Exception {
        return super.sendExplicitGroupTo(state, groupId, decoratePrivateAgentGroupText(state, groupId, text));
    }

    private String decoratePrivateAgentGroupText(AIGroupSession state, String groupId, String text) {
        if (text == null || text.trim().isEmpty() || groupId == null || groupId.startsWith("private:")) {
            return text;
        }
        if (!(state instanceof MultiAgentSession) || ((MultiAgentSession) state).isPublicAgent()) {
            return text;
        }
        String user = ((MultiAgentSession) state).getPeer();
        if (user == null || user.isEmpty()) {
            return text;
        }
        String prefix = privateAgentLabel(user, groupId) + "：";
        return text.startsWith(prefix) ? text : prefix + text;
    }

    /** 私聊用户在公屏上的署名：{@code <用户名>的agent}，查不到昵称时退回账号 id。 */
    private String privateAgentLabel(String userId, String groupId) {
        return privateUserName(userId, groupId) + "的agent";
    }

    /**
     * 私聊用户的显示名：优先用私聊消息里见过的昵称/群名片，其次问适配器（群名片 &gt; 昵称），
     * 都查不到时退回账号 id。结果会缓存，避免每次发言都查一次。
     */
    private String privateUserName(String userId, String groupId) {
        if (userId == null || userId.isEmpty()) {
            return "";
        }
        String known = privateUserNames.get(userId);
        if (known != null && !known.isEmpty()) {
            return known;
        }
        try {
            String name = getAdapter().getDisplayName(userId, groupId).get(2, TimeUnit.SECONDS);
            if (name != null && !name.trim().isEmpty()) {
                String value = name.trim();
                privateUserNames.put(userId, value);
                return value;
            }
        } catch (Throwable ignored) {
            // 适配器查不到（例如官方 openid 不在该群命名空间）就退回账号 id
        }
        privateUserNames.put(userId, userId);
        return userId;
    }

    /** 记住私聊用户的显示名，用于他名下的 agent 在公屏发言时的署名。 */
    private void rememberPrivateUserName(String userId, String name) {
        if (userId == null || userId.isEmpty() || name == null) {
            return;
        }
        String value = name.trim();
        if (!value.isEmpty()) {
            privateUserNames.put(userId, value);
        }
    }

    @Override
    public String sendTaskToAgent(AIGroupSession from, String targetAgent, String task) {
        if (task == null || task.trim().isEmpty()) {
            return "缺少任务内容 task";
        }
        String target = targetAgent == null ? "" : targetAgent.trim();
        if (target.isEmpty()) {
            return "缺少目标 agent";
        }
        AIGroupSession to = null;
        for (AIGroupSession candidate : getSessions()) {
            if (candidate == from || !(candidate instanceof MultiAgentSession)) {
                continue;
            }
            if (agentName(candidate).equalsIgnoreCase(target) && allowsHandover(from, agentName(candidate))) {
                boolean candidatePrivate = candidate.groupId != null && candidate.groupId.startsWith("private:");
                if (from.groupId != null && from.groupId.startsWith("private:") && !candidatePrivate
                        && from instanceof MultiAgentSession
                        && !((MultiAgentSession) from).canSeeGroup(candidate.groupId)) {
                    continue;
                }
                if (!candidatePrivate && !candidate.groupId.equals(from.groupId)
                        && from instanceof MultiAgentSession
                        && !((MultiAgentSession) from).canSeeGroup(candidate.groupId)) {
                    continue;
                }
                to = candidate;
                break;
            }
        }
        if (to == null) {
            return "找不到可接收任务的 agent「" + target + "」";
        }
        String line = "<agent-task from=\"" + escape(agentName(from)) + "\">\n" + task.trim()
                + "\n</agent-task>";
        to.addMessage(Collections.singletonList(() -> new PlainText(line)), true);
        submitMessage(to);
        return "已把任务交给 " + agentName(to);
    }

    @Override
    public String listContactableAgents(AIGroupSession from, String user) {
        String peer = user == null || user.trim().isEmpty() ? currentPrivatePeer(from) : user.trim();
        String card = groupCard(from, peer);
        List<String> names = new ArrayList<>();
        for (AIGroupSession candidate : getSessions()) {
            if (candidate == from) {
                continue;
            }
            boolean isPrivate = candidate.groupId != null && candidate.groupId.startsWith("private:");
            if (isPrivate && !privateEnabled(candidate)) {
                continue;
            }
            boolean sameGroup = candidate.groupId.equals(from.groupId);
            if (!sameGroup && !allowsHandover(from, agentName(candidate))) {
                continue;
            }
            // 私聊 agent 不能联系它看不到的群 agent，保持群隔离
            if (from.groupId != null && from.groupId.startsWith("private:") && !isPrivate
                    && from instanceof MultiAgentSession
                    && !((MultiAgentSession) from).canSeeGroup(candidate.groupId)) {
                continue;
            }
            if (!isPrivate && !sameGroup && from instanceof MultiAgentSession
                    && !((MultiAgentSession) from).canSeeGroup(candidate.groupId)) {
                continue;
            }
            names.add(agentName(candidate));
        }
        StringBuilder text = new StringBuilder("可联络的 agent");
        if (!peer.isEmpty()) {
            text.append("（用户 ").append(peer);
            if (!card.isEmpty()) {
                text.append("，群名片：").append(card);
            }
            text.append("）");
        }
        text.append("：");
        if (names.isEmpty()) {
            return text.append("无").toString();
        }
        for (String name : names) {
            text.append("\n- ").append(name);
        }
        return text.toString();
    }

    /** 查用户在来源群里的群名片；查不到返回空串。 */
    private String groupCard(AIGroupSession from, String userId) {
        if (from == null || userId == null || userId.trim().isEmpty()) {
            return "";
        }
        String groupId = from.groupId;
        if (groupId == null || groupId.startsWith("private:")) {
            return "";
        }
        try {
            String name = getAdapter().getGroupMemberName(groupId, userId).get(2, TimeUnit.SECONDS);
            return name == null ? "" : name.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 该会话是否允许把信息交给指定 agent（`handoverTargets` 里的 `*` 表示任意）。 */
    private static boolean allowsHandover(AIGroupSession from, String targetName) {
        JsonArray targets = from == null ? null : JsonConfig.getArray(from.config, "handoverTargets");
        if (targets == null) {
            return false;
        }
        for (JsonElement element : targets) {
            if (element != null && element.isJsonPrimitive()) {
                String target = element.getAsString();
                if ("*".equals(target) || (targetName != null && targetName.equals(target))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String handoverPrivate(AIGroupSession from, String targetAgent, String user, String summary)
            throws Exception {
        if (user == null || user.trim().isEmpty()) {
            return "缺少要移交的私聊对象 user";
        }
        // 目标用户可能还没有私聊会话：先按默认配置自动建一个，再走正常移交
        ensurePrivateSession(user);
        String peer = user.trim();
        String card = groupCard(from, peer);
        AIGroupSession target = null;
        List<String> available = new ArrayList<>();
        boolean fromPublic = from.groupId != null && !from.groupId.startsWith("private:");
        for (AIGroupSession candidate : getSessions()) {
            if (candidate == from) {
                continue;
            }
            boolean sameGroup = candidate.groupId.equals(from.groupId);
            boolean publicToPrivate = fromPublic && candidate.groupId.startsWith("private:");
            if (!sameGroup && !publicToPrivate) {
                continue;
            }
            if (publicToPrivate && !allowsHandover(from, agentName(candidate))) {
                continue;
            }
            if (!privateEnabled(candidate)) {
                continue;
            }
            String name = agentName(candidate);
            available.add(card.isEmpty() ? name : name + "（群名片：" + card + "）");
            if (name.equalsIgnoreCase(targetAgent == null ? "" : targetAgent.trim())) {
                target = candidate;
            }
        }
        if (target == null) {
            return "没有找到同群可接手的 agent「" + targetAgent + "」；可用："
                    + (available.isEmpty() ? "无" : String.join("、", available));
        }
        rememberPrivateContact(target, peer);
        privatePeers.put(target, peer);
        privateRoutes.put(key(from.botId, peer), new PrivateRoute(agentName(target),
                System.currentTimeMillis() + JsonConfig.getLong(target.config, "privateWhitelistTtlMs",
                        DEFAULT_PRIVATE_WHITELIST_TTL_MS)));
        savePrivateState();
        String notice = "<private-handover from=\"" + escape(agentName(from)) + "\" user=\"" + peer + "\">\n"
                + "摘要：" + summary + "\n"
                + "请接手这个用户的私聊，用 send_private 联系对方。\n"
                + "</private-handover>";
        target.addMessage(Collections.singletonList(() -> new PlainText(notice)), true);
        submitMessage(target);
        return "已移交给 " + agentName(target) + "，之后该用户的私聊由它接手。";
    }

    /** 记录/刷新私聊路由与临时白名单。 */
    private void rememberPrivateContact(AIGroupSession state, String qq) {
        long ttl = Math.max(60000L,
                JsonConfig.getLong(state.config, "privateWhitelistTtlMs", DEFAULT_PRIVATE_WHITELIST_TTL_MS));
        privateRoutes.put(key(state.botId, qq),
                new PrivateRoute(agentName(state), System.currentTimeMillis() + ttl));
        savePrivateState();
    }

    private static String key(String botId, String qq) {
        return botId + ":" + qq;
    }

    // ==================== 私聊路由持久化 ====================

    private void loadPrivateState() {
        try {
            if (!privateStateFile.isFile()) {
                return;
            }
            JsonObject root = JsonParser.parseString(FileUtil.readString(privateStateFile)).getAsJsonObject();
            if (root.has("routes") && root.get("routes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("routes").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject item = entry.getValue().getAsJsonObject();
                    String agent = item.has("agent") ? item.get("agent").getAsString() : "";
                    long expireAt = item.has("expireAt") ? item.get("expireAt").getAsLong() : 0L;
                    if (!agent.isEmpty() && (expireAt <= 0 || expireAt > System.currentTimeMillis())) {
                        privateRoutes.put(entry.getKey(), new PrivateRoute(agent, expireAt));
                    }
                }
            }
            System.out.println("[Multi] 已载入 " + privateRoutes.size() + " 条私聊路由");
        } catch (Throwable t) {
            System.out.println("[Multi] 载入私聊路由失败：" + t);
        }
    }

    private synchronized void savePrivateState() {
        try {
            long now = System.currentTimeMillis();
            JsonObject routes = new JsonObject();
            for (Map.Entry<String, PrivateRoute> entry : privateRoutes.entrySet()) {
                PrivateRoute route = entry.getValue();
                if (route.expireAt > 0 && route.expireAt < now) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("agent", route.agent);
                item.addProperty("expireAt", route.expireAt);
                routes.add(entry.getKey(), item);
            }
            JsonObject root = new JsonObject();
            root.add("routes", routes);
            privateStateFile.getParentFile().mkdirs();
            FileUtil.transfer(root.toString(), privateStateFile);
        } catch (Throwable t) {
            System.out.println("[Multi] 保存私聊路由失败：" + t);
        }
    }

    // ==================== 媒体 ====================

    /**
     * 把入站内容拼成文本：文本原样、图片下载到对象存储后用 {@code <image id="..."/>} 表示，
     * 下载失败时退回占位文字。
     */
    private String buildBody(IncomingMessage msg) {
        StringBuilder text = new StringBuilder();
        for (IncomingContent content : msg.getContents()) {
            if (content instanceof IncomingContent.TextBlock) {
                text.append(escape(((IncomingContent.TextBlock) content).getText()));
            } else if (content instanceof IncomingContent.ImageBlock) {
                IncomingContent.ImageBlock image = (IncomingContent.ImageBlock) content;
                String id = storeMedia(image.getUrl());
                if (id != null && !id.isEmpty()) {
                    text.append("<image id=\"").append(escape(id)).append("\" />");
                } else {
                    text.append("（").append(escape(image.getSummary())).append("）");
                }
            } else if (content instanceof IncomingContent.QuoteBlock) {
                String quoted = ((IncomingContent.QuoteBlock) content).getText();
                if (quoted != null && !quoted.trim().isEmpty()) {
                    text.append("（引用：").append(escape(quoted)).append("）");
                }
            }
        }
        String body = text.toString().trim();
        return body.isEmpty() ? "（图片或媒体消息）" : body;
    }

    /**
     * 下载媒体并保存到对象存储，返回图片 id；同一 URL 只处理一次。
     *
     * @param url 协议给的媒体引用（http/data/base64 等）
     * @return 对象存储 id；失败返回 null
     */
    private String storeMedia(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String cached = mediaCache.get(url);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] data = getAdapter().fetchMedia(url);
            if (data == null || data.length == 0) {
                return null;
            }
            AIGroupSession usageState = getSessions().isEmpty() ? null : getSessions().get(0);
            String id = getStorage().uploadIfNotExists(data,
                    usageState == null ? usage -> { } : usageState::addUsage);
            if (id != null && !id.isEmpty()) {
                mediaCache.put(url, id);
                return id;
            }
        } catch (Throwable t) {
            System.out.println("[Multi] 媒体下载/存储失败：" + t);
        }
        return null;
    }

    // ==================== 会话构建 ====================

    private static AIGroupSession[] createSessions(File dataFolder, MultiAgentConfig multi) throws IOException {
        if (multi == null) {
            throw new IOException("多 agent 配置为空");
        }
        try {
            File appFolder = resolveAppFolder(dataFolder, multi.app);
            List<AIGroupSession> sessions = new ArrayList<>();

            for (MultiAgentConfig.Session session : multi.publicSessions) {
                if (!session.enabled) {
                    continue;
                }
                String groupId = session.id;
                String key = "public-" + groupId;
                String saveName = session.save.isEmpty() ? "multi-" + key : session.save;
                JsonObject codex = multi.codexFor(session);
                JsonObject config = baseConfig(multi, session, groupId, saveName,
                        session.name.isEmpty() ? saveName : session.name, session.adminIds,
                        multi.toolsFor(session), codex);
                config.addProperty("explicitSend", true);
                config.addProperty("privateChat", false);
                addKnowledgeRoot(config, codex);

                String prompt = systemPrompt(multi.commonPrompt, multi.promptFor(session),
                        projectInfo(codex)
                                + "你是群 " + groupId + " 的公屏总览 agent。群聊内容会累积到你的缓冲区；"
                                + "需要发言时调用 send_group(text) 发到本群，正文不会自动发出。");
                MultiAgentSession agent = newSession(dataFolder, appFolder, config, groupId, true,
                        groupId, prompt, bufferFile(dataFolder, key));
                sessions.add(agent);
            }

            for (MultiAgentConfig.Session session : multi.privateSessions) {
                if (!session.enabled) {
                    continue;
                }
                String userId = session.id;
                String key = "private-" + userId;
                String saveName = session.save.isEmpty() ? "multi-" + key : session.save;
                JsonObject codex = multi.codexFor(session);
                JsonObject config = baseConfig(multi, session, "private:" + userId, saveName,
                        session.name.isEmpty() ? saveName : session.name, session.adminIds,
                        multi.toolsFor(session), codex);
                config.addProperty("explicitSend", true);
                config.addProperty("privateChat", true);
                config.addProperty("privateChatMode", "private");
                JsonArray whitelist = new JsonArray();
                whitelist.add(userId);
                config.add("privateWhitelist", whitelist);
                // 每个私人 agent 一个可写个人知识库
                config.addProperty("personalKnowledge",
                        new File(new File(dataFolder, "multi"), "knowledge/" + userId).getAbsolutePath());
                addKnowledgeRoot(config, codex);

                String prompt = systemPrompt(multi.commonPrompt, multi.privatePromptFor(session),
                        projectInfo(codex)
                                + "你正在和 QQ " + userId + " 私聊，只处理这个用户。回复必须调用 "
                                + "send_private(user=\"" + userId + "\", text=...)，正文不会自动发出。");
                MultiAgentSession agent = newSession(dataFolder, appFolder, config, userId, false,
                        userId, prompt, bufferFile(dataFolder, key));
                sessions.add(agent);
                System.out.println("[Multi] 私聊会话已创建：" + agent.getPeer() + "（" + saveName + "）");
            }
            return sessions.toArray(new AIGroupSession[0]);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("创建多 agent 会话失败：" + t, t);
        }
    }

    private static MultiAgentSession newSession(File dataFolder, File appFolder, JsonObject config,
            String peer, boolean publicAgent, String peerId, String prompt, File bufferFile) throws Throwable {
        String saveName = config.get("save").getAsString();
        File saveData = new File(new File(dataFolder, "saveData"), saveName);
        AIApplication app = AIApplicationRegistry.createInstance(dataFolder, appFolder);
        MultiAgentSession session = new MultiAgentSession("appuser", AIApplication.saveDataFromJson(saveData),
                saveData, app, config, publicAgent, peerId, prompt, bufferFile);
        session.loadBuffer();
        return session;
    }

    private static JsonObject baseConfig(MultiAgentConfig multi, MultiAgentConfig.Session session,
            String groupId, String saveName, String name, List<String> adminIds, List<String> tools,
            JsonObject codex) {
        JsonObject config = new JsonObject();
        config.addProperty("botId", multi.botId);
        config.addProperty("groupId", groupId);
        config.addProperty("name", name);
        config.addProperty("save", saveName);
        if (adminIds != null && adminIds.size() == 1) {
            config.addProperty("adminId", adminIds.get(0));
        } else if (adminIds != null && adminIds.size() > 1) {
            JsonArray admins = new JsonArray();
            for (String admin : adminIds) {
                admins.add(admin);
            }
            config.add("adminId", admins);
        }
        JsonArray toolArray = new JsonArray();
        if (tools != null) {
            for (String tool : tools) {
                toolArray.add(tool);
            }
        }
        config.add("tools", toolArray);
        JsonArray contextGroups = new JsonArray();
        if (session != null) {
            for (String group : session.contextGroups) {
                contextGroups.add(group);
            }
        }
        config.add("contextGroups", contextGroups);
        JsonArray handoverTargets = new JsonArray();
        if (session != null) {
            for (String target : session.handoverTargets) {
                handoverTargets.add(target);
            }
        }
        config.add("handoverTargets", handoverTargets);
        config.addProperty("mentionOnly", session != null && session.mentionOnly);
        if (codex != null && codex.size() > 0) {
            config.add("codex", codex.deepCopy());
        }
        if (session != null && session.docLibrary != null && session.docLibrary.size() > 0) {
            config.add("docLibrary", session.docLibrary.deepCopy());
        }
        return config;
    }

    /** 把项目 cwd 交给知识库工具使用（即使没启用 codex，也能用 kb_search/kb_read）。 */
    private static void addKnowledgeRoot(JsonObject config, JsonObject codex) {
        if (codex == null || !codex.has("cwd") || codex.get("cwd").isJsonNull()) {
            return;
        }
        String cwd = codex.get("cwd").getAsString();
        if (cwd != null && !cwd.trim().isEmpty()) {
            config.addProperty("knowledgeRoot", cwd.trim());
        }
    }

    /** 合并全局 codex 与某个用户的覆盖配置（如不同的 cwd/project）。 */
    private static JsonObject mergeCodex(JsonObject base, JsonObject override) {
        JsonObject merged = base == null ? new JsonObject() : base.deepCopy();
        if (override != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : override.entrySet()) {
                merged.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return merged;
    }

    /** 把当前会话的项目路径/项目名写进系统提示词，让模型知道自己在哪个项目里工作。 */
    private static String projectInfo(JsonObject codex) {
        if (codex == null) {
            return "";
        }
        String cwd = JsonConfig.getString(codex, "cwd");
        String project = JsonConfig.getString(codex, "project");
        String projectId = JsonConfig.getString(codex, "projectId");
        StringBuilder text = new StringBuilder();
        if (cwd != null && !cwd.isEmpty()) {
            text.append("当前项目路径：").append(cwd).append('\n');
        }
        if (project != null && !project.isEmpty()) {
            text.append("Codex 项目名：").append(project).append('\n');
        }
        if (projectId != null && !projectId.isEmpty()) {
            text.append("Codex 项目 id：").append(projectId).append('\n');
        }
        return text.length() == 0 ? "" : text.append('\n').toString();
    }

    private static String joinPrompt(String head, String tail) {
        String first = head == null ? "" : head.trim();
        String second = tail == null ? "" : tail.trim();
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return first + "\n\n" + second;
    }

    /** 组合系统提示词：公共总提示词 + 角色提示词 + 渠道说明。 */
    private static String systemPrompt(String common, String role, String tail) {
        return joinPrompt(joinPrompt(common, role), tail);
    }

    private static File bufferFile(File dataFolder, String key) {
        return new File(new File(new File(dataFolder, "multi"), "buffers"), key + ".jsonl");
    }

    /** 选择一个已存在的 app 目录（{@code save/apps/<app>/meta.json} 必须存在）。 */
    private static File resolveAppFolder(File dataFolder, String appName) throws IOException {
        File apps = new File(dataFolder, "apps");
        File folder = new File(apps, appName);
        if (new File(folder, "meta.json").isFile()) {
            return folder;
        }
        StringBuilder available = new StringBuilder();
        File[] dirs = apps.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                if (new File(dir, "meta.json").isFile()) {
                    if (available.length() > 0) {
                        available.append("、");
                    }
                    available.append(dir.getName());
                }
            }
        }
        throw new IOException("找不到 app「" + appName + "」，请把 multi.json 的 app 配成现有 app 目录"
                + (available.length() == 0 ? "（save/apps 下没有可用 app）" : "：" + available));
    }

    /**
     * 按 {@code multi.json} / 系统属性选择协议适配器。
     * <p>
     * 配了 {@code qqbotAppId}（或启动参数 {@code -Dqqbot_appid}）时使用 QQ 官方机器人 API v2，
     * 否则使用 NapCat（{@code napcatUrl}/{@code napcatToken}）。
     * </p>
     *
     * @param dataFolder 运行数据目录（官方适配器用它初始化对象存储）
     * @param config     多 agent 配置，可为 null（此时只用系统属性）
     * @return 协议适配器
     */
    public static ChatAdapter createAdapter(File dataFolder, MultiAgentConfig config) {
        String appId = config == null ? "" : config.qqbotAppId;
        if (appId == null || appId.trim().isEmpty()) {
            appId = System.getProperty("qqbot_appid", "");
        }
        appId = appId == null ? "" : appId.trim();
        if (!appId.isEmpty()) {
            String secret = config == null ? "" : config.qqbotSecret;
            if (secret == null || secret.trim().isEmpty()) {
                secret = System.getProperty("qqbot_secret", "");
            }
            return new QqOfficialAdapter(appId, secret == null ? "" : secret, dataFolder);
        }
        String url = config == null ? System.getProperty("napcat_url", "") : config.napcatUrl;
        String token = config == null ? System.getProperty("napcat_token", "") : config.napcatToken;
        return new NapcatAdapter(url, token, dataFolder);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 独立入口：读取 {@code save/config/multi.json} 并启动多 agent 连接器。 */
    public static void main(String[] args) throws Throwable {
        com.khjxiaogu.aiwuxia.utils.SiteConfig.applyArgs(args);
        LLMConnector.initDefault();
        File dataFolder = new File("save");
        VoiceModelHandler.init(dataFolder);
        MultiAgentConfig config = MultiAgentConfig.load(dataFolder);
        if (config == null) {
            System.out.println("[Multi] 未找到可用的 save/config/multi.json");
            return;
        }
        ChatAdapter adapter = createAdapter(dataFolder, config);
        new MultiAgentConnector(dataFolder, adapter, config);
    }
}
