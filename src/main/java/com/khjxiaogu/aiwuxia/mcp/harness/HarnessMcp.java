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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;

/**
 * 把 DeepSeek Harness 的工作区、聊天、任务、思维链与审批暴露成 MCP 工具。
 * <p>
 * 推荐使用顺序：
 * </p>
 * <ol>
 *   <li>{@code harness_workspaces} 看有哪些工作区；</li>
 *   <li>{@code harness_chats} 看工作区里的聊天（也可直接 {@code harness_new_chat} 建一个）；</li>
 *   <li>{@code harness_send} 投递任务，然后反复 {@code harness_latest} 读取输出/思维链；</li>
 *   <li>返回值里出现 {@code pending} 时，用 {@code harness_feedback} 回答 DSH 的提问或审批。</li>
 * </ol>
 * <p>
 * {@code harness_latest} 的 {@code since} 参数来自上一次返回的 {@code cursor}；当没有新内容时
 * （且 {@code only_if_changed} 不为 false）只返回“未变更”，避免重复灌入整段聊天。
 * </p>
 */
public class HarnessMcp implements ToolProvider {

    private final HarnessMcpService service;

    /**
     * @param service Harness MCP 服务
     */
    public HarnessMcp(HarnessMcpService service) {
        this.service = service;
    }

    /** 底层服务。 */
    public HarnessMcpService getService() {
        return service;
    }

    @Override
    public Collection<ToolData> getTools() {
        List<ToolData> tools = new ArrayList<>(7);
        tools.add(workspaces());
        tools.add(chats());
        tools.add(newChat());
        tools.add(send());
        tools.add(latest());
        tools.add(pending());
        tools.add(feedback());
        return tools;
    }

    /** 列举配置的工作区。 */
    private ToolData workspaces() {
        return new ToolData.Builder("harness_workspaces",
                "列举可用的 DeepSeek Harness 工作区（由 harness.workspaces 或 -Ddsh_mcp_workspaces 配置多个路径），"
                        + "返回名称、路径、是否存在以及聊天数量。")
                .tool(arguments -> service.listWorkspaces())
                .build();
    }

