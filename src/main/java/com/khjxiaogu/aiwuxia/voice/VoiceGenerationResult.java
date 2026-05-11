package com.khjxiaogu.aiwuxia.voice;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class VoiceGenerationResult {
	public final UsageIntf<?> usage;
	public final byte[] audioData;
	public final String format;
	public VoiceGenerationResult(UsageIntf<?> usage, byte[] audioData, String format) {
		super();
		this.usage = usage;
		this.audioData = audioData;
		this.format = format;
	}


}
