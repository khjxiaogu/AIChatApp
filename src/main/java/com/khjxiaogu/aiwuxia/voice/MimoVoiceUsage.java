package com.khjxiaogu.aiwuxia.voice;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * 小米 MiMo 语音合成（voiceclone）用量。与本地语音合成一样限时免额度，仅统计字数。
 */
public class MimoVoiceUsage implements UsageIntf<MimoVoiceUsage> {
	int len;

	public MimoVoiceUsage(int len) {
		super();
		this.len = len;
	}
	@Override
	public void add(MimoVoiceUsage another) {
		len+=another.len;
	}
	@Override
	public void set(MimoVoiceUsage another) {
		len=another.len;
	}
	@Override
	public double getEquivantTokens() {
		return 0; //1 000 000
	}
	@Override
	public String getUsageName() {
		return "Mimo 语音合成";
	}
    @Override
    public String toString() {
        return "语音字数 " + len;
    }
}
