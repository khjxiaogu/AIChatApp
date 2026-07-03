package com.khjxiaogu.aiwuxia.voice;

public class ModelGenerationResult {
	public final byte[] bodyData;
	public final String format;
	public ModelGenerationResult(byte[] audioData, String format) {
		super();
		this.bodyData = audioData;
		this.format = format;
	}

}
