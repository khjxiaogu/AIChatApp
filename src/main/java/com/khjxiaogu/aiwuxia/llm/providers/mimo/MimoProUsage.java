package com.khjxiaogu.aiwuxia.llm.providers.mimo;

public class MimoProUsage extends MimoUsage{
	@Override
	public String getUsageName() {
		return "Mimo 专业";
	}
	public double getEquivantTokens() {
		return completion_tokens * 3d + cacheHitTokens() * .0125d + cacheMissTokens() * 1.5d;
	}
}
