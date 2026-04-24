package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import com.khjxiaogu.aiwuxia.respscheme.RespScheme;

class DeepseekRespScheme extends RespScheme {
	DeepseekUsage usage;
	public DeepseekRespScheme() {
	}

	@Override
	public DeepseekUsage getUsage() {
		return usage;
	}

}
