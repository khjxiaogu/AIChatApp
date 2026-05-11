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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.tools.NameTranslator;
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
	protected final AIChatService parent;
	private final String chatId;
	File fn;
	boolean isLocalAudioEnabled=false;

	AtomicBoolean lock=new AtomicBoolean();
	ApplicationAttributes attributes;
	public WebSocketAISession(AIChatService par,String uid,String chatid,AIApplication aiapp,ApplicationAttributes attr,File fn, ISaveData data) {
		super(uid, data,aiapp);
		this.fn = fn;
		this.parent=par;
		this.chatId=chatid;
		this.attributes=attr;
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
		JsonArray models=new JsonArray();
		for(String s:attributes.models) {
			NameTranslator helper=parent.modelTranslations.get(s.split("/")[0]);
			String translate=s;
			if(helper!=null)
				translate=helper.translate(s);
			
			models.add(JsonBuilder.object().add("key", s).add("name", translate).end());
		}
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating()?1:0)
				.add("price", this.getPrice())
				.add("isVoiceEnabled",super.data.isAudioSession)
				.add("isVoiceUsable",(getAiapp().isLocalVoiceSupported()&&LocalVoiceModel.hasOnlineService()))
				.add("models", models)
				.add("model", getData().modelHint).end().toString()));

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
	public static Map<String,BiConsumer<JsonObject,WebSocketAISession>> operations=new HashMap<>();
	static{
		operations.put("setVoiceEnabled", (jo,state)->{
			state.data.isAudioSession=jo.get("voiceEnabled").getAsBoolean();
			state.sendFrame(JsonBuilder.object().add("isVoiceEnabled",state.data.isAudioSession).end().toString());
			state.save();
		});
		operations.put("revert", (jo,state)->{
			state.getCommandExec().submit(()->{
				state.getAiapp().doRevert(state);
			});
		});
		operations.put("regen", (jo,state)->{
			state.getCommandExec().submit(()->{
				state.getAiapp().doRegen(state);
			});
		});
		operations.put("prompt", (jo,state)->{
			state.getCommandExec().submit(()->{
				state.handleUserInput(jo.get("input").getAsString(), jo.get("content").getAsString());
				state.save();
			});
		});
		operations.put("model", (jo,state)->{
			final String model=jo.get("model").getAsString();
			if(model.isEmpty()||state.attributes.models.contains(model)) {
				state.getCommandExec().submit(()->{
					state.getData().modelHint=model;
					state.save();
				});
				state.sendFrame(JsonBuilder.object().add("model", model).end().toString());
			}else {
				state.sendFrame(JsonBuilder.object().add("model", state.getData().modelHint).end().toString());
			}
		});
		operations.put("genVoice", (jo,state)->{
			state.getCommandExec().submit(()->{
				HistoryItem last=state.getLast();
				if(last.getRole()==Role.ASSISTANT) {
					if(last.getAudioId()==null) {
						String audioId=UUID.randomUUID().toString();
						CompletableFuture<Boolean> cf=state.getAiapp().generateVoice(state, last.getDisplayContent().toString(), audioId);
						try {
							if(cf.get()) {
								last.setAudioId(audioId);
								state.postAudioComplete(last.getIdentifier(),audioId);
							}
						} catch (InterruptedException | ExecutionException e) {
							e.printStackTrace();
						}
					}
				}
			});
		});
	}
	public boolean onModifyAttempt() {
		if(isGenerating)return false;
		return lock.compareAndSet(false, true);
	}
	public void onModifyComplete() {
		lock.set(false);
	}
	@Override
	public void onMessage(Channel conn, String message) {
		JsonObject jo=JsonParser.parseString(message).getAsJsonObject();
		if(jo.has("message")) {
			if(lock.compareAndSet(false, true)) {
				refillChatBox(new MessageContents());
				getCommandExec().submit(()->{
					try {
						getAiapp().handleSpeech(this,new MessageContents( jo.get("message").getAsString()));
						if(getAiapp().isLocalVoiceSupported()&&!super.isAudioSession()) {
							if(isLocalAudioEnabled!=LocalVoiceModel.hasOnlineService()) {
								isLocalAudioEnabled=LocalVoiceModel.hasOnlineService();
								conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("isVoiceUsable",isLocalAudioEnabled).end().toString()));
							}
						}
					}finally{
						lock.set(false);
					}
				});
				
			}else {
				sendNotice("操作太快啦，请稍后再试");
			}
		}else if(jo.has("requestBackLog")) {
			requireMoreMessages();
		}else if(jo.has("operation")) {
			String operation=jo.get("operation").getAsString();
			BiConsumer<JsonObject, WebSocketAISession> op=operations.get(operation);
			if(op!=null)
				op.accept(jo, this);
		}
	}

	@Override
	public void requestUserInput(String input, String prompt, Consumer<String> consumer) {
		sendFrame(JsonBuilder.object().add("input", input).add("prompt", prompt).end().toString());
		super.requestUserInput(input, prompt, consumer);
	}


	@Override
	public void sendNotice(String msg) {
		super.sendNotice(msg);
		sendFrame(JsonBuilder.object().add("notice", msg).end().toString());
	}


	@Override
	public void delMessage(int num) {
		sendFrame(JsonBuilder.object().add("remove", num).end().toString());
	}

	@Override
	public void appendMessage(int id, String message) {
		super.appendMessage(id, message);
		sendFrame(JsonBuilder.object().add("delta", message).add("id", id).end().toString());
	}

	@Override
	public void postMessage(int id, Role role, String message) {
		super.postMessage(id, role, message);
		sendFrame(JsonBuilder.object().add("id", id).add("title", getRoleName(role)).add("message", message).end().toString());
	}
	public void sendFrame(String content) {
		conn.writeAndFlush(new TextWebSocketFrame(content));
	}
	@Override
	public void postAudioComplete(int id,String audioId) {
		super.postAudioComplete(id, audioId);
		sendFrame(JsonBuilder.object().add("id", id).add("audioId", audioId).end().toString());
	}
	@Override
	public void postMessages(List<HistoryItem> items) {
		super.postMessages(items);
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> ja=JsonBuilder.object().array("messages");
		for(HistoryItem i:items) {
			JsonObjectBuilder<JsonArrayBuilder<JsonObjectBuilder<JsonObject>>> ix=ja.object().add("id", i.getIdentifier()).add("title", getRoleName(i.getRole())).add("message", i.getDisplayContent().toString());
			if(i.getAudioId()!=null)
				ix.add("audioId", i.getAudioId());
		}
		sendFrame(ja.end().end().toString());
	}
	@Override
	public void onGenerateStart() {
		super.onGenerateStart();
		sendFrame(JsonBuilder.object().add("status", isGenerating()?1:0).end().toString());
	}
	public void sendSceneContent(String type,String value) {

		sendFrame(JsonBuilder.object().add("scene", type).add("scene_data", value).end().toString());
	}
	public void save() {
		try {
			AIApplication.saveToJson(this, fn);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	@Override
	public void onGenComplete() {
		
		
		parent.updateBrief(chatId, getAiapp().getBrief(this));
		save();
		String price=getPrice();
		
		parent.setPrice(chatId, price);
		super.onGenComplete();
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("status", isGenerating()?1:0).add("price",price).end().toString()));
		if(this.conn.isEmpty()) {
			parent.markRelease(this);
		}
	}
	public String getChatId() {
		return chatId;
	}
	@Override
	public void addUsage(UsageIntf<?> usage) {
		if(attributes.paidOnly) {
			int actualCost=(int) Math.ceil(usage.getEquivantTokens());
		
			if(actualCost>0) {
				parent.getLogger().info("已消费："+actualCost);
				parent.consumePaidTokens(user, actualCost);
			}
			
		}
		if(!attributes.freeNow) {
			int actualCost=(int) Math.floor(usage.getEquivantTokens());
			if(actualCost>0) {
				parent.getLogger().info("已消费："+actualCost);
				parent.consumeTokens(user, actualCost);
			}
		}
		
		super.addStatUsage(usage);
	}
	@Override
	public boolean canGenerate() {
		if(attributes.paidOnly)
			return parent.hasAnyPaidTokenRemaining(user);
		if(!attributes.freeNow)
			return parent.hasAnyTokenRemaining(user);
		return true;
	}
	@Override
	public void refillChatBox(MessageContents text) {
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("sendbox",text.toText()).end().toString()));
	}








}
