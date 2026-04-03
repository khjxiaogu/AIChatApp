package com.khjxiaogu.aiwuxia.llm.providers.volcano;

import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.UsageIntf;

class VolcanoRespScheme extends RespScheme {
	VolcanoUsage usage;
	public VolcanoRespScheme() {
	}

	@Override
	public UsageIntf getUsage() {
		return usage;
	}

}
