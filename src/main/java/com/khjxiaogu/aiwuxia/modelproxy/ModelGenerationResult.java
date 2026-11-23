package com.khjxiaogu.aiwuxia.modelproxy;

public class ModelGenerationResult {
	public final byte[] bodyData;
	public final String format;
	public ModelGenerationResult(byte[] audioData, String format) {
		super();
		this.bodyData = audioData;
		this.format = format;
	}

}
