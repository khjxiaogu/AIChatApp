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
import com.khjxiaogu.aiwuxia.state.Role;
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
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

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
				state.sendNotice("操作太快啦，请稍后再试");
			}
		});
		PAINTER_OPERATIONS.put("requestBackLog", (jo, state, conn) -> {
			// 分页参数解析与下发统一由 WebSocketAISession 处理，缺省(或<=0)的 before_id 即加载最新一页
			state.requireMoreMessages(jo);
		});
		PAINTER_OPERATIONS.put("ping", (jo, state, conn) -> {
			// 心跳：只回给发起请求的连接，并原样带回同一个id，客户端据此确认连接仍然可用
			JsonObjectBuilder<JsonObject> pong = JsonBuilder.object().add("type", "pong");
			if (jo.has("id") && !jo.get("id").isJsonNull()) {
				pong.add("id", jo.get("id"));
			}
			conn.writeAndFlush(new TextWebSocketFrame(pong.end().toString()));
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
        if (!jo.has("type")) {
            return;
        }
        PainterOperation operation = PAINTER_OPERATIONS.get(jo.get("type").getAsString());
        if (operation != null) {
            operation.accept(jo, this, conn);
        }
    }

    @Override
	public boolean isAvailable() {
		return LocalModel.hasOnlineService(ModelType.SDXL);
	}
	public void addPendingUserMessage(String content) {
    	appendLine(Role.USER, content, true);
    	this.flush();
    }
    public void addPendingUserMessage(MessageContent content) {
    	if(content.isPlainText()) {
    		this.appendLine(Role.USER, content.toText(), true);
    	}else {
    		this.appendCh(Role.USER, content, true);
    	}
    }

    @Override
    public void appendReasoner(MessageContent current) {
        super.appendReasoner(current);
        if (current instanceof PlainText) {
            sendFrame(JsonBuilder.object()
                .add("type", "thinking")
                .add("content", ((PlainText) current).toText())
                .end().toString());
        } else if (current instanceof ToolCallContent) {
            for (ToolCall tc : ((ToolCallContent) current).getToolCalls()) {
                sendFrame(JsonBuilder.object()
                    .add("type", "tool_call")
                    .add("tool_name", tc.function.name)
                    .end().toString());
            }
        }
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
