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
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;

public class PainterSession extends AISession implements WebsocketEvents,ChatIdentity{

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(MessageContents.class, new MessageContents.Serilizer<>())
        .create();

    /** 单页历史记录条数 */
    private static final int HISTORY_PAGE_SIZE = 20;
    /** 单页历史记录条数上限，防止客户端一次请求过多 */
    private static final int HISTORY_PAGE_MAX = 100;

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
                // 支持向上滚动加载更多：before_id 指定只加载比该ID更早的记录，缺省(或<=0)则加载最新一页
                int beforeId = -1;
                if (jo.has("before_id") && !jo.get("before_id").isJsonNull()) {
                    beforeId = jo.get("before_id").getAsInt();
                }
                int limit = HISTORY_PAGE_SIZE;
                if (jo.has("limit") && !jo.get("limit").isJsonNull()) {
                    limit = jo.get("limit").getAsInt();
                }
                requireMoreMessages(beforeId, limit);
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
    /**
     * 请求最新一页历史记录（连接建立时的初始加载）。
     */
    public void requireMoreMessages() {
        requireMoreMessages(-1, HISTORY_PAGE_SIZE);
    }

    /**
     * 分页请求历史记录。
     * <p>
     * 历史条目按标识符升序排列，本方法从最新往最旧遍历，取 {@code beforeId} 之前的
     * {@code limit} 条记录发送给客户端，并告知是否还有更早的记录。
     * 客户端向上滚动时携带当前最旧记录的标识符作为 {@code beforeId} 即可继续加载。
     * </p>
     *
     * @param beforeId 只返回标识符严格小于该值的历史条目；小于等于0表示从最新记录开始（初始加载）
     * @param limit    本页最多返回的条目数
     */
    public synchronized void requireMoreMessages(int beforeId, int limit) {
        if (limit <= 0) {
            limit = HISTORY_PAGE_SIZE;
        } else if (limit > HISTORY_PAGE_MAX) {
            limit = HISTORY_PAGE_MAX;
        }
        List<HistoryItem> his = new ArrayList<>();
        boolean hasMore = false;
        for (Iterator<HistoryItem> it = getHistory().reverseIterator(); it.hasNext();) {
            HistoryItem hisitem = it.next();
            if (beforeId > 0 && hisitem.getIdentifier() >= beforeId) continue;
            if (his.size() >= limit) {
                // 已经取满一页且还有更旧的记录
                hasMore = true;
                break;
            }
            his.add(0, hisitem);
        }
        // beforeId > 0 说明这是向上滚动加载的历史页，客户端需要插入到列表最前面
        postMessages(his, beforeId > 0, hasMore);
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
        postMessages(items, false, false);
    }

    /**
     * 批量发送历史记录，并附带分页信息。
     *
     * @param items   按时间从旧到新排列的历史条目
     * @param prepend {@code true} 表示这批记录比客户端已有的记录更早，客户端应插入到列表最前面
     * @param hasMore 是否还有更早的历史记录可以继续加载
     */
    public void postMessages(List<HistoryItem> items, boolean prepend, boolean hasMore) {
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
        outer.add("prepend", prepend);
        outer.add("hasMore", hasMore);
        JsonObject frame = outer.end();
        if (!items.isEmpty()) {
            // 客户端可用 oldestId 作为下一次向上滚动的游标
            frame.addProperty("oldestId", items.get(0).getIdentifier());
            frame.addProperty("newestId", items.get(items.size() - 1).getIdentifier());
        }
        sendFrame(frame.toString());
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
