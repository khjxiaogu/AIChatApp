package com.khjxiaogu.aiwuxia.llm.providers.siliconflow;

public class DeepseekV3Usage extends SiliconUsage{
	@Override
	public String getUsageName() {
		return "Deepseek V3";
	}
	public double getEquivantTokens() {
		return completion_tokens * 4d + prompt_cache_hit_tokens * .1d + prompt_cache_miss_tokens * 1d;
	}
}
