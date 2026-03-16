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
package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Map;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

public class NapCatAIConnector  extends WebSocketClient {
	AIGroupSession state;
	File saveData;
	long botId;
	long groupId;
	String token;
	String url;
	Object lock=new Object();
	public NapCatAIConnector(AIGroupSession state, File saveData, long botId, long groupId,String url, String token) {
		super(URI.create(url),Map.of("Authorization", "Bearer "+token));
		this.state = state;
		this.saveData = saveData;
		this.botId = botId;
		this.groupId = groupId;
		this.token = token;
		this.url = url;
	}
	@Override
    public void onMessage(ByteBuffer bytes) {

    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
    	 System.out.println("opened connection");
    }
    
    @Override
    public void onMessage(String message) {
    	synchronized(lock) {
	    	try {
	    		JsonObject msg=JsonParser.parseString(message).getAsJsonObject();
		    	if(msg.has("message_type")&&"group".equals(msg.get("message_type").getAsString())) {
		    		if(msg.get("group_id").getAsLong()==groupId) {
		    			String sender="";
		    			JsonObject senderObj=msg.get("sender").getAsJsonObject();
		    			if(senderObj.has("card"))
		    				sender=senderObj.get("card").getAsString();
		    			if(sender.isEmpty())
		    				sender=senderObj.get("nickname").getAsString();
		    			long senderid=senderObj.get("user_id").getAsLong();
		    			if(senderid!=botId) {
			    			JsonArray ja=msg.get("raw").getAsJsonObject().get("elements").getAsJsonArray();
			    			boolean containsAtMe=false;
			    			StringBuilder msgBuilder=new StringBuilder("【").append(sender).append("】");
			    			boolean hasBarack=false;
			    			boolean hasText=false;
			    			for(JsonElement je:ja) {
			    				JsonObject melm=je.getAsJsonObject();
			    				if(!melm.get("replyElement").isJsonNull()) {
			    					JsonObject reply=melm.get("replyElement").getAsJsonObject();
			    					msgBuilder.append("回复“");
			    					for(JsonElement replys:reply.get("sourceMsgTextElems").getAsJsonArray()) {
			    						if(replys.getAsJsonObject().get("textElemContent").isJsonPrimitive())
			    						msgBuilder.append(replys.getAsJsonObject().get("textElemContent").getAsString());	
			    					}
			    					msgBuilder.append("”：");
			    					if(reply.get("senderUid").getAsString().equals(String.valueOf(botId))) {
			    						containsAtMe=true;
			    					}
			    					continue;
			    				}
			    				if(!hasBarack) {
			    					msgBuilder.append("「");
			    					hasBarack=true;
			    				}
			    				if(!melm.get("textElement").isJsonNull()) {
			    					hasText=true;
			    					JsonObject text=melm.get("textElement").getAsJsonObject();
			    					if(text.get("atType").getAsInt()!=0) {
			    						if(text.get("atUid").getAsString().equals(String.valueOf(botId))) {
			    							msgBuilder.append("@"+state.getAiapp().getRoleName(state, Role.ASSISTANT));
			    							containsAtMe=true;
			    							continue;
			    						}
			    					}
			    					msgBuilder.append(text.get("content").getAsString());
			    					
			    				}else if(!melm.get("picElement").isJsonNull()) {
			    					JsonObject pic=melm.get("picElement").getAsJsonObject();
			    					msgBuilder.append("（图片：").append(pic.get("summary").getAsString()).append("）");
			    				}
			    			}
			    			if(hasText) {
			    				msgBuilder.append("」");
			    				String messageLine=msgBuilder.toString();
			    				System.out.println("grep message:"+messageLine);
			    				if((!containsAtMe)&&messageLine.contains("@"+state.getAiapp().getRoleName(state, Role.ASSISTANT)))
			    					containsAtMe=true;
			    				state.addMessage(messageLine);
			    			}
			    			int hours=new Date().getHours();
			    			if(hours<6)
			    				containsAtMe=false;
			    			if(containsAtMe) {
			    				state.getCommandExec().submit(()->{
		    						state.getAiapp().handleSpeech(state,state.getPrompt());
		    						try {
										AIApplication.saveToJson(state, saveData);
									} catch (IOException e) {
										e.printStackTrace();
									}
		    						HistoryItem hi=state.getLast();
		    						if(hi.getRole()==Role.ASSISTANT) {

		    							this.send(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", groupId).add("message", state.getLast().getDisplayContent().toString().trim()).end().end().toString());
		    						}
		    						
		    					});
			    			}
			    				
		    			}
		    		}
		    		
		    	}
	    	}catch(Throwable t) {
	    		t.printStackTrace();
	    	}
    	}
        System.out.println("received message: " + message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
    }

    @Override
    public void onError(Exception e) {
        close(CloseFrame.NORMAL, e.toString());
    }
    public static void main(String[] args) throws Throwable {
    	try {
    		LLMConnector.initDefault();
    		String name="xinghanirc";
    		File dataFolder=new File("save");
    		File saveData = new File(new File(dataFolder,"saveData"), "savegroup-"+name+".json");
    		File modelFolder = new File(dataFolder,"apps/"+name);
    		AIApplication main = AIApplicationRegistry.createInstance(dataFolder, modelFolder);
    		AIGroupSession aistate = null;
    		if (saveData.exists()) {
    			aistate = new AIGroupSession("appuser",
    				AIApplication.historyFromJson(saveData),
    				AIApplication.dataFromJson(saveData),
    				main);
    		}else {
    			aistate = new AIGroupSession("appuser",new MemoryHistory(), new AISession.ExtraData(),main);
    			aistate.provideInitialHint();
    		}
    	
			new NapCatAIConnector(aistate,saveData,Long.parseLong(System.getProperty("bot")),Long.parseLong(System.getProperty("group")),System.getProperty("napcat_url"),System.getProperty("napcat_token")).connectBlocking();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

    }


}
