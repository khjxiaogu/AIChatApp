package com.khjxiaogu.aiwuxia.voice;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class VolcanoVoiceUsage implements UsageIntf<VolcanoVoiceUsage> {
	int len;

	public VolcanoVoiceUsage(int len) {
		super();
		this.len = len;
	}
	@Override
	public void add(VolcanoVoiceUsage another) {
		len+=another.len;
	}
	@Override
	public void set(VolcanoVoiceUsage another) {
		len=another.len;
	}
	@Override
	public double getEquivantTokens() {
		return len*150; //*1000000*3/2/10000
	}
    @Override
    public String toString() {
        return "语音字数 " + len;
    }

}
