package com.khjxiaogu.aiwuxia.state.session;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolTask;
import com.khjxiaogu.aiwuxia.mcp.CharacterSettingMcp;
import com.khjxiaogu.aiwuxia.mcp.DeepseekSearchMcp;
import com.khjxiaogu.aiwuxia.mcp.FetchMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ServerToolUseContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

import io.netty.channel.Channel;

/**
 * 绘画会话。
 * <p>
 * 连接管理、鉴权计费、生成生命周期、消息帧与历史分页全部复用 {@link WebSocketAISession}，
 * 本类只保留绘画前端特有的指令处理（按 {@link #PAINTER_OPERATIONS} 分发）、
 * 思考流展示、图片工具链与视频等异步长任务的结算与下发。
 * </p>
 */
public class PainterSession extends WebSocketAISession {

	/**
	 * 绘画前端指令处理器。
	 */
	@FunctionalInterface
	public interface PainterOperation {
		/**
		 * @param jo    客户端请求
		 * @param state 当前会话
		 * @param conn  发起请求的连接，需要只回给该连接时使用
		 */
		void accept(JsonObject jo, PainterSession state, Channel conn);
	}

	/**
	 * 绘画前端指令表，按请求的 {@code type} 分发。
	 */
	public static final Map<String, PainterOperation> PAINTER_OPERATIONS = new HashMap<>();

	static {
		PAINTER_OPERATIONS.put("message", (jo, state, conn) -> {
			String msgId = JsonConfig.getNonBlankString(jo, "msgId");
			// 重连/超时重放：同一条待生成输入只写一次
			if (state.isAcceptedMessage(msgId)) {
				state.acknowledgeMessage(conn, msgId, true, null);
				return;
			}
			state.addPendingUserMessage(jo.get("content").getAsString(), msgId, conn);
		});
		PAINTER_OPERATIONS.put("generate", (jo, state, conn) -> {
			if (state.lock.compareAndSet(false, true)) {
				state.getCommandExec().submit(() -> {
					try {
						state.getAiapp().handleSpeech(state, MessageContents.EMPTY);
					} finally {
						state.lock.set(false);
					}
				});
			} else {
				state.sendNotice("操作太快啦，请稍后再试");
			}
		});
		PAINTER_OPERATIONS.put("stop", (jo, state, conn) -> {
			if (state.getCurrentOutput()!=null) {
				state.getCurrentOutput().interrupt();
			} else {
				// 本轮其实已经结束（停止请求来晚了）：回发真实生成状态，让前端退出“停止中”的等待态
				state.sendGenerationStatus();
				state.sendNotice("操作太快啦，请稍后再试");
			}
		});
		PAINTER_OPERATIONS.put("requestBackLog", (jo, state, conn) -> {
			// 分页参数解析与下发统一由 WebSocketAISession 处理，缺省(或<=0)的 before_id 即加载最新一页
			state.requireMoreMessages(jo);
		});
		PAINTER_OPERATIONS.put("model", (jo, state, conn) -> {
			// 模型切换与对话前端共用父类实现：校验标识并回发最终生效的模型
			state.setModel(jo.has("model") && !jo.get("model").isJsonNull() ? jo.get("model").getAsString() : "");
		});
		PAINTER_OPERATIONS.put("revert", (jo, state, conn) -> {
			// 撤回最后一轮：与对话前端共用父类实现，动作在会话命令线程中执行；
			// 回填只发回发起请求的连接，避免多标签页互相覆盖输入框
			state.requestRevert(JsonConfig.getNonBlankString(jo, "requestId"), conn);
		});
		PAINTER_OPERATIONS.put("regen", (jo, state, conn) -> {
			// 重新生成最后一条回复
			state.requestRegen(JsonConfig.getNonBlankString(jo, "requestId"), conn);
		});
	}

