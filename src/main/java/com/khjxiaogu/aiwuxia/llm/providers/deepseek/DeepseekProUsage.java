package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

public class DeepseekProUsage extends DeepseekUsage{
	@Override
	public String getUsageName() {
		return "Deepseek 专业";
	}
	public double getEquivantTokens() {
		return completion_tokens * 3d + prompt_cache_hit_tokens * .0125d + prompt_cache_miss_tokens * 1.5d;
	}
}
