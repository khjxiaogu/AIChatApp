package com.khjxiaogu.aiwuxia.voice;

import com.khjxiaogu.aiwuxia.respscheme.UsageIntf;

public class LocalVoiceUsage implements UsageIntf<LocalVoiceUsage> {
	int len;

	public LocalVoiceUsage(int len) {
		super();
		this.len = len;
	}
	@Override
	public void add(LocalVoiceUsage another) {
		len+=another.len;
	}
	@Override
	public void set(LocalVoiceUsage another) {
		len=another.len;
	}
	@Override
	public double getEquivantTokens() {
		return 0; //1 000 000
	}

}