    /**
     * 绘画会话中不受调用次数与轮次上限约束的工具。
     * <p>
     * 名称与注册时的工具名一致：
     * </p>
     * <ul>
     *   <li>{@code send_image}（绘画会话的发送图片工具）；</li>
     *   <li>{@code send_video}（绘画会话的发送视频工具）；</li>
     *   <li>{@code search_character}（{@link SDXLMcp} 搜索角色提示词）；</li>
     *   <li>{@code web_view}（{@link FetchMcp} 抓取网页，即“fetch”）；</li>
     *   <li>{@code web_search}（{@link DeepseekSearchMcp} 的 DeepSeek 网页搜索，未配置
     *       {@code deepseektoken} 时不注册）；</li>
     *   <li>{@code moegirl_search}（{@link FetchMcp} 萌娘百科搜索，即“search_moegirl”）。</li>
     *   <li>{@code list_characters} / {@code search_characters} / {@code get_character_setting}
     *       （{@link CharacterSettingMcp} 的角色设定查询）。</li>
     * </ul>
     * <p>
     * 这几类调用属于搜索参考、取用素材与下发结果的辅助动作，一次绘画里可能反复触发：
     * 它们既不消耗工具调用轮次，也不会因轮次或次数上限被拒绝执行。
     * </p>
     */
    private static final Set<String> UNLIMITED_TOOLS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("send_image", "send_video", "search_character", "web_view", "web_search",
                "moegirl_search", "list_characters", "search_characters", "get_character_setting")));

    public PainterSession(AIChatService par, String uid, String chatid, AIApplication aiapp,
                          ApplicationAttributes attr, File fn, ISaveData data) {
        super(par, uid, chatid, aiapp, attr, fn, data);
        // 历史里的图片只保存对象存储 ID，模型调用层用本会话的存储把它们解析成可访问地址
        setObjectStorage(par.getPainterStorage());
        // 绘画工具由 AIChatService 统一创建，SDXL 工具按用户取用，这里只注册进本会话的工具调用管理器，
        // 由管理器统一处理调用次数、权限、许可与执行
        par.registerPainterTools(this);
    }

    /**
     * 绘画会话不在连接建立时等待初始化提示，保持原有的异步行为，避免生成中重连被阻塞。
     */
    @Override
    protected void prepareInitialHint() {
        provideInitialHint();
    }

    /**
     * 写入绘画会话的工具调用规则：搜索参考、抓取素材与下发结果的工具不受调用次数限制，
     * 既不参与工具调用轮次计数，也不会因为轮次或次数超限而被拒绝执行。
     * <p>
     * 其余规则（轮次上限、权限、许可）沿用 {@link ToolCallManager} 的默认值。
     * </p>
     *
     * @param manager 本会话的工具调用管理器
     */
    @Override
    protected void applyToolCallRules(ToolCallManager manager) {
        manager.setUnlimitedTools(UNLIMITED_TOOLS);
    }

    /**
     * 绘画应用不会进入 {@code STARTED} 阶段（阶段始终停留在 INITIALIZE），
     * 而绘画前端只在向上滚动时才主动发送 requestBackLog，
     * 因此连接建立后必须始终下发最近一页历史，否则界面初始为空。
     */
    @Override
    protected boolean shouldSendBacklog() {
        return true;
    }

    @Override
    public void onMessage(Channel conn, String message) {
        JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
        // 心跳：ping -> pong，与对话前端共用实现，不参与指令分发
        if (Heartbeat.replyPongIfPing(conn, jo)) {
            return;
        }
        if (!jo.has("type")) {
            return;
        }
        String frameType = jo.get("type").getAsString();
        PainterOperation operation = PAINTER_OPERATIONS.get(frameType);
        if (operation != null) {
            operation.accept(jo, this, conn);
            return;
        }
        // 绘画前端未特殊处理的通用指令（语音开关、工具许可等）交给父类的公共帧处理器
        super.onMessage(conn, message);
    }

    /**
     * 绘画会话是否可用。
     * <p>
     * 用户通过 {@code /painter-local} 页面接入了自己的本地绘画模型时，即使服务器没有共享的
     * SDXL 服务，该用户的绘画会话也可用；两者都没有时才判定为不可用。这与工具执行时的
     * 取用顺序一致（用户模型优先，离线回退共享模型）。
     * </p>
     */
    @Override
	public boolean isAvailable() {
		boolean available = LocalModel.hasOnlineService(user, ModelType.SDXL);
		if (!available) {
			// 打印 session 所属用户，便于和本地模型接入日志里的 uid 对比
			System.err.println("[Painter] local SDXL unavailable for session user=" + user);
		}
		return available;
	}

	/**
	 * 绘画前端的帧全部按 {@code type} 分发，因此模型回发也带上 type，
	 * 避免无 type 的帧被前端当成消息内容处理。
	 */
	@Override
	protected void sendModelHint(String model) {
		sendFrame(JsonBuilder.object().add("type", "model").add("model", model).end().toString());
	}

	/**
	 * 记录一条待生成的用户文本输入。
	 * <p>
	 * 与 {@link #addPendingUserMessage(MessageContent)} 相同，写入动作同样排到会话命令线程上执行。
	 * </p>
	 *
	 * @param content 用户输入的文本
	 */
	public void addPendingUserMessage(String content) {
		addPendingUserMessage(new MutableMessageContents(content));
	}

	/**
	 * 记录一条待生成的用户文本输入，并在写入成功后向发起连接回送确认帧。
	 *
	 * @param content 用户输入的文本
	 * @param msgId   客户端消息标识
	 * @param conn    发起连接
	 */
	public void addPendingUserMessage(String content, String msgId, Channel conn) {
		addPendingUserMessage(new MutableMessageContents(content), msgId, conn);
	}

	/**
	 * 记录一条待生成的用户输入（文本、图片等）。
	 * <p>
	 * 绘画前端的输入分两步：先落成“待生成”的用户条目，再由 {@code generate} 触发本轮生成。
	 * 输入可能来自 WebSocket 线程（文本帧），也可能来自 HTTP 线程（图片上传），
	 * 两者都可能在生成进行中到达，因此统一排到会话命令线程上写入：
	 * 这样输入按到达顺序排在正在进行的这一轮之后，成为下一轮的待生成输入；
	 * 若当前没有正在生成的轮次，则与已有输入合并成本轮输入。
	 * </p>
	 * <p>
	 * 写入使用 {@link #continueWrite(Role)}：同一轮的多次输入（先文本、再图片）拼进同一条
	 * 用户记录，而不是各起一条。
	 * </p>
	 *
	 * @param content 用户输入内容
	 */
	public void addPendingUserMessage(MessageContent content) {
		addPendingUserMessage(new MutableMessageContents(content));
	}

	/**
	 * 记录一条待生成的用户输入（可包含多段内容）。
	 *
	 * @param content 用户输入内容，多段内容会写进同一条用户记录
	 */
	public void addPendingUserMessage(MessageContents content) {
		addPendingUserMessage(content, null, null);
	}

	/**
	 * 记录一条待生成的用户输入，并在写入成功后向发起连接回送确认帧。
	 * <p>
	 * 绘画页的“发送”只把文本落成待生成条目，不立即触发生成；因此确认帧必须等历史写入
	 * 真正完成后再发，否则前端会把“写失败”也当成发送成功。写入失败时撤销 msgId 的
	 * “已接受”标记，让客户端可以用同一个 msgId 重试。
	 * </p>
	 *
	 * @param content 用户输入内容
	 * @param msgId   客户端消息标识，可为 null（图片上传等非 WebSocket 来源）
	 * @param conn    发起连接，可为 null
	 */
	public void addPendingUserMessage(MessageContents content, String msgId, Channel conn) {
		if (msgId != null) {
			markAcceptedMessage(msgId);
		}
		try {
			getCommandExec().submit(() -> {
				try {
					try (HistoryWriteContext write = continueWrite(Role.USER)) {
						for (MessageContent part : content) {
							// 纯文本按行写入（与对话前端一致，带换行），图片等其它内容按块写入
							if (part.isPlainText())
								write.appendLine(part.toText(), true);
							else
								write.append(part, true);
						}
					}
					flush();
					acknowledgeMessage(conn, msgId, true, null);
				} catch (Throwable t) {
					parent.getLogger().printStackTrace(t);
					// 先明确拒收，再把原文还给用户；两步都带同一个 msgId，前端能对上自己的发送
					acknowledgeMessage(conn, msgId, false, "save_failed");
					refillChatBoxFor(content, msgId, conn);
					sendNotice("消息保存失败，请重试");
				}
			});
		} catch (Throwable t) {
			unmarkAcceptedMessage(msgId);
			acknowledgeMessage(conn, msgId, false, "rejected");
			parent.getLogger().printStackTrace(t);
		}
	}

    /**
     * 思维链写入钩子：按绘画前端的下行协议把推理内容写成帧。
     * <p>
     * 流式输出与重连补发共用 {@link #writeReasonerFrames(MessageContents, Consumer)}，
     * 保证两条路径产生的帧完全一致。
     * </p>
     */
    @Override
    protected void onReasonerAppend(MessageContent current) {
        writeReasonerFrames(new MutableMessageContents(current), this::sendFrame, this);
    }

    /**
     * 按绘画前端的下行协议把推理内容写成帧：纯文本发 thinking 帧，工具调用发 tool_call 帧。
     * <p>
     * 流式输出与重连补发共用本方法，保证两条路径产生的帧完全一致。
     * </p>
     *
     * @param reasoning 推理内容（一段或多段）
     * @param frameSink 帧的接收者，通常为 {@link #sendFrame(String)}
     */
    static void writeReasonerFrames(MessageContents reasoning, Consumer<String> frameSink) {
        writeReasonerFrames(reasoning, frameSink, null);
    }

    /**
     * 按绘画前端的下行协议把推理内容写成帧。
     * <p>
     * 提供会话时，工具调用的显示由会话统一解析：生成过程中使用已算好的显示，
     * 历史重新加载后按当前注册的工具重算，保证加载后工具简述与详情同样可见。
     * </p>
     *
     * @param reasoning 推理内容（一段或多段）
     * @param frameSink 帧的接收者，通常为 {@link #sendFrame(String)}
     * @param session   会话，可为 null（此时只使用内容自带的显示）
     */
    static void writeReasonerFrames(MessageContents reasoning, Consumer<String> frameSink, AISession session) {
        for (MessageContent current : reasoning) {
            if (current instanceof PlainText) {
                frameSink.accept(JsonBuilder.object()
                    .add("type", "thinking")
                    .add("content", ((PlainText) current).toText())
                    .end().toString());
            } else if (current instanceof ToolCallContent) {
                ToolCallContent content = (ToolCallContent) current;
                List<ToolCall> calls = content.getToolCalls();
                for (int i = 0; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);
                    ToolCallDisplay display = session == null ? content.getDisplay(i)
                        : session.getToolCallDisplay(content, i);
                    JsonObjectBuilder<JsonObject> frame = JsonBuilder.object()
                        .add("type", "tool_call")
                        .add("call_id", call == null || call.id == null ? "" : call.id)
                        .add("tool_name", display.getBrief()) // 用户友好简述替代原始工具名称
                        .add("tool", call == null || call.function == null ? "" : call.function.name);
                    if (display.hasContent()) {
                        frame.add("content", contentsToJson(display.getContent()));
                    }
                    frameSink.accept(frame.end().toString());
                }
            } else if (current instanceof ServerToolUseContent) {
                // DeepSeek 服务端联网工具：按工具调用帧下发，参数用带截断的占位文本
                ServerToolUseContent serverToolUse = (ServerToolUseContent) current;
                JsonObjectBuilder<JsonObject> frame = JsonBuilder.object()
                    .add("type", "tool_call")
                    .add("tool_name", serverToolUse.getBrief())
                    .add("tool", serverToolUse.getToolName());
                String detail = serverToolUse.toText();
                if (!detail.isEmpty()) {
                    frame.add("content", contentsToJson(new MutableMessageContents(detail)));
                }
                frameSink.accept(frame.end().toString());
            }
        }
    }

    /**
     * 补发生成中的思维链：把仍留在本轮可写入上下文里的内容按与流式输出一致的方式重发一次。
     * <p>
     * 推理内容一旦提交进历史（上下文对应的记录随之进入历史条目），历史分页里就会带上它，
     * 因此只需补发还没提交的那部分，不会与历史中的思考链重复。
     * </p>
     *
     * @param generating 当前是否正在生成
     * @param buffered   尚未提交进历史条目的思维链，可为 null
     * @param frameSink  帧的接收者，通常为 {@link #sendFrame(String)}
     * @param session    会话，用于统一解析工具显示，可为 null
     * @return 是否补发了内容
     */
    static boolean replayGeneratingReasoning(boolean generating, MessageContents buffered, Consumer<String> frameSink,
                                             AISession session) {
        if (!generating || buffered == null || buffered.isEmpty())
            return false;
        writeReasonerFrames(buffered, frameSink, session);
        return true;
    }

    /**
     * 连接建立（含断开重连）后补发正在生成的思维链。
     * <p>
     * 本轮回复在提交进历史之前只存在于 {@link AISession#getCurrentWrite()} 返回的可写入上下文里
     * （思维链、显示内容都写在它对应的记录上），历史记录中并没有它；
     * 重连时客户端只能拿到历史，若不补发，已经生成的那部分思考内容就丢了，
     * 界面上只剩下重连之后新产生的思考。
     * </p>
     */
    @Override
    protected void sendGeneratingReasoning() {
        HistoryWriteContext write = getCurrentWrite();
        replayGeneratingReasoning(isGenerating(), write == null ? null : write.getReasoningContent(), this::sendFrame,
            this);
    }

    /**
     * 追加多模态内容：除旧协议外，绘画前端需要结构化内容数组来渲染图片等，
     * 因此这里直接下发 content 数组（父类只处理纯文本增量）。
     */
    @Override
    public void appendMessage(int id, MessageContent content) {
        sendFrame(JsonBuilder.object()
            .add("id", id)
            .add("type", "content")
            .add("content", contentsToJson(new MutableMessageContents(content)))
            .end().toString());
    }

    // ==================== 异步长任务（即梦视频等） ====================

    /**
     * 异步工具任务创建：把任务状态下发给绘画前端。
     * <p>
     * 即梦等异步视频任务耗时几分钟，本轮回复往往先结束；前端据此在输入框上方一直显示
     * “视频生成中”，避免用户以为没有响应而重复发起。任务完成后由
     * {@link #handleToolTaskCompleted(ToolTask)} 下发结果。
     * 本地 H3 视频已改为同步工具，不经过这里。
     * </p>
     *
     * @param task 新任务
     */
    @Override
    protected void handleToolTaskCreated(ToolTask task) {
        if (task == null) {
            return;
        }
        sendFrame(taskFrame("created", task, null).toString());
    }

    /**
     * 异步工具任务结束：在会话命令线程里结算，先下发任务状态，再把结果附件写入历史。
     * <p>
     * 任务通常在本轮回复结束之后才完成（即梦视频等长任务需要数分钟），此时本轮写入上下文已经关闭，
     * 直接交给父类会把附件写进一条已经结束的记录里而丢失。因此这里续写最后一条助手回复
     * （最后一条不是助手时新起一条）：前端收到更新后的 {@code message} 帧后重新渲染这条回复，
     * 视频就此出现在本轮结果里，历史里也能重新加载出来。
     * </p>
     *
     * @param task 已结束的任务（成功或失败）
     */
    @Override
    protected void handleToolTaskCompleted(ToolTask task) {
        if (task == null) {
            return;
        }
        try {
            getCommandExec().submit(() -> publishTaskResult(task));
        } catch (Throwable t) {
            parent.getLogger().printStackTrace(t);
        }
    }

    /**
     * 结算一次异步任务：下发状态帧，并把结果附件写入历史。
     *
     * @param task 已结束的任务
     */
    private void publishTaskResult(ToolTask task) {
        boolean failed = task.isFailed() || task.isCancelled();
        String message = failed ? taskFailureMessage(task)
            : task.getResult() == null ? "" : task.getResult().getText();
        sendFrame(taskFrame(failed ? "failed" : "done", task, message).toString());
        if (failed) {
            sendNotice("媒体生成失败：" + (message == null || message.isEmpty() ? "未知错误" : message));
            releaseIfIdle();
            return;
        }
        List<MessageContent> attachments = task.getResult() == null ? null
            : task.getResult().getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            if (message != null && !message.isEmpty()) {
                sendNotice(message);
            }
            releaseIfIdle();
            return;
        }
        HistoryWriteContext current = getCurrentWrite();
        if (current != null && !current.isResolved()) {
            // 本轮还在写入（例如视频在本轮结束前就生成完）：直接追加到当前回复，保持顺序
            for (MessageContent attachment : attachments) {
                if (attachment != null) {
                    current.append(attachment, false);
                }
            }
        } else {
            // 本轮已经结束：续写最后一条助手回复（最后一条不是助手时新起一条）。
            // 续写而不是新开记录，能让视频留在原来那轮回复里：撤回/重新生成仍按
            // “助手回复 ↔ 用户请求”配对处理，不会把视频当成一轮独立对话。
            try (HistoryWriteContext write = continueWrite(Role.ASSISTANT)) {
                for (MessageContent attachment : attachments) {
                    if (attachment != null) {
                        write.append(attachment, false);
                    }
                }
                // 上下文里补一句文本：下一轮对话时模型知道上一轮已经生成过视频。
                // 明确说明视频 id 不能当参考图：视频与图片共用同一套内容哈希 id，
                // 直接拿视频 id 做参考图会让图生视频/即梦收到非图片数据。
                String ids = attachmentIds(attachments);
                write.appendContext(ids.isEmpty() ? "（视频已生成）"
                    : "（视频已生成，视频id：" + ids + "；视频 id 不能作为图片参考图使用）");
            }
            flush();
        }
        sendNotice("视频已生成");
        releaseIfIdle();
    }

    /**
     * 释放会话前的判断：没有连接、也没有仍在运行的后台任务时才释放。
     * <p>
     * 即梦视频等异步任务在本轮回复结束后还会继续几分钟，因此本轮结束时不能直接释放会话：会话关闭会取消
     * 后台任务，用户刷新回来就看不到结果。这里等最后一个任务完成、视频已写入历史之后再释放。
     * H3 视频是同步工具，本轮内就已完成，不依赖这段逻辑。
     * </p>
     */
    private void releaseIfIdle() {
        if (conn.isEmpty() && getToolCallManager().getRunningTasks().isEmpty()) {
            parent.markRelease(this);
        }
    }

    /**
     * 本轮结束时若仍有后台任务在运行，先不释放会话（否则任务会被取消）。
     * <p>
     * 任务全部结束后由 {@link #releaseIfIdle()} 在命令线程里释放；期间用户重新打开页面则恢复正常，
     * 由下一次生成结束时的常规流程释放。
     * </p>
     *
     * @return 没有后台任务时返回 {@code true}
     */
    @Override
    protected boolean canReleaseWhenIdle() {
        return getToolCallManager().getRunningTasks().isEmpty();
    }

    /**
     * 失败任务的说明文本。
     *
     * @param task 失败的任务
     * @return 可展示的失败原因
     */
    private static String taskFailureMessage(ToolTask task) {
        Throwable error = task.getError();
        if (error != null && error.getMessage() != null && !error.getMessage().isEmpty()) {
            return error.getMessage();
        }
        return task.isCancelled() ? "任务已取消" : "未知错误";
    }

    /**
     * 拼接附件里的视频 id（用于写入上下文，让模型记得生成过哪些视频）。
     *
     * @param attachments 附件列表
     * @return 逗号分隔的视频 id，没有视频时为空串
     */
    private static String attachmentIds(List<MessageContent> attachments) {
        StringBuilder ids = new StringBuilder();
        for (MessageContent attachment : attachments) {
            if (!(attachment instanceof VideoContent)) {
                continue;
            }
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(((VideoContent) attachment).getVideoUrl());
        }
        return ids.toString();
    }

    /**
     * 构造异步任务帧：{@code {type:"task", state, id, tool, name, time, message?}}。
     *
     * @param state   任务状态：{@code created} / {@code done} / {@code failed}
     * @param task    任务
     * @param message 附加说明，可为 null
     * @return 下行帧
     */
    private static JsonObject taskFrame(String state, ToolTask task, String message) {
        JsonObjectBuilder<JsonObject> frame = JsonBuilder.object()
            .add("type", "task")
            .add("state", state)
            .add("id", task.getId() == null ? "" : task.getId())
            .add("tool", task.getToolName() == null ? "" : task.getToolName())
            .add("name", task.getDisplay() == null ? "" : task.getDisplay().getBrief())
            .add("time", task.getCreatedAt());
        if (message != null && !message.isEmpty()) {
            frame.add("message", message);
        }
        return frame.end();
    }

    /**
     * 往 {@code status} 帧里附带仍在运行的任务，供重连/刷新后恢复“视频生成中”的提示。
     * <p>
     * 没有任务时也下发空数组：重连期间任务可能已经结束，前端需要靠这份全量快照清掉旧提示，
     * 缺字段会被当成“没有变化”。
     * </p>
     */
    @Override
    protected JsonObject statusExtras() {
        List<ToolTask> running = getToolCallManager().getRunningTasks();
        JsonArray tasks = new JsonArray();
        if (running != null) {
            for (ToolTask task : running) {
                tasks.add(taskFrame("running", task, null));
            }
        }
        JsonObject extras = new JsonObject();
        extras.add("tasks", tasks);
        return extras;
    }
}
