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

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

/**
 * 火山引擎声音复刻API。
 */
public class VolcanoVoiceApi implements VoiceModel {
	Map<String,String> botids;
	Gson gs=new Gson();
	@SuppressWarnings("unchecked")
	public VolcanoVoiceApi(File file) throws JsonSyntaxException, IOException {
		super();
		this.botids=gs.fromJson(FileUtil.readString(new File(file,"volcanovoice.json")), Map.class);
	}
	@Override
	public CompletableFuture<ModelGenerationResult> getAudioData(String roleName, String uid, String text, String rid,
			Consumer<UsageIntf<?>> usageListener) {
		String appid = System.getProperty("volcappid");
		String accessToken = System.getProperty("volcsecret");
		JsonObject send = JsonBuilder.object().object("app").add("appid", appid).add("token", accessToken)
				.add("cluster", "volcano_icl").end().object("user").add("uid", "aichatapp@" + uid).end().object("audio")
				.add("voice_type", botids.get(roleName)).add("encoding", "mp3").end().object("request").add("reqid", rid)
				.add("operation", "query").add("text", text.replaceAll("（[^）]+）", "")).end().end();
		JsonObject data;
		try {
			data = new HttpRequestBuilder("https://openspeech.bytedance.com/api/v1/tts")
					.header("Authorization", "Bearer;" + accessToken)
					.header("Content-Type", "application/json; charset=utf-8").post(true).send(gs.toJson(send))
					.readJson();
			if (data.has("data")) {
				usageListener.accept(new VolcanoVoiceUsage(text.length()));
				return CompletableFuture.completedFuture(
						new ModelGenerationResult(Base64.getDecoder().decode(data.get("data").getAsString()), "mp3"));
			}

			return CompletableFuture.failedFuture(new IOException("API Returned " + data));
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}

	}

	@Override
	public boolean canProcessVoice(String roleName) {
		return botids.containsKey(roleName);
	}
	@Override
	public boolean isHinted(String modelName) {
		return "volcano".equals(modelName);
	}
}
