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
package com.khjxiaogu.aiwuxia.mcp;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.codex.CodexService;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;

/**
 * 把本机的 Codex 编程代理包装成一组 MCP 工具（参考 {@code HarnessMcp} 的组织方式）。
 * <p>
 * 推荐使用顺序：
 * </p>
 * <ol>
 *   <li>{@code codex_status} 看 Codex 是否可用、版本与当前配置；</li>
 *   <li>{@code codex_send} 直接把任务投给默认线程（没有线程会自动建）；
 *       需要指定目录时先 {@code codex_start}；</li>
 *   <li>反复 {@code codex_latest}（把上次返回的 {@code cursor} 当 {@code since}，
 *       配合 {@code wait_seconds}）读正文、推理摘要、工具活动与错误；</li>
 *   <li>返回值出现 {@code pending} 时：审批用 {@code codex_feedback} 的
 *       {@code decision=allow/allow_session/deny/cancel}，提问用 {@code text}；</li>
 *   <li>需要中止时 {@code codex_interrupt}；</li>
 *   <li>需要把对象存储里的素材放进项目固定目录（只给纯文件名）时 {@code codex_pull_file}，
 *       需要把固定目录里的文件换成对象存储 id 时 {@code codex_push_file}。</li>
 * </ol>
 * <p>
 * 每个工具都返回 JSON 文本（{@code ok}/{@code error} 字段），方便模型直接消费。
 * 工具实例绑定一个「归属键」，QQ 群与 MCP 用户各自只看到自己的线程。
 * </p>
 */
public class CodexMcp implements ToolProvider {

    private final CodexService service;
    private final String owner;
    /**
     * {@code codex_send} 是否注册成异步工具。
     * <p>
     * 异步时投递后立即返回，轮次结束时由工具管理器把结果作为新消息交给会话，
     * 从而「由系统等待、完成后触发 AI」，避免模型反复轮询。
     * </p>
     */
    private final boolean asyncSend;

    /**
     * @param service Codex 服务
     * @param owner   归属键（例如 {@code qq:botId:groupId}、{@code mcp:uid}）
     */
    public CodexMcp(CodexService service, String owner) {
        this(service, owner, false);
    }

    /**
     * @param service   Codex 服务
     * @param owner     归属键
     * @param asyncSend {@code codex_send} 是否异步（完成后由系统触发 AI）
     */
    public CodexMcp(CodexService service, String owner, boolean asyncSend) {
        this.service = service;
        this.owner = owner == null ? "" : owner;
        this.asyncSend = asyncSend;
    }

    /** 是否异步投递。 */
    public boolean isAsyncSend() {
        return asyncSend;
    }

    /** 底层服务。 */
    public CodexService getService() {
        return service;
    }

    /** 归属键。 */
    public String getOwner() {
        return owner;
    }

    @Override
    public String getName() {
        return "Codex MCP";
    }

    @Override
    public Collection<ToolData> getTools() {
        List<ToolData> tools = new ArrayList<>(11);
        tools.add(status());
        tools.add(threads());
        tools.add(start());
        tools.add(send());
        tools.add(latest());
        tools.add(pending());
        tools.add(feedback());
        tools.add(reviewApproval());
        tools.add(interrupt());
        tools.add(pullFile());
        tools.add(pushFile());
        return tools;
    }

    /** 运行状态。 */
    private ToolData status() {
        return new ToolData.Builder("codex_status",
                "查看本机 Codex 是否可用：安装路径、版本、进程是否在跑、当前工作目录/沙箱/审批模式、已存在的会话数。"
                        + "第一次使用或工具报错时先调用它。")
                .tool(arguments -> service.status().toString())
                .build();
    }

    /** 列出线程。 */
    private ToolData threads() {
        return new ToolData.Builder("codex_threads",
                "列出当前会话（QQ 群或 MCP 用户）下已存在的 Codex 线程：thread_id、工作目录、模型、是否正在执行、"
                        + "待审批数量、最后错误。thread_id 用于 codex_send / codex_latest / codex_interrupt。")
                .tool(arguments -> service.threads(owner).toString())
                .build();
    }

