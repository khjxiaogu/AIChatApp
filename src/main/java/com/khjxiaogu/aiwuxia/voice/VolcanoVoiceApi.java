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
package com.khjxiaogu.aiwuxia.voice;

import java.io.IOException;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
/**
 * 火山引擎声音复刻API。
 * */
public class VolcanoVoiceApi implements VoiceModel {
	@Override
	public byte[] getAudioData(String botid,String uid,String text,String rid) throws IOException {
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
