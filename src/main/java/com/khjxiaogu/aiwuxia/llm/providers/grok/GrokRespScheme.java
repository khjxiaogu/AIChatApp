package com.khjxiaogu.aiwuxia.llm.providers.grok;

import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;

class GrokRespScheme extends RespScheme {
	GrokUsage usage;
	public GrokRespScheme() {
	}

	@Override
	public GrokUsage getUsage() {
		return usage;
	}

}