    /** 新建线程。 */
    private ToolData start() {
        return new ToolData.Builder("codex_start",
                "新建一个 Codex 线程（会话）。不传 cwd 时用默认工作目录；工作目录只能在允许范围内选择。"
                        + "返回的 thread_id 用于 codex_send。只建线程，不投递任务。")
                .putOptionalParam("cwd", "工作目录绝对路径；省略用配置的默认目录（codex.cwd）。", "工作目录")
                .putOptionalParam("title", "线程标题，便于在 codex_threads 里辨认。", "标题")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.startThread(owner, JsonConfig.getNonBlankString(json, "cwd"), JsonConfig.getNonBlankString(json, "title")).toString();
                })
                .build();
    }

    /** 投递任务。 */
    private ToolData send() {
        String description = asyncSend
                ? "把任务交给 Codex 后立即返回；任务在后台执行，结束时系统会把结果作为新消息发给你并触发新一轮。"
                        + "投递后本轮就结束，**不要用 codex_latest 轮询进度**；只有用户追问时才查一次。"
                        + "**task 直接放用户原话**（最多去掉 @/引用前缀），不要改写、不要补步骤或验收标准，Codex 会自己拆解。"
                        + "不传 thread_id 时用当前默认线程；同一线程上一轮没结束时返回 busy。"
                : "把任务投给 Codex 线程；可用 wait_seconds 等一会儿，之后用 codex_latest 读输出。"
                        + "task 放用户原话，不要改写或拆解。"
                        + "不传 thread_id 时用当前默认线程；同一线程上一轮没结束时返回 busy。";
        return new ToolData.Builder("codex_send", description)
                .putParam("task", "用户原话（去掉 @/引用前缀），不要改写、不要拆成步骤；Codex 会自己理解和拆解。", "任务")
                .putOptionalParam("thread_id", "目标线程 id；省略用当前默认线程。", "线程")
                .putOptionalUrlParam("images", "图片（可多张，逗号分隔，最多 4 张）", "图片")
                .putOptionalParam("wait_seconds", "同步模式：投递后等待输出的秒数（0-25）；异步模式忽略。", "等待秒数")
                .async(asyncSend)
                .tool((arguments, output, manager) -> {
                    JsonObject json = json(arguments);
                    String task = JsonConfig.getNonBlankString(json, "task");
                    List<String> images = imageIds(json);
                    if ((task == null || task.isEmpty()) && (images == null || images.isEmpty())) {
                        return CompletableFuture.completedFuture(
                                ToolResult.text(error("缺少任务内容 task").toString()));
                    }
                    if (asyncSend) {
                        return dispatch(JsonConfig.getNonBlankString(json, "thread_id"), task, images);
                    }
                    JsonObject sent = service.send(owner, JsonConfig.getNonBlankString(json, "thread_id"), task, images,
                            JsonConfig.getInt(json, "wait_seconds", 0));
                    return CompletableFuture.completedFuture(ToolResult.text(sent.toString()));
                })
                .build();
    }

    /**
     * 异步投递：任务轮次结束时完成 future，由工具管理器把结果交给会话触发新一轮 AI。
     *
     * @param threadId 线程 id，可为空
     * @param task     任务正文
     * @param images   图片 id 列表，可为空
     * @return 结果 future
     */
    private CompletableFuture<ToolResult> dispatch(String threadId, String task, List<String> images) {
        JsonObject sent = service.send(owner, threadId, task, images, 0);
        if (!JsonConfig.getBool(sent, "ok")) {
            return CompletableFuture.completedFuture(ToolResult.text(sent.toString()));
        }
        String tid = JsonConfig.getNonBlankString(sent, "thread_id");
        String turnId = JsonConfig.getNonBlankString(sent, "turn_id");
        long cursor = sent.has("cursor") ? sent.get("cursor").getAsLong() : 0L;
        long timeout = service.getConfig().turnTimeoutMs + 300000L;
        return service.awaitTurn(tid, turnId, timeout).thenApply(result -> {
            JsonObject payload = result.deepCopy();
            payload.addProperty("cursor", cursor);
            // 本轮 Codex 产出的截图/生成图：作为附件交给会话，由连接器发到 QQ
            JsonArray collected = service.collectImages(tid);
            JsonArray meta = new JsonArray();
            List<MessageContent> attachments = new ArrayList<>();
            for (JsonElement element : collected) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject image = element.getAsJsonObject();
                String id = JsonConfig.getNonBlankString(image, "id");
                String data = JsonConfig.getNonBlankString(image, "data");
                if ((id == null || id.isEmpty()) && (data == null || data.isEmpty())) {
                    continue;
                }
                JsonObject info = image.deepCopy();
                info.remove("data");
                meta.add(info);
                // 与其它工具一致：附件里放对象存储图片 id，连接器负责解析成 QQ 可发的地址
                attachments.add(new ImageContent(id != null && !id.isEmpty() ? id : data, "Codex 截图"));
            }
            if (meta.size() > 0) {
                payload.add("images", meta);
                payload.addProperty("images_sent", meta.size());
            }
            payload.addProperty("notice", "后台任务已结束（系统通知）。不要重复提交同一任务。");
            payload.addProperty("next", "向用户汇报：先看 text/recent_tools/status；需要完整过程时调用 codex_latest，"
                    + "thread_id 用 thread_id、since 用 cursor，一次读取后总结；pending 不为 0 时用 codex_pending 查看、"
                    + "codex_feedback 处理。图片（若有）已经作为图片消息发到群里，不要重复贴路径。");
            return attachments.isEmpty() ? ToolResult.text(payload.toString())
                    : ToolResult.of(payload.toString(), attachments);
        });
    }

    /** 读取输出。 */
    private ToolData latest() {
        return new ToolData.Builder("codex_latest",
                "增量读取线程输出。传上次返回的 cursor 作为 since；没有新内容且 only_if_changed 不为 false 时"
                        + "只返回 unchanged，不会重复灌入旧内容。"
                        + "进程重启后第一次读取会先用 Codex 的持久化记录回放最近几轮（history_loaded=true），"
                        + "所以恢复会话/问「之前做到哪」直接读即可，不需要给 Codex 发新消息。"
                        + "items 里 kind=assistant 是正文、reasoning 是思维链摘要、"
                        + "tool 是工具活动、turn 是轮次结束（status=completed/failed/interrupted）、error 是错误。"
                        + "items 里出现 pending 时用 codex_feedback 处理审批/提问。")
                .putOptionalParam("thread_id", "线程 id；省略用当前默认线程。", "线程")
                .putOptionalParam("since", "上次返回的 cursor；省略表示从最近内容开始读。", "游标")
                .putOptionalParam("include_reasoning", "是否包含思维链摘要，默认 true。", "包含思维链")
                .putOptionalParam("max_items", "最多返回多少条事件，默认 20，最大 100。", "条数")
                .putOptionalParam("wait_seconds", "长轮询等待新内容的秒数（0-25），默认 0。", "等待秒数")
                .putOptionalParam("only_if_changed", "没有新内容时是否只返回 unchanged，默认 true。", "仅变更时返回")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    Long since = JsonConfig.getLongOrNull(json, "since");
                    return service.latest(owner, JsonConfig.getNonBlankString(json, "thread_id"), since,
                            JsonConfig.getBool(json, "include_reasoning", true),
                            JsonConfig.getInt(json, "max_items", 20),
                            JsonConfig.getInt(json, "wait_seconds", 0),
                            JsonConfig.getBool(json, "only_if_changed", true)).toString();
                })
                .build();
    }
    /** 待处理交互。 */
    private ToolData pending() {
        return new ToolData.Builder("codex_pending",
                "列出等待处理的审批与提问（Codex 需要执行敏感命令、改工作目录外的文件，或需要补充信息时产生）。"
                        + "审批用 codex_feedback 的 decision=allow/allow_session/deny/cancel；提问用 text 回答。")
                .putOptionalParam("thread_id", "只列出该线程；省略用当前默认线程。", "线程")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.pending(owner, JsonConfig.getNonBlankString(json, "thread_id")).toString();
                })
                .build();
    }

    /** 答复审批/提问。 */
    private ToolData feedback() {
        return new ToolData.Builder("codex_feedback",
                "答复一条待处理交互：审批用 decision=allow（放行一次）/allow_session（本会话都放行）/deny（拒绝，"
                        + "继续本轮）/cancel（拒绝并中断本轮）；提问用 text 自由回答，或用 selected 选择选项"
                        + "（多个用 | 分隔）。拒绝时 text 会作为理由回传给 Codex。")
                .putParam("task_id", "待办任务 id，来自 codex_pending 或 codex_latest 的 pending。", "任务")
                .putOptionalParam("decision", "审批决定：allow / allow_session / deny / cancel，也接受"
                        + "允许/同意/拒绝/中断的写法。", "决定")
                .putOptionalParam("text", "提问的自由文本答案；审批时作为拒绝理由。", "文本")
                .putOptionalParam("selected", "提问的可选标签，多个用 | 分隔。", "选项")
                .putOptionalParam("answers", "完整答案 JSON，例如 {\"answers\":{\"q1\":{\"answers\":[\"允许\"]}}}。",
                        "完整答案")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.feedback(owner, JsonConfig.getNonBlankString(json, "task_id"), JsonConfig.getNonBlankString(json, "decision"),
                            JsonConfig.getNonBlankString(json, "text"), JsonConfig.getNonBlankString(json, "selected"), JsonConfig.getNonBlankString(json, "answers")).toString();
                })
                .build();
    }

    /** 预审：由对话 AI 判断 Codex 的审批是否合理。 */
    private ToolData reviewApproval() {
        return new ToolData.Builder("codex_review_approval",
                "预审一条 Codex 审批请求。收到 `<codex-approval task_id=...>` 时先逐字核对命令/参数/路径是否真正正确、"
                        + "是否安全、是否与当前任务一致：合理且安全用 decision=approve 直接放行；"
                        + "不确定、危险、越界写入/联网/读取凭据用 decision=ask_user 交给用户；明显错误或恶意用 reject。"
                        + "转人工时可以给 reviewer 建议审核人（例如让发起人自己确认）；只有 agent 开启开放审核权限才生效，"
                        + "否则一律由管理员审核。")
                .putParam("task_id", "预审任务 id，来自 `<codex-approval task_id=...>`。", "任务")
                .putParam("decision", "approve / ask_user / reject。", "决定")
                .putOptionalParam("reason", "一句话理由，会记入审批记录。", "理由")
                .putOptionalParam("reviewer", "建议审核人账号（QQ 号 / QQ 官方 openid）；仅 ask_user 且 agent 开启开放审核权限时生效，否则忽略。",
                        "审核人")
                .tool((arguments, output, manager) -> {
                    JsonObject json = json(arguments);
                    String taskId = JsonConfig.getNonBlankString(json, "task_id");
                    String decision = JsonConfig.getNonBlankString(json, "decision");
                    if (taskId == null) {
                        return CompletableFuture.completedFuture(ToolResult.text("缺少 task_id"));
                    }
                    if (decision == null) {
                        return CompletableFuture.completedFuture(
                                ToolResult.text("缺少 decision（approve / ask_user / reject）"));
                    }
                    String reviewer = JsonConfig.getNonBlankString(json, "reviewer");
                    return CompletableFuture.completedFuture(ToolResult.text(
                            service.reviewApproval(owner, taskId, decision, JsonConfig.getNonBlankString(json, "reason"),
                                    reviewer == null ? "" : reviewer).toString()));
                })
                .build();
    }

    /** 中断。 */
    private ToolData interrupt() {
        return new ToolData.Builder("codex_interrupt",
                "请求中断 Codex 当前正在执行的一轮任务。中断后线程仍可继续用 codex_send 投递新任务。")
                .putOptionalParam("thread_id", "线程 id；省略用当前默认线程。", "线程")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.interrupt(owner, JsonConfig.getNonBlankString(json, "thread_id")).toString();
                })
                .build();
    }

    /**
     * 对象存储 → 项目固定目录：把素材放进代码项目的固定接收目录并命名。
     * <p>
     * 典型场景：用户说「把这张图放到项目里叫 icon.png」，agent 用消息里的图片 id 调本工具，
     * 再用 {@code codex_send} 把返回的 {@code relative} 交给 Codex。目录固定，AI 只能控制文件名。
     * </p>
     */
    private ToolData pullFile() {
        return new ToolData.Builder("codex_pull_file",
                "把对象存储里的文件下载到 Codex 线程项目的固定接收目录（目录固定，不能选，也不能带路径），"
                        + "只需给纯文件名 name（例如 icon.png）。file_id 用其它工具返回的对象存储 id。"
                        + "目标已存在时默认报错，确实要覆盖传 overwrite=true。"
                        + "下载后如需 Codex 使用，把返回的 relative 写进 codex_send 的任务。")
                .orderSensitive()
                .putUrlParam("file_id", "输入要放进项目的文件", "文件ID")
                .putParam("name", "纯文件名（可带扩展名，例如 icon.png）；不能带目录、盘符或路径分隔符。", "文件名")
                .putOptionalParam("thread_id", "线程 id；省略用当前默认线程的项目目录（还没有线程时用默认项目目录）。",
                        "线程")
                .putOptionalParam("overwrite", "固定目录里已有同名文件时是否覆盖，true/false，默认 false。", "覆盖")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.pullFile(owner, JsonConfig.getNonBlankString(json, "thread_id"),
                            JsonConfig.getNonBlankString(json, "file_id"),
                            JsonConfig.getNonBlankString(json, "name"),
                            JsonConfig.getBool(json, "overwrite", false)).toString();
                })
                .build();
    }

    /**
     * 项目固定目录 → 对象存储：把固定目录里的文件换成对象存储 id。
     * <p>
     * 典型场景：把固定目录里的截图、文档、音频发给用户，或作为 {@code send_image} /
     * {@code codex_send images} 的输入。目录固定，AI 只能指定该目录里的文件名。
     * </p>
     */
    private ToolData pushFile() {
        return new ToolData.Builder("codex_push_file",
                "把 Codex 线程项目固定接收目录里的文件上传到对象存储，返回对象存储 id（可交给 send_image 等工具，"
                        + "或写进 codex_send 的参数）。目录固定，name 只能是该目录里的纯文件名。")
                .orderSensitive()
                .putParam("name", "固定接收目录里的纯文件名（例如 screenshot.png）；不能带目录或路径分隔符。", "文件名")
                .putOptionalParam("thread_id", "线程 id；省略用当前默认线程的项目目录（还没有线程时用默认项目目录）。",
                        "线程")
                .tool(arguments -> {
                    JsonObject json = json(arguments);
                    return service.pushFile(owner, JsonConfig.getNonBlankString(json, "thread_id"),
                            JsonConfig.getNonBlankString(json, "name")).toString();
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

    private static JsonObject error(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", message);
        return json;
    }

    /** 解析逗号分隔的图片 id 列表。 */
    private static List<String> imageIds(JsonObject json) {
        String raw = JsonConfig.getNonBlankString(json, "images");
        if (raw == null) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (String part : raw.split("[,，\\s]+")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                ids.add(value);
            }
        }
        return ids.isEmpty() ? null : ids;
    }

}
