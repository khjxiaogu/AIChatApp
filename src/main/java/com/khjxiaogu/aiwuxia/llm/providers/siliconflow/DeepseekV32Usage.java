package com.khjxiaogu.aiwuxia.llm.providers.siliconflow;

public class DeepseekV32Usage extends SiliconUsage{
	@Override
	public String getUsageName() {
		return "Deepseek V3.2";
	}
	public double getEquivantTokens() {
		return completion_tokens * 3d + prompt_cache_hit_tokens * .2d + prompt_cache_miss_tokens * 2d;
	}
}
