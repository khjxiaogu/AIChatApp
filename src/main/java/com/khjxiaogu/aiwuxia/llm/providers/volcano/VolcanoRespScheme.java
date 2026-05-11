package com.khjxiaogu.aiwuxia.llm.providers.volcano;

import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;

class VolcanoRespScheme extends RespScheme {
	VolcanoUsage usage;
	public VolcanoRespScheme() {
	}

	@Override
	public VolcanoUsage getUsage() {
		return usage;
	}

}
