package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.MessageItem;
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

public class WebSocketAISession extends AISession implements WebsocketEvents {
	/**
	 * 
	 */
	private static final long serialVersionUID = -3405324710746609198L;
	ChannelGroup conn=new DefaultChannelGroup(new UnorderedThreadPoolEventExecutor(2));
	private final AIChatService parent;
	private final String chatId;
	File fn;
	AIApplication aiapp;
	boolean isClientAudioEnabled=false;
	public WebSocketAISession(AIChatService par,String uid,String chatid,AIApplication aiapp,File fn, HistoryHolder history, AIData data) {
		super(uid,history, data);
		this.fn = fn;
		this.parent=par;
		this.chatId=chatid;
		this.aiapp=aiapp;
	}


	@Override
	public void onOpen(Channel conn, FullHttpRequest handshake) {
		
		this.conn.add(conn);
		if(super.getStage()!=GameStage.INITIALIZE) {
			requireMoreMessages();
		}else {
			aiapp.provideInitial(this);
		}
		aiapp.prepareScene(this);
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating?1:0).add("price", this.getPrice()).add("isVoiceUsable",super.isAudioSession()).end().toString()));

	}
	
	public void requireMoreMessages() {
		int i = 0;
		List<HistoryItem> his = new ArrayList<>();
		for (Iterator<HistoryItem> it = history.reverseIterator(); it.hasNext();) {
			HistoryItem hisitem=it.next();
			his.add(0, hisitem);
			i++;
			if (i >= 20) break;
		}
		postMessages(his);
	}
	@Override
	public void onClose(Channel conn) {
		this.conn.remove(conn);
		if(this.conn.isEmpty()&&!isGenerating) {
			parent.markRelease(this);
		}
	}

	@Override
	public void onMessage(Channel conn, String message) {
		JsonObject jo=JsonParser.parseString(message).getAsJsonObject();
		if(jo.has("message")) {
			if(isGenerating) {
				conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("id",-1).add("title", "").add("message", "内容生成中，请稍后再试。").end().toString()));
				return;
			}
			isGenerating=true;
			aiapp.handleSpeech(this, jo.get("message").getAsString());
		}else if(jo.has("requestBackLog")) {
			requireMoreMessages();
		}else if(jo.has("voiceEnabled")) {
			this.isClientAudioEnabled=jo.get("voiceEnabled").getAsBoolean();
		}
	}

	@Override
	public void delMessage(int num) {
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("remove", num).end().toString()));
	}

	@Override
	public void appendMessage(int id, String message) {
		super.appendMessage(id, message);
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("delta", message).add("id", id).end().toString()));
	}

	@Override
	public void postMessage(int id, Role role, String message) {
		super.postMessage(id, role, message);
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("id", id).add("title", aiapp.getRoleName(this, role)).add("message", message).end().toString()));
	}

	@Override
	public void postAudioComplete(int id,String audioId) {
		super.postAudioComplete(id, audioId);
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("id", id).add("audioId", audioId).end().toString()));
	}
	@Override
	public void postMessages(List<HistoryItem> items) {
		super.postMessages(items);
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> ja=JsonBuilder.object().array("messages");
		for(HistoryItem i:items) {
			JsonObjectBuilder<JsonArrayBuilder<JsonObjectBuilder<JsonObject>>> ix=ja.object().add("id", i.getIdentifier()).add("title", aiapp.getRoleName(this, i.getRole())).add("message", i.getContent().toString());
			if(i.audioId!=null)
				ix.add("audioId", i.audioId);
		}
		conn.writeAndFlush(new TextWebSocketFrame(ja.end().end().toString()));
	}
	@Override
	public void onGenStart() {
		super.onGenStart();
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating?1:0).end().toString()));
	}
	public void setScene(String type,String value) {

		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("scene", type).add("scene_data", value).end().toString()));
	}

	@Override
	public void onGenComplete() {
		
		super.onGenComplete();
		parent.updateBrief(chatId, aiapp.getBrief(this));
		try {
			AIApplication.saveToJson(this, fn);
		} catch (IOException e) {
			e.printStackTrace();
		}
		isGenerating=false;
		String price=getPrice();
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating?1:0).add("price",price).end().toString()));
		parent.setPrice(chatId, price);
		if(this.conn.isEmpty()) {
			parent.markRelease(this);
		}
	}
	public boolean isAudioSession() {
		return isClientAudioEnabled&&super.isAudioSession();
	}
	public String getChatId() {
		return chatId;
	}

}
