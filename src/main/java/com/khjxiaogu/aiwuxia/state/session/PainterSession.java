package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.mcp.FetchMcp;
import com.khjxiaogu.aiwuxia.mcp.MultiModalMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
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

    public List<ToolData> tools=new ArrayList<>();
    private final ConcurrentHashMap<String, BotCallbackPromise> pendingLocalRequests = new ConcurrentHashMap<>();

    public PainterSession(AIChatService par, String uid, String chatid, AIApplication aiapp,
                          ApplicationAttributes attr, File fn, ISaveData data) {
        super(par, uid, chatid, aiapp, attr, fn, data);
		MultiModalMcp.create(par.getPainterStorage(), this::addUsage).addTool(this.tools);
        MultiModalMcp.createOutput(this,par.getPainterStorage(), Collections.emptyMap(),this::addUsage).addTool(this.tools);
        SDXLMcp.createRemoteLocalModel(this,par.getPainterStorage(), par.lora,par.chara,imgid->par.urlbase+"/painter/image?conversation_id="+chatid+"&image_id="+imgid, true).addTool(this.tools);
        FetchMcp.create(this, par.getPainterStorage());
    }

    @Override
	public List<ToolData> getAvailableTools() {
		return tools;
	}

    /**
     * 绘画会话不在连接建立时等待初始化提示，保持原有的异步行为，避免生成中重连被阻塞。
     */
    @Override
    protected void prepareInitialHint() {
        provideInitialHint();
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
		submitHistoryWrite(() -> appendLine(Role.USER, content, true), "消息保存失败，请重试");
	}

	/**
	 * 记录一条待生成的用户输入（文本、图片等）。
	 * <p>
	 * 绘画前端的输入分两步：先落成“待生成”的用户条目，再由 {@code generate} 触发本轮生成。
	 * 输入可能来自 WebSocket 线程（文本帧），也可能来自 HTTP 线程（图片上传），
	 * 两者都可能在生成进行中到达，因此统一交给
	 * {@link #submitHistoryWrite(Runnable, String)} 排到会话命令线程上追加：
	 * 这样输入按到达顺序排在正在进行的这一轮之后，成为下一轮的待生成输入；
	 * 若当前没有正在生成的轮次，则与已有输入合并成本轮输入，行为与直接追加一致。
	 * </p>
	 *
	 * @param content 用户输入内容
	 */
	public void addPendingUserMessage(MessageContent content) {
		submitHistoryWrite(() -> {
			if (content.isPlainText()) {
				appendLine(Role.USER, content.toText(), true);
			} else {
				appendCh(Role.USER, content, true);
			}
		}, "消息保存失败，请重试");
	}

    /**
     * 追加一条历史条目。
     * <p>
     * 绘画前端的用户输入在 {@code message} 帧到达时就已经以“待生成”的形式写入历史，
     * 生成时应用仍会按对话前端的惯例追加一条用户消息作为本轮输入，但此时传入的内容是
     * {@link MessageContents#EMPTY}（真正的输入早已由前端录入）。
     * </p>
     * <p>
     * 若把这条空消息也记进历史，它就会夹在用户输入与助手回复之间；撤回与重新生成按
     * “用户消息 + 助手回复”配对时取到的是这条空条目，于是用户真正输入的内容撤不掉，
     * 输入框还会被回填成空字符串。因此这里忽略空占位消息，直接复用待生成的用户条目。
     * </p>
     */
    @Override
    public void add(Role role, MessageContents content, boolean isValidContext) {
        if (shouldIgnoreRoundInput(role, content, getLast()))
            return;
        super.add(role, content, isValidContext);
    }

    /**
     * 判断本次追加是否为生成时补写的空用户占位消息，即应当忽略、直接复用待生成条目的输入。
     *
     * @param role    要追加的角色
     * @param content 要追加的内容，可为 null
     * @param last    追加前的最后一条历史条目，可为 null
     * @return 应当忽略时返回 true（角色为用户、最后一条也是待生成的用户输入、且本次内容为空）
     */
    static boolean shouldIgnoreRoundInput(Role role, MessageContents content, HistoryItem last) {
        return role == Role.USER
                && last != null && last.getRole() == Role.USER
                && (content == null || (content.isPlainText() && content.toText().isEmpty()));
    }

    @Override
    public void appendReasoner(MessageContent current) {
        super.appendReasoner(current);
        writeReasonerFrames(new MutableMessageContents(current), this::sendFrame);
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
                    ToolCallDisplay display = content.getDisplay(i);
                    JsonObjectBuilder<JsonObject> frame = JsonBuilder.object()
                        .add("type", "tool_call")
                        .add("tool_name", display.getBrief()) // 用户友好简述替代原始工具名称
                        .add("tool", call.function == null ? "" : call.function.name);
                    if (display.hasContent()) {
                        frame.add("content", contentsToJson(display.getContent()));
                    }
                    frameSink.accept(frame.end().toString());
                }
            }
        }
    }

    /**
     * 补发生成中的思维链：把仍留在推理缓冲区里的内容按与流式输出一致的方式重发一次。
     * <p>
     * 推理内容一旦挂到助手条目上（缓冲区随之清空），历史分页里就会带上它，
     * 因此只需补发缓冲区里的部分，不会与历史中的思考链重复。
     * </p>
     *
     * @param generating 当前是否正在生成
     * @param buffered   尚未写入历史的推理缓冲区，可为 null
     * @param frameSink  帧的接收者，通常为 {@link #sendFrame(String)}
     * @return 是否补发了内容
     */
    static boolean replayGeneratingReasoning(boolean generating, MessageContents buffered, Consumer<String> frameSink) {
        if (!generating || buffered == null || buffered.isEmpty())
            return false;
        writeReasonerFrames(buffered, frameSink);
        return true;
    }

    /**
     * 连接建立（含断开重连）后补发正在生成的思维链。
     * <p>
     * 首个助手内容产生之前，推理内容只存在于会话内存缓冲区
     * （{@link AISession#currentReasoner}）里，历史记录中并没有它；
     * 重连时客户端只能拿到历史，若不补发，已经生成的那部分思考内容就丢了，
     * 界面上只剩下重连之后新产生的思考。
     * </p>
     */
    @Override
    protected void sendGeneratingReasoning() {
        replayGeneratingReasoning(isGenerating(), currentReasoner, this::sendFrame);
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
