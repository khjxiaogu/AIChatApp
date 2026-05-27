package com.khjxiaogu.aiwuxia.voice;

public class VoiceGenerationResult {
	public final byte[] audioData;
	public final String format;
	public VoiceGenerationResult(byte[] audioData, String format) {
		super();
		this.audioData = audioData;
		this.format = format;
	}

}
