package com.khjxiaogu.aiwuxia.voice;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
/**
 * 本地部署模型服务对象
 * 
 * */
public class LocalModelHandshaker implements WebsocketEvents {
	private static class Result{
		boolean finished;
		byte[] data;
	}
	private final BlockingQueue<Channel> pool=new LinkedBlockingQueue<>();
	private final Map<String,Result> ars=new ConcurrentHashMap<>();
	public LocalModelHandshaker() {
		super();
	}
	int num=0;
	@Override
	public void onOpen(Channel conn, FullHttpRequest handshake) {
		this.pool.offer(conn);
		num++;
	}

	@Override
	public void onClose(Channel conn) {
		pool.remove(conn);
		num--;
	}

	@Override
	public void onMessage(Channel conn, String message) {
		JsonObject jo=JsonParser.parseString(message).getAsJsonObject();
		Result ar=ars.remove(jo.get("reqid").getAsString());
		System.out.println("received "+message+ar);
		if(ar!=null) {
			synchronized(ar) {
				if(!jo.has("error")) {
					ar.data=Base64.getDecoder().decode(jo.get("data").getAsString());
				}
				ar.finished=true;
				ar.notifyAll();
			}
			
		}
	}
	public void onMessage(String reqid,byte[] data) {
		Result ar=ars.remove(reqid);
		if(ar!=null) {
			ar.data=data;
			ar.finished=true;
			
		}
	}
	public boolean hasOnlineService() {
		return num>0;
	}
	public CompletableFuture<byte[]> requireAudio(String chara,String emote,String reqid,String text) {
		if(pool.isEmpty())
			return CompletableFuture.completedFuture(null);
		JsonObject request=JsonBuilder.object().add("chara", chara).add("emote", emote).add("reqid", reqid).add("text", text).add("type", "audiorequest").end();
		return CompletableFuture.supplyAsync(()->{
			while(true) {
				if(pool.isEmpty())
					return null;
				Channel ch=null;
				try {
					ch=pool.poll(5,TimeUnit.SECONDS);
					Result result=new Result();
					ars.put(reqid, result);
					ch.writeAndFlush(new TextWebSocketFrame(request.toString()));
					synchronized(result) {
						long beginTime=System.currentTimeMillis();
						long endTime=beginTime+1000*60*3;//3分钟
						while(true){
							long currTime=System.currentTimeMillis();
							if(!ch.isActive()||currTime>=endTime)
								return null;
							if(result.finished) {
								return result.data;
							}
							result.wait(endTime-currTime);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}finally {
					if(ch!=null)
						pool.offer(ch);
				}

				
			}
		});
		
	}


}
