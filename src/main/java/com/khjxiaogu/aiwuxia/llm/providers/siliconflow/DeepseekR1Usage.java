package com.khjxiaogu.aiwuxia.llm.providers.siliconflow;

public class DeepseekR1Usage extends SiliconUsage{
	public double getEquivantTokens() {
		return completion_tokens * 8d + prompt_cache_hit_tokens * .2d + prompt_cache_miss_tokens * 2d;
	}
}