    /** 列举聊天。 */
    private ToolData chats() {
        return new ToolData.Builder("harness_chats",
                "列举工作区下的 DeepSeek Harness 聊天（会话）。返回 chat_id、标题、工作区、更新时间与运行状态；"
                        + "chat_id 用于 harness_send / harness_latest。")
                .putOptionalParam("workspace", "只列出该工作区（路径或名称）；省略则列出所有已配置工作区。", "工作区")
                .putOptionalParam("limit", "最多返回多少条，默认 50，最大 200。", "数量")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.listChats(JsonConfig.getNonBlankString(json, "workspace"), JsonConfig.getInt(json, "limit", 50));
                })
                .build();
    }

    /** 新建聊天。 */
    private ToolData newChat() {
        return new ToolData.Builder("harness_new_chat",
                "在指定工作区创建一个新的 DeepSeek Harness 聊天；可选直接投递首条任务。"
                        + "返回的 chat_id 用于后续 harness_send / harness_latest。")
                .putOptionalParam("workspace", "工作区路径或名称；省略时使用第一个配置的工作区。", "工作区")
                .putOptionalParam("title", "聊天标题，可省略（DSH 会根据首条消息自动生成）。", "标题")
                .putOptionalParam("task", "创建后立即投递的首条任务，可省略。", "任务")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.newChat(JsonConfig.getNonBlankString(json, "workspace"), JsonConfig.getNonBlankString(json, "title"), JsonConfig.getNonBlankString(json, "task"));
                })
                .build();
    }

    /** 投递任务。 */
    private ToolData send() {
        return new ToolData.Builder("harness_send",
                "向已有聊天投递一条任务（用户消息），立即返回已接收。之后用 harness_latest 读取输出与思维链。")
                .putParam("chat_id", "聊天 id，来自 harness_chats 或 harness_new_chat。", "聊天")
                .putParam("task", "要投递的任务正文。", "任务")
                .putOptionalParam("wait_seconds", "可选：等待轮次开始/结束的秒数（0-25），默认 0 立即返回。", "等待秒数")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.sendTask(JsonConfig.getNonBlankString(json, "chat_id"), JsonConfig.getNonBlankString(json, "task"),
                            JsonConfig.getInt(json, "wait_seconds", 0));
                })
                .build();
    }

    /** 读取最新输出。 */
    private ToolData latest() {
        return new ToolData.Builder("harness_latest",
                "读取聊天的最新输出与思维链。传入上次返回的 cursor 作为 since；当没有新内容且 only_if_changed "
                        + "不是 false 时只返回“未变更”，不会重复返回旧内容。返回里的 pending 是待回答的提问/审批，"
                        + "用 harness_feedback 反馈。")
                .putParam("chat_id", "聊天 id。", "聊天")
                .putOptionalParam("since", "上次返回的 cursor（事件序号）；省略则返回最近内容。", "游标")
                .putOptionalParam("include_reasoning", "是否包含思维链，默认 true。", "包含思维链")
                .putOptionalParam("max_messages", "最多返回多少条消息，默认 20，最大 100。", "消息数")
                .putOptionalParam("only_if_changed", "没有新内容时是否只返回“未变更”，默认 true。", "仅变更时返回")
                .putOptionalParam("wait_seconds", "可选：长轮询等待新内容的秒数（0-25），默认 0。", "等待秒数")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    Long since = JsonConfig.getLongOrNull(json, "since");
                    return service.latest(JsonConfig.getNonBlankString(json, "chat_id"), since,
                            JsonConfig.getBool(json, "include_reasoning", true),
                            JsonConfig.getInt(json, "max_messages", 20),
                            JsonConfig.getBool(json, "only_if_changed", true),
                            JsonConfig.getInt(json, "wait_seconds", 0));
                })
                .build();
    }

    /** 列举待回答任务。 */
    private ToolData pending() {
        return new ToolData.Builder("harness_pending",
                "列举 DeepSeek Harness 侧等待外部 AI 处理的提问与工具审批任务。"
                        + "harness_latest 的返回值也会带 pending；用 harness_feedback 回答。")
                .putOptionalParam("chat_id", "只列出该聊天的待办。", "聊天")
                .putOptionalParam("kind", "question、approval 或 all（默认 all）。", "类型")
                .putOptionalParam("wait_seconds", "可选：等待新待办的秒数（0-25），默认 0 立即返回。", "等待秒数")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.pending(JsonConfig.getNonBlankString(json, "chat_id"), JsonConfig.getNonBlankString(json, "kind"),
                            JsonConfig.getInt(json, "wait_seconds", 0));
                })
                .build();
    }

    /** 反馈提问/审批。 */
    private ToolData feedback() {
        return new ToolData.Builder("harness_feedback",
                "回答 DeepSeek Harness 的提问，或批准/拒绝一次工具审批。"
                        + "审批用 decision=allow/deny；提问用 text 自由回答，或用 selected 选择选项（多个用 | 分隔），"
                        + "复杂多问题可用 answers 传完整 JSON 数组。")
                .putParam("task_id", "待办任务 id，来自 harness_pending 或 harness_latest 的 pending。", "任务")
                .putOptionalParam("decision", "审批决定：allow 或 deny；也接受 允许/拒绝/同意/不同意。", "决定")
                .putOptionalParam("text", "提问的自由文本答案；审批时作为拒绝原因。", "文本")
                .putOptionalParam("selected", "提问选项标签，多个用 | 分隔，例如“方案A|方案B”。", "选项")
                .putOptionalParam("answers", "完整答案 JSON 数组，例如 [{\"id\":\"q1\",\"selected\":[\"允许\"]}]。", "完整答案")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.feedback(JsonConfig.getNonBlankString(json, "task_id"), JsonConfig.getNonBlankString(json, "decision"),
                            JsonConfig.getNonBlankString(json, "text"), JsonConfig.getNonBlankString(json, "selected"), JsonConfig.getNonBlankString(json, "answers"));
                })
                .build();
    }

    // ==================== 参数解析 ====================

    private static JsonObject json(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(arguments);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

}
