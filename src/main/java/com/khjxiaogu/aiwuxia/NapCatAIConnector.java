package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.MemoryHistory;
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
			    			JsonArray ja=msg.get("message").getAsJsonArray();
			    			boolean containsAtMe=false;
			    			StringBuilder msgBuilder=new StringBuilder("【").append(sender).append("】「");
			    			boolean hasText=false;
			    			for(JsonElement je:ja) {
			    				JsonObject melm=je.getAsJsonObject();
			    				String type=melm.get("type").getAsString();
			    				
			    				if("text".equals(type)) {
			    					msgBuilder.append(melm.get("data").getAsJsonObject().get("text").getAsString());
			    					hasText=true;
			    				}else if("image".equals(type)) {
			    					msgBuilder.append("（图片：").append(melm.get("data").getAsJsonObject().get("summary").getAsString()).append("）");
			    				}else if("at".equals(type)) {
			    					String qq=melm.get("data").getAsJsonObject().get("qq").getAsString();
			    					if(String.valueOf(botId).equals(qq)) {
			    						msgBuilder.append("@"+state.aiapp.getName());
			    						containsAtMe=true;
			    						hasText=true;
			    					}
			    					
			    				}
			    			}
			    			if(hasText) {
			    				msgBuilder.append("」");
			    				String messageLine=msgBuilder.toString();
			    				System.out.println("grep message:"+messageLine);
			    				if((!containsAtMe)&&messageLine.contains("@"+state.aiapp.getName()))
			    					containsAtMe=true;
			    				state.addMessage(messageLine);
			    			}
			    			if(containsAtMe) {
			    				state.commandExec.submit(()->{
		    						state.aiapp.handleSpeech(state,state.getPrompt());
		    						try {
										AIApplication.saveToJson(state, saveData);
									} catch (IOException e) {
										e.printStackTrace();
									}
		    						HistoryItem hi=state.getLast();
		    						if(hi.getRole()==Role.ASSISTANT) {

		    							this.send(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", groupId).add("message", state.getLast().getContent().toString().trim()).end().end().toString());
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
    public static void main(String[] args) {
    	try {
    		String name="xinghanirc";
    		File dataFolder=new File("save");
    		File saveData = new File(new File(dataFolder,"saveData"), "savegroup-"+name+".json");
    		File modelFolder = new File(dataFolder,name);
    		AIApplication main = new AIGroupApplication(modelFolder,"汪星涵");
    		AIGroupSession aistate = null;
    		if (saveData.exists()) {
    			aistate = new AIGroupSession("appuser",
    				AIApplication.historyFromJson(saveData),
    				AIApplication.dataFromJson(saveData),
    				main);
    		}else {
    			aistate = new AIGroupSession("appuser",new MemoryHistory(), new AISession.AIData(),main);
    			aistate.provideInitial();
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
