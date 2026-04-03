package com.khjxiaogu.aiwuxia.llm.providers.grok;

import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.UsageIntf;

class GrokRespScheme extends RespScheme {
	GrokUsage usage;
	public GrokRespScheme() {
	}

	@Override
	public UsageIntf getUsage() {
		return usage;
	}

}
