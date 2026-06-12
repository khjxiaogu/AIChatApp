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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.mcp.AgentPingMcp;
import com.khjxiaogu.aiwuxia.mcp.CrontabMcp;
import com.khjxiaogu.aiwuxia.mcp.FetchMcp;
import com.khjxiaogu.aiwuxia.mcp.MultiModalMcp;
import com.khjxiaogu.aiwuxia.mcp.MusicMcp;
import com.khjxiaogu.aiwuxia.mcp.QQMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.mcp.SeedreamMcp;
import com.khjxiaogu.aiwuxia.objectstorage.TOSUsage;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession.CurrentContext;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.BotCallbackPromise;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.ResourceOrderManager;
import com.khjxiaogu.aiwuxia.utils.ResourceOrderManager.OrderHandle;
import com.khjxiaogu.aiwuxia.utils.ResourceOrderManager.ResourceAccess;
import com.khjxiaogu.aiwuxia.vision.ImageNoise;
import com.khjxiaogu.aiwuxia.voice.VoiceGenerationResult;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VoiceModelLocalServer;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;

public class NapCatAIConnector extends WebSocketClient {
	List<AIGroupSession> states=new ArrayList<>();

	Deque<Mid2Bid> mbid=new ArrayDeque<>(200);
	TOStorage tos;
	static class Mid2Bid{
		String mid;
		long bid;
		Mid2Bid(String mid, long bid) {
			super();
			this.mid = mid;
			this.bid = bid;
		}
	}
	JsonObject imgKey = new JsonObject();
	ExecutorService sendMessage=Executors.newFixedThreadPool(3);
	private final ResourceLock resourceLock = new ResourceLock(12);
	public NapCatAIConnector(File dataFolder, String url,
			String token, AIGroupSession... states) throws JsonSyntaxException, IOException, InterruptedException {
		super(URI.create("ws://" + url), Map.of("Authorization", "Bearer " + token));
		tos = new TOStorage(
				JsonParser.parseString(FileUtil.readString(new File(dataFolder, "tos.json"))).getAsJsonObject());


		imgKey = JsonParser.parseString(FileUtil.readString(new File(dataFolder, "img.json"))).getAsJsonObject();
		connectBlocking();
		for(AIGroupSession sess:states) {

			addTools(sess,dataFolder,sess.config);
			this.states.add(sess);
			sess.reasonerConsumer=data->{
				try {
					JsonObject jo = sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").add("echo","assistant_message").object("params")
							.add("group_id", sess.groupId)
							.object("message").add("type", "text").object("data").add("text", data.toString()).end().end()
							.end().end()).get();
					String mid=jo.get("message_id").getAsString();
					appendMid(mid,sess.botId);
					sess.addStatus("思维链消息id："+mid+"\n");
				} catch (InterruptedException | ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			};
			HistoryItem hi=sess.getHistory().peekLast();
			if(hi!=null&&hi.getRole()==Role.USER) {
				try(OrderHandle handle=manager.register()){
					float priceBefore=sess.getTokens();
					sess.getAiapp().doRegen(sess);
					sendLastMessage(sess,handle,sess.getTokens()-priceBefore);
				}
			}
			
		}
	}
	Object mbidLock=new Object();
	public void appendMid(String mid,long bid) {
		synchronized(mbidLock) {
			mbid.addLast(new Mid2Bid(mid,bid));
			while(mbid.size()>150) {
				mbid.pollFirst();
			}
		}
	}
	@FunctionalInterface
	public interface BotCallback{
		void call(int status,JsonObject data);
	}
	public Map<String,BotCallback> callBack=new ConcurrentHashMap<>();
	public void sendWithCallback(JsonObject jo,BotCallback callback) {
		String uid=UUID.randomUUID().toString();
		if(callback!=null)
			callBack.put(uid, callback);
		jo.addProperty("echo", uid);
		this.send(jo.toString());
	}
	public CompletableFuture<JsonObject> sendWithCallback(JsonObject jo) {
		String uid=UUID.randomUUID().toString();
		BotCallbackPromise cb=new BotCallbackPromise();
		callBack.put(uid, cb.getBotCallback());
		jo.addProperty("echo", uid);
		this.send(jo.toString());
		return cb.getFuture();
	}
    public static BufferedImage shrinkToQuarter(BufferedImage original) {
        if (original == null) {
            throw new IllegalArgumentException("原始图像不能为null");
        }
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();
        
        // 计算新尺寸，至少保证1x1
        int newWidth = Math.max(1, originalWidth / 8);
        int newHeight = Math.max(1, originalHeight / 8);
        
        // 选择新图像类型: 支持透明度则使用ARGB，否则RGB
        int imageType = original.getColorModel().hasAlpha() ?
                BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, imageType);
        
