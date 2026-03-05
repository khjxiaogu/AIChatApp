package com.khjxiaogu.aiwuxia;

import java.io.IOException;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

public class VolcanoApi {
	public static byte[] getAudioData(String botid,String uid,String text,String rid) throws IOException {
        String appid = System.getProperty("volcappid");
        String accessToken = System.getProperty("volcsecret");
        JsonObject send=JsonBuilder.object().object("app").add("appid", appid).add("token", accessToken).add("cluster", "volcano_icl").end()
        .object("user").add("uid", "aichatapp@"+uid).end()
        .object("audio").add("voice_type", botid).add("encoding", "mp3").end()
        .object("request").add("reqid",rid).add("operation", "query").add("text", text).end()
        .end();
        JsonObject data=new HttpRequestBuilder("https://openspeech.bytedance.com/api/v1/tts")
        		.header("Authorization", "Bearer;"+accessToken)
        		.header("Content-Type", "application/json; charset=utf-8")
        		.post(true).send(send.toString()).readJson();
        if(data.has("data"))
        	return Base64.getDecoder().decode(data.get("data").getAsString());
        throw new IOException("API Returned "+data);
	}
}
