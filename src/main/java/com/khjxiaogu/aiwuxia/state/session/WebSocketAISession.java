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
package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceModel;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;

public class WebSocketAISession extends AISession implements WebsocketEvents {

	ChannelGroup conn=new DefaultChannelGroup(new UnorderedThreadPoolEventExecutor(3));
	private final AIChatService parent;
	private final String chatId;
	File fn;
	

	ReentrantLock lock=new ReentrantLock();
	boolean isClientAudioEnabled=false;
	boolean isLocalAudioEnabled=false;
	public WebSocketAISession(AIChatService par,String uid,String chatid,AIApplication aiapp,File fn, HistoryHolder history, ExtraData data) {
		super(uid,history, data,aiapp);
		this.fn = fn;
		this.parent=par;
		this.chatId=chatid;
	}


	@Override
	public void onOpen(Channel conn, FullHttpRequest handshake) {
		
		this.conn.add(conn);

		if(super.getStage()!=ApplicationStage.INITIALIZE) {
			requireMoreMessages();
		}else {
			provideInitialHint();
		}
		getAiapp().prepareScene(this);
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating()?1:0).add("price", this.getPrice()).add("isVoiceUsable",super.isAudioSession()||(getAiapp().isLocalVoiceSupported()&&LocalVoiceModel.hasOnlineService())).end().toString()));

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
		if(this.conn.isEmpty()&&!isGenerating()) {
			parent.markRelease(this);
		}
	}

	@Override
	public void onMessage(Channel conn, String message) {
		JsonObject jo=JsonParser.parseString(message).getAsJsonObject();
		if(jo.has("message")) {
			if(lock.tryLock()) {
				try {
					getCommandExec().submit(()->{
						getAiapp().handleSpeech(this, jo.get("message").getAsString());
						if(getAiapp().isLocalVoiceSupported()&&!super.isAudioSession()) {
							if(isLocalAudioEnabled!=LocalVoiceModel.hasOnlineService()) {
								isLocalAudioEnabled=LocalVoiceModel.hasOnlineService();
								conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("isVoiceUsable",isLocalAudioEnabled).end().toString()));
							}
						}
					});
				}finally {
					lock.unlock();
				}
			}else {
				conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("id",-1).add("title", "").add("message", "操作太快啦，请稍后再试").end().toString()));
			}
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
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("id", id).add("title", getAiapp().getRoleName(this, role)).add("message", message).end().toString()));
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
			JsonObjectBuilder<JsonArrayBuilder<JsonObjectBuilder<JsonObject>>> ix=ja.object().add("id", i.getIdentifier()).add("title", getAiapp().getRoleName(this, i.getRole())).add("message", i.getDisplayContent().toString());
			if(i.getAudioId()!=null)
				ix.add("audioId", i.getAudioId());
		}
		conn.writeAndFlush(new TextWebSocketFrame(ja.end().end().toString()));
	}
	@Override
	public void onGenerateStart() {
		super.onGenerateStart();
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating()?1:0).end().toString()));
	}
	public void sendSceneContent(String type,String value) {

		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("scene", type).add("scene_data", value).end().toString()));
	}

	@Override
	public void onGenComplete() {
		
		
		parent.updateBrief(chatId, getAiapp().getBrief(this));
		try {
			AIApplication.saveToJson(this, fn);
		} catch (IOException e) {
			e.printStackTrace();
		}
		String price=getPrice();
		
		parent.setPrice(chatId, price);
		super.onGenComplete();
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating()?1:0).add("price",price).end().toString()));
		if(this.conn.isEmpty()) {
			parent.markRelease(this);
		}
	}

	public boolean isAudioSession() {
		return isClientAudioEnabled&&(isLocalAudioEnabled||super.isAudioSession());
	}
	public String getChatId() {
		return chatId;
	}
	@Override
	public void addUsage(Usage usage) {
		int uncached_cost=0;
		if(usage.prompt_cache_hit_tokens==0&&usage.prompt_cache_miss_tokens==0) {
			uncached_cost+=usage.prompt_tokens;
		}else {
			uncached_cost+=usage.prompt_cache_miss_tokens;
			uncached_cost+=usage.prompt_cache_hit_tokens/10;
		}
		uncached_cost+=usage.completion_tokens;
		parent.consumeTokens(user, uncached_cost);
		
		super.addUsage(usage);
	}
	@Override
	public boolean canGenerate() {
		return parent.hasAnyTokenRemaining(user);
	}
}
