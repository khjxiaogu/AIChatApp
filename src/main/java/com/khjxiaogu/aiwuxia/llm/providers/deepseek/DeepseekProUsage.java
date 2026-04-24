package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

public class DeepseekProUsage extends DeepseekUsage{
	public double getEquivantTokens() {
		return completion_tokens * 12d + prompt_cache_hit_tokens * .5d + prompt_cache_miss_tokens * 6d;
	}
}
