package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import java.util.Calendar;

public class DeepseekProUsage extends DeepseekUsage{
	static Calendar discountOff;
	static{
		discountOff=Calendar.getInstance();
		discountOff.set(2026, 6, 1,0,0,0);

	}
	public double getEquivantTokens() {
		Calendar now=Calendar.getInstance();
		if(now.before(discountOff)) {
			return completion_tokens * 3d + prompt_cache_hit_tokens * .0125d + prompt_cache_miss_tokens * 1.5d;
			
		}
		return completion_tokens * 12d + prompt_cache_hit_tokens * .5d + prompt_cache_miss_tokens * 6d;
	}
}
