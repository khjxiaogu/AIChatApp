package com.khjxiaogu.aiwuxia.llm.providers.siliconflow;

import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;

public class SiliconRespScheme extends RespScheme {
	SiliconUsage usage;
	public SiliconRespScheme() {
	}

	@Override
	public SiliconUsage getUsage() {
		return usage;
	}

}
