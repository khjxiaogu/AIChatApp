package com.khjxiaogu.aiwuxia.voice;

public class ModelGenerationResult {
	public final byte[] audioData;
	public final String format;
	public ModelGenerationResult(byte[] audioData, String format) {
		super();
		this.audioData = audioData;
		this.format = format;
	}

}
