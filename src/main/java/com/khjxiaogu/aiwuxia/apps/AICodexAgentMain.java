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
package com.khjxiaogu.aiwuxia.apps;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;
import com.khjxiaogu.aiwuxia.utils.MessageReader;

/**
 * Codex 编排 agent 应用：自己不写代码，把活交给本机的 Codex 编程代理，再把结果讲给用户。
 * <p>
 * 与 {@link AIWuxiaMain} 这类「直接生成内容」的应用不同，本应用依赖会话里注册的
 * {@code codex_*} 工具（{@code CodexMcp}）：
 * </p>
 * <ul>
 *   <li>在 QQ 里由 {@code NapCatAIConnector} 按技能名 {@code codex} 注册；</li>
 *   <li>在外部 MCP 场景由 {@code McpServerEndpoint} 暴露；</li>
 *   <li>应用自己<b>不</b>创建 Codex 进程，避免一个应用把运行时生命周期握在手里。</li>
 * </ul>
 * <p>
 * 因此同一个应用既能挂 QQ，也能在别的入口复用；缺少工具时模型会看到「工具不存在」，
 * 提示词里已要求它把这一情况如实告诉用户，而不是假装完成。
 * </p>
 */
public class AICodexAgentMain extends AIApplication {

    /** 应用名。 */
    private final String name;
    /** 系统提示词（内置 + 可选 role.txt/rules.txt）。 */
    private final String system;

