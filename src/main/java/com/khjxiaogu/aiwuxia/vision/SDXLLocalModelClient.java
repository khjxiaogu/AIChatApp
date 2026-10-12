package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient;
import com.khjxiaogu.aiwuxia.voice.ModelType;

public class SDXLLocalModelClient extends LocalModelClient {
	
	public SDXLLocalModelClient(URI serverUri,ResourceLock lock, boolean autoReconnect, long reconnectIntervalMs) {
		super(serverUri, Map.of("authorization","Bearer "+System.getProperty("localVoiceToken", "")), d->{
			try(ResourcePermit l=lock.acquire(12))  {
				System.out.println(d.get("data"));
				Gson gs=new Gson();
				return new Response(HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; utf-8")
				.url(d.get("path").getAsString())
				.post()
				.send(gs.fromJson(d.get("data").toString(), String.class))
				.readBytes(),"json");
		
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}, Set.of(ModelType.SDXL), autoReconnect, reconnectIntervalMs);
	}

}
