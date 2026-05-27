package com.khjxiaogu.aiwuxia.llm.providers.mimo;

import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class MimoRespScheme extends RespScheme {
	MimoUsage usage;
	@Override
	public UsageIntf<?> getUsage() {
		return usage;
	}

}