        Graphics2D g2d = scaledImage.createGraphics();
        try {
            // 设置高质量缩放算法
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            
            // 绘制缩放后的图像
            g2d.drawImage(original, 0, 0, newWidth, newHeight, 0, 0,
                    originalWidth, originalHeight, null);
        } finally {
            g2d.dispose();
        }
        ImageNoise.addLaplaceNoise(scaledImage, 17);
        return scaledImage;
    }
	public void addTools(AIGroupSession state,File dataFolder,JsonObject config) throws IOException {
		Set<String> skillSet=new HashSet<>();
		if(config.has("tools")) {
			for(JsonElement je:config.get("tools").getAsJsonArray()) {
				skillSet.add(je.getAsString());
			}
		}
		Map<String, LoraConfigurations> lora = new LinkedHashMap<>();
		File lfn=new File(dataFolder,"loras.json");
		if(lfn.exists()) {
			JsonObject refimg=JsonParser.parseString(FileUtil.readString(lfn)).getAsJsonObject();
			for(String key:refimg.keySet()) {
				JsonObject jo=refimg.get(key).getAsJsonObject();
				lora.put(key, new LoraConfigurations(jo.get("key").getAsString(),jo.get("weight").getAsFloat()));
			}
		}
		Map<String, String> refimage = new LinkedHashMap<>();
		File rfn=new File(dataFolder,"reference_image.json");
		if(rfn.exists()) {
			JsonObject refimg=JsonParser.parseString(FileUtil.readString(rfn)).getAsJsonObject();
			for(String key:refimg.keySet()) {
				refimage.put(key, refimg.get(key).getAsString());
			}
		}
		Map<String,String> emojis=new HashMap<>();
		if(config.has("emojis")) {
			JsonObject jo=config.get("emojis").getAsJsonObject();
			for(String je:jo.keySet()) {
				emojis.put(je,jo.get(je).getAsString());
			}
		}
		Function<String,String> imageCollector = image -> {
			try {
				beforeSendMessage(state);
				JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", state.groupId)
						.object("message").add("type", "image").object("data").add("file", image).end().end().end()
						.end()).get();

				appendMid(jo.get("message_id").getAsString(),state.botId);
				return "发送图片成功，消息id："+jo.get("message_id").getAsString();
			} catch(Exception e) {
				e.printStackTrace();
				return "发送失败";
			}
		};
		Function<String,String> nsfwImageCollector = image -> {
			beforeSendMessage(state);
			/*try {
				
				JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", state.groupId)
						.object("message").add("type", "image").object("data").add("file", image).end().end().end()
						.end()).get();

				appendMid(jo.get("message_id").getAsString(),state.botId);
				return "发送图片成功，消息id："+jo.get("message_id").getAsString();
			} catch(Exception e) {*/
				//e.printStackTrace();
				String url="https://www.khjxiaogu.com/works/imgView.html?img="+URLEncoder.encode(image,StandardCharsets.UTF_8);
				try(ByteArrayOutputStream baos=new ByteArrayOutputStream()){
					try(InputStream bais=FileUtil.fetch(image)){
						BufferedImage bi=ImageIO.read(bais);
						BufferedImage bo=shrinkToQuarter(bi);
						ImageIO.write(bo, "jpg", baos);
					}
					JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", state.groupId)
							.array("message").object().add("type", "image").object("data").add("file", toDataUrl(baos.toByteArray())).end().end().object().add("type", "text").object("data").add("text",url).end().end().end().end()
							.end()).get();
	
					appendMid(jo.get("message_id").getAsString(),state.botId);
					return "发送图片成功，消息id："+jo.get("message_id").getAsString();
				} catch(Exception e2) {
					e2.printStackTrace();
					try {
						JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", state.groupId)
								.array("message").object().add("type", "text").object("data").add("text", url).end().end().end().end()
								.end()).get();
						appendMid(jo.get("message_id").getAsString(),state.botId);
						return "发送图片成功，消息id："+jo.get("message_id").getAsString();
					} catch(Exception e3) {
						e3.printStackTrace();
						return "发送失败";
					}
				}
			//}
		};
		Consumer<String> imageIdCollector = image -> {
			state.addMessage(0, "", Arrays.asList(()->new PlainText("<tool>图片生成成功，尚未发送，图片id："+image+"</tool>")),true);
			submitMessage(state);
		};
		Consumer<String> videoCollector = image -> {
			this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params").add("group_id", state.groupId)
					.object("message").add("type", "video").object("data").add("file", image).end().end().end()
					.end(),(code,jo)->{
						if(code==0) {

							appendMid(jo.get("message_id").getAsString(),state.botId);
							state.addMessage(0, "", Arrays.asList(()->new PlainText("<tool>发送生成视频消息成功，消息id："+jo.get("message_id").getAsString()+"</tool>")),true);
							submitMessage(state);
						}
					});
		};
		Consumer<Throwable> exceptCollector = exc -> {
			state.addMessage(0, "", Arrays.asList(()->new PlainText("生成失败，原因："+exc.getMessage())),true);
			submitMessage(state);
		};
		if(skillSet.contains("fetch"))
			FetchMcp.create(state, tos).addTool(state.tools);

		if(skillSet.contains("multimodal"))
			MultiModalMcp.create(tos,state::addUsage).addTool(state.tools);

		if(skillSet.contains("qq")) {
			QQMcp.create(state, ""+state.groupId, tos,imageCollector,skillSet.contains("sdxl-nsfw")?nsfwImageCollector:null,emojis).addTool(state.tools);
		}
		if(skillSet.contains("sdxl"))
			SDXLMcp.create(state, tos, lora,false,resourceLock).addTool(state.tools);
		if(skillSet.contains("sdxl-nsfw"))
			SDXLMcp.create(state, tos, lora,true,resourceLock).addTool(state.tools);
		CrontabMcp.setPath(new File(dataFolder,"crontab.json"));
		if(skillSet.contains("cron"))
			CrontabMcp.create(state.botId, str->{
			state.addMessage(0,"",Arrays.asList(()->new PlainText("<trigger>"+str+"</trigger>")),true);
			submitMessage(state);
		}).addTool(state.tools);
		if(skillSet.contains("music"))
			MusicMcp.create(new File(dataFolder,"music"),config.get("voice").getAsString(),state.getRoleName(Role.ASSISTANT), fn->{
			try {
				this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params")
						.add("group_id", state.groupId).object("message").add("type", "record").object("data")
						.add("file", toDataUrl(FileUtil.readAll(fn))).end().end().end().end(),(code,jo)->{
							if(code==0) {

								appendMid(jo.get("message_id").getAsString(),state.botId);
								state.addStatus("发送音频消息id："+jo.get("message_id").getAsString()+"\n");
							}
						});
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}, fn -> {
			
			state.addMessage(0,"",Arrays.asList(()->new PlainText("<tool>歌声音频生成成功，已放到本地歌曲列表，名为：“"+fn+"”</tool>")),true);
			submitMessage(state);
		},url -> {
			try {
				beforeSendMessage(state);
				JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").object("params")
						.add("group_id", state.groupId).object("message").add("type", "record").object("data")
						.add("file", url).end().end().end().end()).get();
				appendMid(jo.get("message_id").getAsString(),state.botId);
				return "发送音乐成功，消息id："+jo.get("message_id").getAsString();
			} catch(Exception e) {
				e.printStackTrace();
				return "发送失败";
			}
		},resourceLock).addTool(state.tools);
		if(skillSet.contains("seedimage"))
			SeedreamMcp.createImage(state,imgKey, imageIdCollector,exceptCollector, refimage, tos).addTool(state.tools);
		if(skillSet.contains("seedvideo"))
			SeedreamMcp.createVideo(imgKey, videoCollector,exceptCollector, refimage, tos,state).addTool(state.tools);
		//SeedreamMcp.create(imgKey, imageCollector, refimage, tos).addTool(state.tools);
		AgentPingMcp.create(state.getRoleName(Role.ASSISTANT), (from,msg) -> {
			state.addMessage(state.botId, msg, Arrays.asList(()->new PlainText("<message senderName=\"" + from + "\">"+msg)),true);
			submitMessage(state);
		}).addTool(state.tools);

	}

	@Override
	public void onMessage(ByteBuffer bytes) {
	}

	@Override
	public void onOpen(ServerHandshake serverHandshake) {
		System.out.println("opened connection");
	}

	public byte[] getImageBytes(String fileId) throws Exception {
		// 1. 构造请求 JSON：{"file_id":urls "..."}
		return FileUtil.readAll(FileUtil.fetch(fileId));
	}
	Object lock = new Object();
	@Override
	public void onMessage(String message) {
		synchronized (lock) {
			try {

				
				JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
				//System.out.println("received message: " + message);
				if(msg.has("status")&&msg.has("echo")) {
					
					JsonElement data=msg.get("data");
					BotCallback cb=callBack.remove(msg.get("echo").getAsString());
					if(cb!=null) {
						JsonObject dataobj=null;
						if(data.isJsonObject())
							dataobj=data.getAsJsonObject();
						cb.call(msg.get("retcode").getAsInt(), dataobj);
					}
					return;
				}
				if(!msg.has("message_type"))
					return;
				String evt=msg.get("message_type").getAsString();
				/*if ("message_sent".equals(evt)) {
					
				}*/
				@SuppressWarnings("unused")
				long self=msg.get("self_id").getAsLong();
				if ("group".equals(evt)) {
					System.out.println("received group message: " + message);
					Set<Long> containsAt=new HashSet<>();
					long replyId=-1;
					String replyMsgId=null;
					Map<String, String> urls = new HashMap<>();
					for (JsonElement je : msg.get("message").getAsJsonArray()) {
						JsonObject nmsg = je.getAsJsonObject();
						String type=nmsg.get("type").getAsString();

						JsonObject data = nmsg.get("data").getAsJsonObject();
						
						if (type.equals("image")) {

							urls.put(data.get("file").getAsString(), data.get("url").getAsString());
						}else if(type.equals("at")) {
							if(data.has("qq")) {
								long atid=Long.parseLong(data.get("qq").getAsString());
								
								
								containsAt.add(atid);
							}
						}else if(type.equals("reply")) {
							if(data.has("id")) {
								String mid=data.get("id").getAsString();
								replyMsgId=mid;
								synchronized(mbidLock) {
									for(Mid2Bid msid:mbid) {
										if(mid.equals(msid.mid)) {
											replyId=msid.bid;
											break;
										}
									}
								}
							}
							
						}
					}
					if(containsAt.isEmpty()&&replyId!=-1) {
						containsAt.add(replyId);
					}
					for(AIGroupSession state:states) {
						if (msg.get("group_id").getAsLong() == state.groupId) {
							boolean containsAtMe = containsAt.contains(state.botId);
							
							String sender = "";
							JsonObject senderObj = msg.get("sender").getAsJsonObject();
							if (senderObj.has("card"))
								sender = senderObj.get("card").getAsString();
							if (sender.isEmpty())
								sender = senderObj.get("nickname").getAsString();
							long senderid = senderObj.get("user_id").getAsLong();
							StringBuilder textContent=new StringBuilder();
							if (senderid != state.botId) {
								JsonArray ja = msg.get("raw").getAsJsonObject().get("elements").getAsJsonArray();
								
								List<Supplier<MessageContent>> mes = new ArrayList<>();
								String senderName = "<message senderName=\"" + sender + "\" senderId=\"" + senderid
										+ "\" messageId=\"" + msg.get("message_id").getAsString() + "\">";
								mes.add(() -> new PlainText(senderName));
								if(containsAtMe)
									mes.add(() -> new PlainText("@" + state.getRoleName(Role.ASSISTANT)));
								boolean hasText = false;
								for (JsonElement je : ja) {
									JsonObject melm = je.getAsJsonObject();
									if (!melm.get("replyElement").isJsonNull()) {
										JsonObject reply = melm.get("replyElement").getAsJsonObject();
										StringBuilder msgBuilder = new StringBuilder();
										msgBuilder.append("<quote messageId=\"" + replyMsgId
												+ "\" senderId=\"" + reply.get("senderUid").getAsString() + "\">");
										for (JsonElement replys : reply.get("sourceMsgTextElems").getAsJsonArray()) {
											if (replys.getAsJsonObject().get("textElemContent").isJsonPrimitive())
												msgBuilder.append(
														replys.getAsJsonObject().get("textElemContent").getAsString());
										}
										msgBuilder.append("</quote>");
										mes.add(() -> new PlainText(msgBuilder.toString()));
										String senderId=reply.get("senderUid").getAsString();
						
										if (replyId==-1&&senderId.equals(String.valueOf(state.botId))) {
											containsAtMe=true;
										}
										continue;
									}
									if (!melm.get("textElement").isJsonNull()) {
										hasText = true;
										JsonObject text = melm.get("textElement").getAsJsonObject();
										String messageLine = text.get("content").getAsString();
										textContent.append(messageLine);
										if ((!containsAtMe)
												&& messageLine.contains("@" + state.getRoleName(Role.ASSISTANT)))
											containsAtMe = true;
										mes.add(() -> new PlainText(messageLine));
	
									} else if (!melm.get("picElement").isJsonNull()) {
										JsonObject pic = melm.get("picElement").getAsJsonObject();
										String summary = "（" + pic.get("summary").getAsString() + "）";
										String fid = urls.get(pic.get("fileName").getAsString());
										hasText = true;
										mes.add(() -> {
											try {
												byte[] data=getImageBytes(fid);
												state.addUsage(new TOSUsage(data.length));
												String path = tos.uploadIfNotExists(data);
												return new PlainText("<image id=\"" + path + "\" />");
											} catch (Exception e) {
												e.printStackTrace();
											}
											return new PlainText(summary);
	
										});
									}
								}
								if (hasText) {
									mes.add(() -> new PlainText("</message>"));
	
									state.addMessage(senderid,textContent.toString(),mes,false);
								}
								// int hours=new Date().getHours();
								// if(hours<6)
								// containsAtMe=false;
								if (containsAtMe) {
									
	
									this.send(JsonBuilder.object().add("action", "set_msg_emoji_like").object("params")
											.add("message_id", msg.get("message_id").getAsString()).add("emoji_id", "424").add("set", true).end().end().toString());
									submitMessage(state);
								}
	
							}
						}
					}

				}
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}
	public void notifyOtherAgents(AIGroupSession state,String msg,String mid) {
		for(AIGroupSession sess:states) {
			if(state!=sess&&state.groupId==sess.groupId)
				sess.addMessage(state.botId, msg, Arrays.asList(()->new PlainText("<message senderName=\"" + state.getRoleName(Role.ASSISTANT) + "\" id=\"" + mid + "\">"+msg)),true);
		}
	}
	public void sendLastMessage(AIGroupSession state,OrderHandle handle,float price) {
		state.printReasoning();
		HistoryItem hi = state.getLast();
		String msg=state.getLast().getDisplayContent().toString().trim();
		String priceStr="";
		if(state.outputPrice) {
			priceStr+="token:"+Math.round(price);
		}
		
		final String toWritePrice=priceStr;
		AutoCloseable closable=handle.fork();
		if (hi.getRole() == Role.ASSISTANT) {
			sendMessage.submit(()->{
				
				beforeSendMessage(state);
				try {
					if(msg==null||msg.isEmpty()) {
						JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").add("echo","assistant_message").object("params")
								.add("group_id", state.groupId)
								.object("message").add("type", "text").object("data").add("text", "生成完成。"+toWritePrice).end().end()
								.end().end()).get();
						String mid=jo.get("message_id").getAsString();
						appendMid(mid,state.botId);
						state.addStatus("上条消息id："+mid+"\n");
						notifyOtherAgents(state, msg, mid);
						
					}else {
						CompletableFuture<VoiceGenerationResult> dataFuture;
						try (ResourcePermit l=resourceLock.acquire(2)){
							dataFuture =VoiceModelHandler.getAudioData("", state.getRoleName(Role.ASSISTANT),"qbot", msg.replaceAll("枫茜", "枫西").replaceAll("茜茜", "西西"),UUID.randomUUID().toString(), state::addUsage);
						}
						try(ResourceAccess ac=handle.acquire()){
							
							JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").add("echo","assistant_message").object("params")
									.add("group_id", state.groupId)
									.object("message").add("type", "record").object("data").add("file", toDataUrl(dataFuture.get().audioData)).end().end()
									.end().end())
							.get();
							
							String mid=jo.get("message_id").getAsString();
	
							appendMid(mid,state.botId);
							state.addStatus("上条消息id："+mid+"\n");
							notifyOtherAgents(state, msg, mid);
							
							
						}
						
					}
				} catch (Throwable e) {
					if(!(e instanceof ModelRouteException))
						e.printStackTrace();
					try(ResourceAccess ac=handle.acquire()){
						JsonObject jo=this.sendWithCallback(JsonBuilder.object().add("action", "send_group_msg").add("echo","assistant_message").object("params")
								.add("group_id", state.groupId)
								.object("message").add("type", "text").object("data").add("text", msg+toWritePrice).end().end()
								.end().end()).get();
						String mid=jo.get("message_id").getAsString();
						appendMid(mid,state.botId);
						state.addStatus("上条消息id："+mid+"\n");
						notifyOtherAgents(state, msg, mid);
					} catch (Exception e1) {
						e1.printStackTrace();
					}
				}finally {
					try {
						closable.close();
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				
			});
			

		}
	}
	ResourceOrderManager manager=new ResourceOrderManager();
	Object globalLock=new Object();

	public void beforeSendMessage(AIGroupSession state) {
		
	}
	public void submitMessage(AIGroupSession state) {
		CurrentContext prompt;
		prompt=state.getPrompt();
		
		
		state.getCommandExec().submit(() -> {
			try(OrderHandle handle=manager.register()){
				float priceBefore=state.getTokens();
				String lst=state.getAndClearStatus();
				MutableMessageContents mst=new MutableMessageContents(lst);
				for(MessageContent msg:prompt.content)
					mst.add(msg);
				if(prompt.ctx.qq!=0)
					state.currentCtx=prompt.ctx;
				state.getAiapp().handleSpeech(state, mst);
				state.currentCtx=null;

			
				sendLastMessage(state,handle,state.getTokens()-priceBefore);
			}
		});
	}

	public static String toDataUrl(byte[] data) {
		if (data == null) {
			throw new IllegalArgumentException("字节数组不能为 null");
		}
		String base64 = Base64.getEncoder().encodeToString(data);
		return "data:;base64," + base64;
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
		if(remote)
			this.reconnect();
	}

	@Override
	public void onError(Exception e) {
		close(CloseFrame.NORMAL, e.toString());
	}

	public static void main(String[] args) throws Throwable {
		try {
			LLMConnector.initDefault();
			
			File dataFolder = new File("save");
			VoiceModelHandler.init(dataFolder);
			JsonArray jarr=JsonParser.parseString(FileUtil.readString(new File(dataFolder,"agents.json"))).getAsJsonArray();
			AIGroupSession[] states=new AIGroupSession[jarr.size()];
			int i=0;
			for(JsonElement jelm:jarr) {
				JsonObject config=jelm.getAsJsonObject();
				String name = config.get("name").getAsString();
				File saveData = new File(new File(dataFolder, "saveData"), config.get("save").getAsString());
				File modelFolder = new File(dataFolder, "apps/" + name);
				
				AIApplication main = AIApplicationRegistry.createInstance(dataFolder, modelFolder);
				AIGroupSession aistate = null;
				aistate = new AIGroupSession("appuser", AIApplication.saveDataFromJson(saveData),saveData, main,config);

				states[i++]=aistate;
			}
			new Thread(() -> {
				try {
					BasicWebServerBuilder.build().createURIRoot().createWrapper(new VoiceModelLocalServer())
							.rule("/aichat").complete().complete().setNotFound(new File(new File("save"), "404.html"))
							.compile().serverHttp(8998).info("http服务端已开启");
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}).start();
			new NapCatAIConnector(dataFolder, System.getProperty("napcat_url"),
					System.getProperty("napcat_token"),states);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

}
