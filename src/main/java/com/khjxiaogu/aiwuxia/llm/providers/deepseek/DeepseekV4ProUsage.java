package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

public class DeepseekV4ProUsage extends DeepseekV4Usage{
	public double getEquivantTokens() {
        return completion_tokens * 6.75
            + prompt_cache_hit_tokens * 0.075
            + prompt_cache_miss_tokens * 2.25;
	}
}
