package com.khjxiaogu.aiwuxia.voice;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.providers.mimo.MimoRespScheme;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

public class MimoVoiceApi implements VoiceModel {
	final File sampleVoice;
	Gson gson=new Gson();
	@Override
	public CompletableFuture<ModelGenerationResult> getAudioData(String roleName,String uid, String text,String messageId,Consumer<UsageIntf<?>> usageListener) {
		try {
			String caption="";
			File captionFile=new File(sampleVoice,roleName+".txt");
			if(captionFile.exists()) {
				try {
					caption=FileUtil.readString(captionFile);
				} catch (IOException e) {
					e.printStackTrace();
					return CompletableFuture.failedFuture(e);
				}
			}
			JsonObject request = JsonBuilder.object().add("model", "mimo-v2.5-tts-voiceclone")
			.array("messages")
			.object().add("role", "user").add("content", caption).end()
			.object().add("role", "assistant").add("content", text).end()
			.end()
			.object("audio").add("format", "mp3").add("voice", toDataUrl(FileUtil.readAll(new File(sampleVoice,roleName+".mp3")))).end()
			.end();
			return CompletableFuture.supplyAsync(()->{
			try {
				JsonObject retjs = HttpRequestBuilder.create("api.xiaomimimo.com").url("/v1/chat/completions")
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer "+System.getProperty("mimotoken"))
						.post(true).send(gson.toJson(request)).readJson();
				
				RespScheme scheme=gson.fromJson(retjs, MimoRespScheme.class);
				PcmToWavConverter converter=new PcmToWavConverter(24000);
				converter.addAudioChunk(scheme.choices.get(0).message.audio.data);
				usageListener.accept(new LocalVoiceUsage(text.length()));
				return new ModelGenerationResult(converter.saveAsWav(),"wav");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			});
		} catch (IOException e) {
			e.printStackTrace();
			return CompletableFuture.failedFuture(e);
		}
		
		
	}
	public static String toDataUrl(byte[] data) {
		if (data == null) {
			throw new IllegalArgumentException("字节数组不能为 null");
		}
		String base64 = Base64.getEncoder().encodeToString(data);
		return "data:audio/mp3;base64," + base64;
	}
	MimoVoiceApi(File file) {
		super();
		this.sampleVoice = new File(file,"mimovoice");
	}
	@Override
	public boolean canProcessVoice(String roleName) {
		return new File(sampleVoice,roleName+".mp3").exists();
	}
	@Override
	public boolean isHinted(String modelName) {
		return "mimo".equals(modelName);
	}
}
