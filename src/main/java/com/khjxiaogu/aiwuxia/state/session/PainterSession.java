package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.mcp.FetchMcp;
import com.khjxiaogu.aiwuxia.mcp.MultiModalMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.utils.BotCallbackPromise;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.ModelType;

import io.netty.channel.Channel;

/**
 * 绘画会话。
 * <p>
 * 连接管理、鉴权计费、生成生命周期、消息帧与历史分页全部复用 {@link WebSocketAISession}，
 * 本类只保留绘画前端特有的指令处理（按 {@link #PAINTER_OPERATIONS} 分发）、
 * 思考流展示与图片工具链。
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
			state.addPendingUserMessage(jo.get("content").getAsString());
		});
		PAINTER_OPERATIONS.put("generate", (jo, state, conn) -> {
			if (state.lock.compareAndSet(false, true)) {
				state.getCommandExec().submit(() -> {
					try {
						state.getAiapp().handleSpeech(state, MessageContents.EMPTY);
						state.refillChatBox(MessageContents.EMPTY);
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
			// 撤回最后一轮：与对话前端共用父类实现，动作在会话命令线程中执行
			state.requestRevert();
		});
		PAINTER_OPERATIONS.put("regen", (jo, state, conn) -> {
			// 重新生成最后一条回复
			state.requestRegen();
		});
	}

	private static final AtomicLong LOCAL_REQUEST_ID = new AtomicLong(0);

    /**
     * 绘画会话中不受调用次数与轮次上限约束的工具。
     * <p>
     * 名称与注册时的工具名一致：
     * </p>
     * <ul>
     *   <li>{@code send_image}（{@link MultiModalMcp} 的发送图片工具）；</li>
     *   <li>{@code search_character}（{@link SDXLMcp} 搜索角色提示词）；</li>
     *   <li>{@code web_view}（{@link FetchMcp} 抓取网页，即“fetch”）；</li>
     *   <li>{@code moegirl_search}（{@link FetchMcp} 萌娘百科搜索，即“search_moegirl”）。</li>
     * </ul>
     * <p>
     * 这几类调用属于搜索参考、取用素材与下发结果的辅助动作，一次绘画里可能反复触发：
     * 它们既不消耗工具调用轮次，也不会因轮次或次数上限被拒绝执行。
     * </p>
     */
    private static final Set<String> UNLIMITED_TOOLS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("send_image", "search_character", "web_view", "moegirl_search")));

    private final ConcurrentHashMap<String, BotCallbackPromise> pendingLocalRequests = new ConcurrentHashMap<>();

    public PainterSession(AIChatService par, String uid, String chatid, AIApplication aiapp,
                          ApplicationAttributes attr, File fn, ISaveData data) {
        super(par, uid, chatid, aiapp, attr, fn, data);
        // 工具直接注册进会话的工具调用管理器，由管理器统一处理调用次数、权限、许可与执行
        registerTools(MultiModalMcp.create(par.getPainterStorage(), this::addUsage));
        registerTools(MultiModalMcp.createOutput(this,par.getPainterStorage(), Collections.emptyMap(),this::addUsage));
        registerTools(SDXLMcp.createRemoteLocalModel(this,par.getPainterStorage(), par.lora,par.chara,imgid->par.urlbase+"/painter/image?conversation_id="+chatid+"&image_id="+imgid, true));
        registerTools(FetchMcp.create(this, par.getPainterStorage()));
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

    @Override
	public boolean isAvailable() {
		//return true;
    	return LocalModel.hasOnlineService(ModelType.SDXL);
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
	 * 记录一条待生成的用户输入（文本、图片等）。
	 * <p>
	 * 绘画前端的输入分两步：先落成“待生成”的用户条目，再由 {@code generate} 触发本轮生成。
	 * 输入可能来自 WebSocket 线程（文本帧），也可能来自 HTTP 线程（图片上传），
	 * 两者都可能在生成进行中到达，因此统一交给
	 * {@link #submitHistoryWrite(Runnable, String)} 排到会话命令线程上写入：
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
		submitHistoryWrite(() -> {
			try (HistoryWriteContext write = continueWrite(Role.USER)) {
				for (MessageContent part : content) {
					// 纯文本按行写入（与对话前端一致，带换行），图片等其它内容按块写入
					if (part.isPlainText())
						write.appendLine(part.toText(), true);
					else
						write.append(part, true);
				}
			}
		}, "消息保存失败，请重试");
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
                        .add("tool_name", display.getBrief()) // 用户友好简述替代原始工具名称
                        .add("tool", call == null || call.function == null ? "" : call.function.name);
                    if (display.hasContent()) {
                        frame.add("content", contentsToJson(display.getContent()));
                    }
                    frameSink.accept(frame.end().toString());
                }
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

    public CompletableFuture<JsonObject> requestLocal(String path, String body) {
        String requestId = String.valueOf(LOCAL_REQUEST_ID.incrementAndGet());
        BotCallbackPromise promise = new BotCallbackPromise(5);
        pendingLocalRequests.put(requestId, promise);
        sendFrame(JsonBuilder.object()
            .add("type", "local_request")
            .add("request_id", requestId)
            .add("path", path)
            .add("body", body)
            .end().toString());
        return promise.getFuture();
    }

    public void completeLocalRequest(String requestId, JsonObject result) {
        BotCallbackPromise pending = pendingLocalRequests.remove(requestId);
        if (pending != null) {
        	if(result!=null&&result.has("code")&&result.has("data")) {
        		int code=result.get("code").getAsInt();
        	
        		pending.getBotCallback().call(code==200?0:code, result.get("data").getAsJsonObject());
        	}else
                pending.getBotCallback().call(-1, new JsonObject());
        }
    }
}
