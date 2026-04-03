package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.UsageIntf;

class DeepseekRespScheme extends RespScheme {
	DeepseekUsage usage;
	public DeepseekRespScheme() {
	}

	@Override
	public UsageIntf getUsage() {
		return usage;
	}

}
