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

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * 多 agent 架构里的会话：公屏 agent 或某个用户的私人 agent。
 * <p>
 * 与普通 {@link AIGroupSession} 的区别：
 * </p>
 * <ul>
 *   <li>缓冲区**不设上限**（积压消息不会被丢弃）；</li>
 *   <li>每条进入缓冲区的消息立即追加到 {@code save/multi/buffers/*.jsonl}，重启后回放，
 *       方便序列化/恢复；</li>
 *   <li>可以带一段每个用户不同的系统提示词，在 {@link #getPrompt()} 里前置；</li>
 *   <li>私聊会话使用 {@code private:<user>} 作为 groupId，只注册私聊发送工具。</li>
 * </ul>
 */
public class MultiAgentSession extends AIGroupSession {

    /** 历史系统消息标记，用于重启时替换而不是重复。 */
    private static final String SYSTEM_MARKER = "<!-- multi-agent-system-prompt -->";
    /** 公屏会话（true）还是私人会话（false）。 */
    private final boolean publicAgent;
    /** 公屏群 id，或私聊用户 id。 */
    private final String peer;
    /** 该会话专属的系统提示词，可为空。 */
    private final String systemPrompt;
    /** 除自身群外还要接收的群。 */
    private final java.util.Set<String> contextGroups;
    /** 可移交/传递信息的 agent 名称集合（`*` 表示任意）。 */
    private final java.util.Set<String> handoverTargets;
    /** 是否只有被 @ 才触发。 */
    private final boolean mentionOnly;
    /** 缓冲区落盘文件，可为 null（不持久化）。 */
    private final File bufferFile;
    /** 回放缓冲区时避免再次追加落盘。 */
    private volatile boolean loading;

    public MultiAgentSession(String user, ISaveData data, File saveData, AIApplication aiapp,
            JsonObject config, boolean publicAgent, String peer, String systemPrompt, File bufferFile) {
        super(user, data, saveData, aiapp, config);
        this.publicAgent = publicAgent;
        this.peer = peer == null ? "" : peer;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        this.contextGroups = strings(config, "contextGroups");
        this.handoverTargets = strings(config, "handoverTargets");
        this.mentionOnly = config != null && config.has("mentionOnly")
                && !config.get("mentionOnly").isJsonNull() && config.get("mentionOnly").isJsonPrimitive()
                && config.get("mentionOnly").getAsBoolean();
        this.bufferFile = bufferFile;
        initSystemHistory();
    }

    /** 是否只有被 @ 才触发。 */
    public boolean isMentionOnly() {
        return mentionOnly;
    }

    /** 该会话是否应接收某个群的消息（自己的公屏群始终算）。 */
    public boolean canSeeGroup(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            return false;
        }
        if (publicAgent && peer.equals(groupId)) {
            return true;
        }
        return contextGroups.contains(groupId);
    }

    /** 该会话是否可以把信息交给指定 agent。 */
    public boolean canHandoverTo(String agentName) {
        return handoverTargets.contains("*")
                || (agentName != null && handoverTargets.contains(agentName));
    }

    private static java.util.Set<String> strings(JsonObject config, String key) {
        return new java.util.LinkedHashSet<>(JsonConfig.getStringList(config, key));
    }

    public boolean isPublicAgent() {
        return publicAgent;
    }

    public String getPeer() {
        return peer;
    }

    @Override
    public void addMessage(String messageId, List<Supplier<MessageContent>> msg, boolean important) {
        super.addMessage(messageId, msg, important);
        if (loading || bufferFile == null || msg == null || msg.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Supplier<MessageContent> supplier : msg) {
            try {
                MessageContent content = supplier.get();
                if (content != null) {
                    text.append(content.toText());
                }
            } catch (Throwable t) {
                // 内容加载失败时保留占位，不阻塞入队
                text.append("（内容加载失败）");
            }
        }
        appendBufferLine(messageId, text.toString());
    }

    @Override
    public MessageContents getPrompt() {
        MessageContents base = super.getPrompt();
        // 消息已经被 AI 取走：清掉落盘缓冲，避免重启后重复回放
        clearBuffer();
        // 只保留一小段身份提醒；完整设定已经写进对话开始时的系统记录
        MutableMessageContents result = new MutableMessageContents(
                "<system>先分清这条消息来自公屏还是私聊、是哪个用户，再按对应渠道和身份回复。</system>\n");
        for (MessageContent content : base) {
            result.add(content);
        }
        return result;
    }

    /** 把完整会话设定写进历史里的系统消息（只写一次，重启时更新）。 */
    private void initSystemHistory() {
        if (systemPrompt.isEmpty()) {
            return;
        }
        try {
            HistoryHolder history = getHistory();
            List<HistoryItem> old = new java.util.ArrayList<>();
            for (HistoryItem item : history) {
                if (item.getRole() == Role.SYSTEM && item.getDisplayContent() != null
                        && item.getDisplayContent().getPlainText().contains(SYSTEM_MARKER)) {
                    old.add(item);
                }
            }
            for (HistoryItem item : old) {
                history.removeOf(item.getIdentifier());
            }
            history.add(Role.SYSTEM, SYSTEM_MARKER + "\n" + systemPrompt, true);
        } catch (Throwable t) {
            System.out.println("[Multi] 写入系统提示词失败：" + t);
        }
    }

    /** 启动时把落盘缓冲区回放到会话队列。 */
    public void loadBuffer() {
        if (bufferFile == null || !bufferFile.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(bufferFile.toPath(), StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                String text = line;
                try {
                    JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                    if (json.has("text") && !json.get("text").isJsonNull()) {
                        text = json.get("text").getAsString();
                    }
                } catch (RuntimeException ignored) {
                    // 旧格式/坏行按原始文本回放
                }
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                final String content = text;
                loading = true;
                try {
                    addMessage(null, java.util.Collections.singletonList(
                            () -> new PlainText(content)), false);
                } finally {
                    loading = false;
                }
            }
            System.out.println("[Multi] 已回放缓冲区 " + bufferFile.getName());
            trimQueue(10);
        } catch (Throwable t) {
            System.out.println("[Multi] 回放缓冲区失败：" + t);
        }
    }

    private void appendBufferLine(String messageId, String text) {
        if (bufferFile == null) {
            return;
        }
        try {
            bufferFile.getParentFile().mkdirs();
            JsonObject line = new JsonObject();
            if (messageId != null) {
                line.addProperty("id", messageId);
            }
            line.addProperty("text", text);
            line.addProperty("time", System.currentTimeMillis());
            Files.write(bufferFile.toPath(),
                    (line.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.out.println("[Multi] 写缓冲区失败：" + e);
        }
    }

    private void clearBuffer() {
        if (bufferFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(bufferFile.toPath());
        } catch (IOException e) {
            System.out.println("[Multi] 清理缓冲区失败：" + e);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
