package com.khjxiaogu.aiwuxia.voice;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class LocalVoiceApi implements VoiceModel {
	VoiceTagger vt;
	Map<String,String> botids;
	Gson gs=new Gson();
	@SuppressWarnings("unchecked")
	public LocalVoiceApi(File file) throws JsonSyntaxException, IOException {
		super();
		this.botids=gs.fromJson(FileUtil.readString(new File(file,"localvoice.json")), Map.class);
		vt=new VoiceTagger(file);
	}
	@Override
	public CompletableFuture<VoiceGenerationResult> getAudioData(String roleName, String uid, String text,
			String messageId,Consumer<UsageIntf<?>> usageListener) {
		System.out.println("local voice");
		return vt.extractTalkContent(roleName, text, usageListener).thenCompose(s->LocalVoiceModel.requireAudio(botids.get(roleName), messageId, s, usageListener));
	}
	@Override
	public boolean canProcessVoice(String roleName) {
		return botids.containsKey(roleName)&&LocalVoiceModel.hasOnlineService();
	}
	@Override
	public boolean isHinted(String modelName) {
		return "gptsovits".equals(modelName);
	}

}
