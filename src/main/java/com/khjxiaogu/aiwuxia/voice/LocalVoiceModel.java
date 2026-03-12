package com.khjxiaogu.aiwuxia.voice;

import java.util.concurrent.CompletableFuture;
/**
 * 本地部署语音模型交互器
 * */
public class LocalVoiceModel {

	public static final LocalModelHandshaker lhs=new LocalModelHandshaker();
	public static boolean hasOnlineService() {
		return lhs.hasOnlineService();
	}
	public static CompletableFuture<byte[]> requireAudio(String chara,String emote,String reqid,String text) {
		return lhs.requireAudio(chara, emote, reqid, text);
	}
}
