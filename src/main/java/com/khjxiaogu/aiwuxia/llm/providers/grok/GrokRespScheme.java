package com.khjxiaogu.aiwuxia.llm.providers.grok;

import com.khjxiaogu.aiwuxia.respscheme.RespScheme;

class GrokRespScheme extends RespScheme {
	GrokUsage usage;
	public GrokRespScheme() {
	}

	@Override
	public GrokUsage getUsage() {
		return usage;
	}

}
