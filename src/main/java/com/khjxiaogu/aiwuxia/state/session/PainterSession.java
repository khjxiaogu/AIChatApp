package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.utils.BotCallbackPromise;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;

public class PainterSession extends AISession implements WebsocketEvents,ChatIdentity{

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(MessageContents.class, new MessageContents.Serilizer<>())
        .create();

    ChannelGroup conn = new DefaultChannelGroup(new UnorderedThreadPoolEventExecutor(3));
    protected final AIChatService parent;
    private final String chatId;
    ApplicationAttributes attributes;
    AtomicBoolean lock = new AtomicBoolean();
    public List<ToolData> tools=new ArrayList<>();
    private final ConcurrentHashMap<String, BotCallbackPromise> pendingLocalRequests = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong LOCAL_REQUEST_ID = new java.util.concurrent.atomic.AtomicLong(0);
    @Override
	public List<ToolData> getAvailableTools() {
		return tools;
	}
	public PainterSession(AIChatService par, String uid, String chatid, AIApplication aiapp,
                          ApplicationAttributes attr, File fn, ISaveData data) {
        super(uid, data, aiapp);
        this.parent = par;
        this.chatId = chatid;
        this.attributes = attr;
		MultiModalMcp.create(par.getPainterStorage(), this::addUsage).addTool(this.tools);
        MultiModalMcp.createOutput(this,par.getPainterStorage(), Collections.emptyMap(),this::addUsage).addTool(this.tools);

        SDXLMcp.createRemoteLocal(this, par.getPainterStorage(), par.lora,par.chara,imgid->par.urlbase+"/painter/image?conversation_id="+chatid+"&image_id="+imgid, true).addTool(this.tools);
        FetchMcp.create(this, par.getPainterStorage());
    }

    @Override
    public void onOpen(Channel conn, FullHttpRequest handshake) {
        this.conn.add(conn);
        provideInitialHint();
        requireMoreMessages();
        
        getAiapp().prepareScene(this);
        conn.writeAndFlush(new TextWebSocketFrame(
            JsonBuilder.object()
                .add("type", "status")
                .add("generating", isGenerating())
                .add("conversation_id", chatId)
                .end().toString()));
    }

    @Override
    public void onClose(Channel conn) {
        this.conn.remove(conn);
        if (this.conn.isEmpty() && !isGenerating()) {
            parent.markRelease(this);
        }
    }

    public boolean onModifyAttempt() {
        if (isGenerating) return false;
        return lock.compareAndSet(false, true);
    }

    public void onModifyComplete() {
        lock.set(false);
    }

    @Override
    public void onMessage(Channel conn, String message) {
        JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
        if (jo.has("type")) {
            String type = jo.get("type").getAsString();
            if ("message".equals(type)) {
                String content = jo.get("content").getAsString();
                addPendingUserMessage(content);
            } else if ("generate".equals(type)) {
                if (lock.compareAndSet(false, true)) {
                    
                    getCommandExec().submit(() -> {
                        try {
                            getAiapp().handleSpeech(this, MessageContents.EMPTY);
                            refillChatBox(MessageContents.EMPTY);
                        } finally {
                            lock.set(false);
                        }
                    });
                } else {
                    sendNotice("操作太快啦，请稍后再试");
                }
            } else if ("requestBackLog".equals(type)) {
                requireMoreMessages();
            }
        }
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
    public void requireMoreMessages() {
        int i = 0;
        List<HistoryItem> his = new ArrayList<>();
        for (Iterator<HistoryItem> it = getHistory().reverseIterator(); it.hasNext();) {
            HistoryItem hisitem = it.next();
            his.add(0, hisitem);
            i++;
            if (i >= 20) break;
        }
        postMessages(his);
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

    @Override
    public void sendNotice(String msg) {
        super.sendNotice(msg);
        sendFrame(JsonBuilder.object()
            .add("type", "notice")
            .add("content", msg)
            .end().toString());
    }

    @Override
    public void delMessage(int num) {
        sendFrame(JsonBuilder.object()
            .add("type", "remove")
            .add("id", num)
            .end().toString());
    }

    @Override
    public void appendMessage(int id, String message) {
        super.appendMessage(id, message);
        sendFrame(JsonBuilder.object()
            .add("type", "content")
            .add("id", id)
            .add("content", message)
            .end().toString());
    }

    @Override
    public void appendMessage(int id, MessageContent content) {
        sendFrame(JsonBuilder.object()
            .add("id", id)
            .add("type", "content")
            .add("content", contentsToJson(new MutableMessageContents(content)))
            .end().toString());
    }

    @Override
    public void postMessage(int id, Role role, MessageContents message) {
        super.postMessage(id, role, message);
        sendFrame(JsonBuilder.object()
            .add("id", id)
            .add("title", getRoleName(role))
            .add("type", "message")
            .add("content", contentsToJson(message))
            .end().toString());
    }

    @Override
    public void postMessages(List<HistoryItem> items) {
        super.postMessages(items);
        JsonArrayBuilder<JsonObjectBuilder<JsonObject>> ja = JsonBuilder.object().array("messages");
        for (HistoryItem i : items) {
        	JsonArray thinking=new JsonArray();
        	if(i.getReasoningContent()!=null&&i.getRole()==Role.ASSISTANT)
	        	for(MessageContent current:i.getReasoningContent()) {
		        	
		        	if (current instanceof PlainText) {
		        		thinking.add(((PlainText) current).toText());
		            } else if (current instanceof ToolCallContent) {
		                for (ToolCall tc : ((ToolCallContent) current).getToolCalls()) {
		                	thinking.add(JsonBuilder.object()
		                        .add("type", "tool_call")
		                        .add("tool_name", tc.function.name)
		                        .end());
		                }
		            }
	        	}
            ja.object()
                .add("id", i.getIdentifier())
                .add("title", getRoleName(i.getRole()))
                .add("thinking", thinking)
                .add("content", contentsToJson(i.getDisplayContent()));
        }
        JsonObjectBuilder<JsonObject> outer = ja.end();
        outer.add("type", "batch_messages");
        sendFrame(outer.end().toString());
    }

    @Override
    public void postAudioComplete(int id, String audioId) {
        super.postAudioComplete(id, audioId);
        sendFrame(JsonBuilder.object()
            .add("type", "audio")
            .add("id", id)
            .add("audioId", audioId)
            .end().toString());
    }

    @Override
    public void onGenerateStart() {
        super.onGenerateStart();
        sendFrame(JsonBuilder.object()
            .add("type", "status")
            .add("generating", true)
            .end().toString());
    }

    @Override
    public void onGenComplete() {
        parent.updateBrief(chatId, getAiapp().getBrief(this));
        String price = getPrice();
        parent.setPrice(chatId, price);
        super.onGenComplete();
        sendFrame(JsonBuilder.object()
            .add("type", "done")
            .add("price", price)
            .end().toString());
        if (this.conn.isEmpty()) {
            parent.markRelease(this);
        }
    }

    @Override
    public void refillChatBox(MessageContents text) {
        sendFrame(JsonBuilder.object()
            .add("type", "sendbox")
            .add("content", text.toText())
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

    public void sendFrame(String content) {
        conn.writeAndFlush(new TextWebSocketFrame(content));
    }
    @Override
    public String getChatId() {
        return chatId;
    }

    private static JsonElement contentsToJson(MessageContents contents) {
        if (contents == null) {
            return new JsonArray();
        }
        return GSON.toJsonTree(contents, MessageContents.class);
    }
}