    /**
     * @param base 服务数据根目录
     * @param path 应用目录（save/apps/xxx）
     * @param name 应用显示名（meta.json 的 name）
     * @param meta 元数据
     * @throws IOException 读取提示词文件失败
     */
    public AICodexAgentMain(File base, File path, String name, JsonObject meta) throws IOException {
        super();
        this.name = name == null || name.trim().isEmpty() ? "Codex 编程助手" : name;
        this.system = buildPrompt(path, meta);
        handlers.add((state, ret) -> {
            if (ret != null && !ret.isEmpty()) {
                try (HistoryWriteContext write = state.beginWrite(Role.USER)) {
                    write.append(ret, true);
                }
            }
            try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
                AIOutput output = LLMConnector.call(constructAIrequest(state));
                state.setCurrentOutput(output);
                output.addUsageListener(state::addUsage);
                // 思维链后台写，正文边读边写，与项目里其它应用保持一致
                observeReasonerWrite(handleReasonerContent(output, state, write));
                writeContent(output, write);
            }
            state.addDialogRow();
            return null;
        });
    }

    @Override
    public void provideInitial(AISession state) {
        state.setStage(com.khjxiaogu.aiwuxia.state.ApplicationStage.STARTED);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String constructSystem(ApplicationState state) {
        return system;
    }

    @Override
    public String getBrief(AISession state) {
        return name;
    }

    @Override
    public String getMemory(AISession state) {
        return state.getLastSummary();
    }

    // ==================== 请求构造 ====================

    /**
     * 构造本轮请求：系统提示 + 压缩后的历史。工具由会话的 {@code ToolCallManager} 提供。
     */
    public AIRequest constructAIrequest(AISession state) throws IOException {
        Builder builder = AIRequest.builder(state)
                .taskType(TaskType.CODE)
                .strength(ReasoningStrength.MEDIUM)
                .streamed()
                .temperature(0.6f)
                .maxTokens(8192);
        builder.addHistoryItem(Role.SYSTEM, system);
        HistoryHolder history = state.getHistory();
        if (history != null && !history.isEmpty()) {
            HistoryCompactor.compact(history, 90000, 20000,
                    role -> role == Role.USER ? "用户：" : "助手：",
                    removed -> {
                        // Codex 自己保存了完整线程；这里用模型把被移除的历史压成真正的摘要，
                        // 并保留上一版摘要，避免每次压缩都丢掉更早的信息。
                        String text = removed == null ? "" : removed.trim();
                        if (text.length() > 6000) {
                            text = text.substring(text.length() - 6000);
                        }
                        try {
                            state.setLastSummary(makeSummaryrequest(state, text));
                        } catch (Throwable t) {
                            t.printStackTrace();
                            String previous = state.getLastSummary();
                            state.setLastSummary((previous == null || previous.isEmpty() ? "" : previous + "\n")
                                    + text);
                        }
                    });
            String lastSummary = state.getLastSummary();
            if (lastSummary != null && !lastSummary.isEmpty()) {
                builder.addHistoryItem(Role.SYSTEM, "（较早对话摘要，仅供参考）\n" + lastSummary);
            }
            Iterator<HistoryItem> iterator = history.validContextIterator();
            while (iterator.hasNext()) {
                builder.addHistoryItem(iterator.next());
            }
        }
        return builder.build();
    }

    /**
     * 用模型压缩被移除的历史；保留上一版摘要作为前情，避免旧信息被覆盖。
     */
    public String makeSummaryrequest(AISession state, String summary) throws IOException {
        Builder builder = AIRequest.builder(state).taskType(TaskType.CODE)
                .strength(ReasoningStrength.MEDIUM).temperature(0.3f).maxTokens(2048);
        builder.addHistoryItem(Role.SYSTEM,
                "你是对话历史压缩器。把给定的对话块压缩成中文摘要，保留：用户需求、已完成的工作、"
                        + "验证结果、未完成事项和下一步；不要写工具名、线程 id、路径、审批/沙箱等内部细节。"
                        + "只输出摘要正文，控制在 800 字以内。");
        StringBuilder user = new StringBuilder();
        String previous = state.getLastSummary();
        if (previous != null && !previous.trim().isEmpty()) {
            user.append("=== 前情提要 ===\n").append(previous.trim()).append("\n\n");
        }
        user.append("=== 待压缩对话块 ===\n").append(summary == null ? "" : summary.trim());
        builder.addHistoryItem(Role.USER, user.toString());
        AIOutput output = LLMConnector.call(builder.build());
        output.addUsageListener(state::addUsage);
        String text = output.getContentText();
        return text == null ? "" : text.trim();
    }

    /** 把正文内容段写入本轮记录。 */
    private void writeContent(AIOutput output, HistoryWriteContext write) throws IOException {
        MessageReader reader = output.getContent();
        if (reader == null) {
            return;
        }
        MessageContent current;
        boolean started = false;
        boolean wrote = false;
        while ((current = reader.read()) != null) {
            if (!started) {
                current = skipLeadingBlank(current);
                if (current == null) {
                    continue;
                }
                started = true;
            }
            write.append(current, true);
            wrote = true;
        }
        if (!wrote) {
            logger.warning("本轮正文为空，没有回复内容");
        }
    }

    // ==================== 提示词 ====================

    /**
     * 内置编排提示词；应用目录下如果存在 {@code role.txt} / {@code rules.txt} 会追加在后面。
     */
    private String buildPrompt(File path, JsonObject meta) throws IOException {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个群聊里的编程助手：把用户的原话交给本机的编程代理执行，再把结果转述给用户。")
                .append("你不写代码、不执行命令，也不替用户拆解需求——步骤、验收标准、技术方案都由代理自己判断。\n\n");
        prompt.append("## 工具\n")
                .append("- codex_status：检查代理是否可用；首次使用或报错时看。\n")
                .append("- codex_threads / codex_start：查看或新建会话（只建会话，不投任务）。\n")
                .append("- codex_send：投递任务，立即返回；任务结束后系统会再通知你。task 放用户原话（去掉 @/引用前缀）；")
                .append("用户发来图片时把图片 id 放进 images。\n")
                .append("- codex_latest：按需读输出（恢复会话、用户问进度、看完整过程时用一次）。\n")
                .append("- codex_pending / codex_feedback：处理审批与提问。\n")
                .append("- codex_review_approval：预审 Codex 的审批请求（approve / ask_user / reject）。\n")
                .append("- kb_search / kb_read：只读了解/查资料时优先用；在项目知识库（根目录文件 + ")
                .append("design/docs/diary/discussion 子目录）搜关键字、读 .md。中英文文档都有，搜索时用 | ")
                .append("同时给中英文关键词（如 天气|weather）。\n")
                .append("- group_history：需要更早的群消息时按群 id 查询；私聊里除非必要不要查。\n")
                .append("- ask_user：需要用户确认/二选一时发询问卡片，用户选择或自由回复后系统再通知你。\n")
                .append("- codex_interrupt：中止当前任务。\n\n");
        prompt.append("## 发送方式（重要）\n")
                .append("你的正文不会自动发出：要让用户看到内容，**必须调用发送工具**。\n")
                .append("- `send_group(text)`：发到当前群；群里的公开回复用它。\n")
                .append("- `send_private(user, text)`：发 QQ 私聊；`user` 省略时发给当前私聊用户。私聊回复必须用它。\n")
                .append("- 私聊消息会以 `<private user=\"QQ\" name=\"...\">` 开头；回复时把 user 带上，或用默认对象。\n")
                .append("- 需要把私聊交给同群另一个 agent 时用 `handover_private(agent, summary, user?)`：")
                .append("只传摘要、不传聊天记录，之后由对方接手；不确定对方名字时先用 `list_agents`")
                .append("（会带该用户的群名片）。\n")
                .append("- 汇报时按会话类型选工具：群里用 send_group，私聊用 send_private；不需要用户看到的内容不要发，")
                .append("同一内容也不要重复发。\n\n");
        prompt.append("## 工作流\n")
                .append("1. 用户的话能看懂就直接转交；只有完全看不出要改什么（例如只说「改一下」）才问一句。")
                .append("不要补目标、步骤、验收标准，也不要改写措辞。\n")
                .append("2. 只传关键信息：用户原话 + 必要的同项目上下文。不要复述、不要扩写成需求文档；")
                .append("对用户也不要反复说「已发给代理」，一句「收到，处理中」即可。\n")
                .append("3. **隔离会话**：同一串相关操作（同一目标、同一模块、需要接着上一轮改）用同一个 thread_id；")
                .append("换项目、或与上一轮无关的新任务，先 codex_start 开新会话，不要把所有事塞进一个线程。\n")
                .append("4. 任务结束通知到达后：看 status/text/recent_tools；需要细节用 codex_latest（since 用 cursor）读一次；")
                .append("然后汇报结论、改动文件、验证方式。\n")
                .append("5. 收到 `<codex-approval task_id=\"...\">` 时由你预审：**先逐字核对**命令/参数/路径/cwd 是否与当前任务一致、")
                .append("拼写和引号是否正确，再判断是否安全（删除/覆盖、越界写入、读取凭据、可疑联网、编码混淆、")
                .append("串接命令要逐条看）。\n")
                .append("   - 合理且安全：调用 codex_review_approval，decision=\"approve\"，reason 写关键依据，直接放行，不用告诉用户；\n")
                .append("   - 不确定或危险：decision=\"ask_user\"，由系统交给用户决定；")
                .append("可同时给 reviewer=<QQ> 建议审核人（例如让发起人自己确认）；")
                .append("只有 agent 开启开放审核权限才生效，否则该参数被忽略、仍由管理员审核。\n")
                .append("   - 明显错误或恶意：decision=\"reject\"。\n")
                .append("   不要不复核就 approve；也不要向用户复述整条命令。\n")
                .append("6. 检查完成通知的正文：如果 Codex 只写了本地图片路径（例如 `screenshots/a.png`）却没有用 `![说明](路径)`，")
                .append("且本轮没有图片附件，就用 codex_send 发一句「请用 ![说明](路径) 输出图片，不要只给路径」；")
                .append("同一问题只提醒一次，再汇报。\n")
                .append("7. 用户追问进度或恢复会话时才 codex_latest 一次，不要连续轮询；用户问「之前做到哪」直接读历史。\n\n");
        prompt.append("## 回复用户\n")
                .append("- 中文、简洁、像聊天；先结论后细节，不贴大段 diff/代码。\n")
                .append("- **不要透露内部细节**：不提工具名、会话/游标、附件 id、存储、沙箱/审批配置、配置文件路径等，")
                .append("只说用户需要知道的结果和操作。\n")
                .append("- 图片由系统自动发出，说明是什么即可，不要贴路径或 id。\n")
                .append("- 不编造结果：没收到完成通知、没看到工具输出，就不能说「已完成」。\n")
                .append("- 删除、外发、越界写入、联网等敏感操作先说明并征得同意；审批请求转述给用户。\n")
                .append("- 工具不可用时简短说明暂时无法处理，不要编造，也不要贴配置路径。\n")
                .append("- 一次回复控制在 1500 字以内。\n");
        prompt.append("## 本轮总结（必须）\n")
                .append("每轮结束时，在你的**最终 assistant 正文**末尾写本轮总结，便于后续对话压缩和接手：\n")
                .append("【本轮总结】\n用户需求：……\n工作进度：……\n下一步：……\n")
                .append("- **不要写进 send_group / send_private 的 text 参数**；发送工具只放真正要发给用户的内容。\n")
                .append("- 总结写在正文里即可；显式发送模式下正文不会自动发给用户，只进入会话历史。\n")
                .append("- 只写事实，3-5 行；不要写工具名、线程 id、路径、审批/沙箱等内部细节。\n")
                .append("- 没有明确需求时写「用户需求：无明确需求」；没有进展写「工作进度：暂无」。\n")
                .append("- 如果本轮选择不回复用户，也仍然在正文里留下总结。\n");
        if (meta != null && meta.has("prompt") && !meta.get("prompt").isJsonNull()) {
            String extra = meta.get("prompt").getAsString();
            if (extra != null && !extra.trim().isEmpty()) {
                prompt.append("\n## 额外要求\n").append(extra.trim()).append('\n');
            }
        }
        appendIfExists(prompt, new File(path, "role.txt"), "角色设定");
        appendIfExists(prompt, new File(path, "rules.txt"), "补充规则");
        return prompt.toString();
    }

    private static void appendIfExists(StringBuilder prompt, File file, String title) throws IOException {
        if (file.isFile()) {
            String text = FileUtil.readString(file).replace("\r", "").trim();
            if (!text.isEmpty()) {
                prompt.append("\n## ").append(title).append('\n').append(text).append('\n');
            }
        }
    }
}
