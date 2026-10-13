package com.khjxiaogu.aiwuxia.llm.providers.mimo;

public class MimoProUsage extends MimoUsage{
	@Override
	public String getUsageName() {
		return "Mimo 专业";
	}
	public double getEquivantTokens() {
		return completion_tokens * 3d + prompt_cache_hit_tokens * .0125d + prompt_cache_miss_tokens * 1.5d;
	}
}
