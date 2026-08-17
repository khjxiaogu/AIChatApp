package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;

class DeepseekRespScheme extends RespScheme {
	DeepseekV4Usage usage;
	public DeepseekRespScheme() {
	}

	@Override
	public DeepseekV4Usage getUsage() {
		return usage;
	}

}
