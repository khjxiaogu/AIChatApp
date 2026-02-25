package com.khjxiaogu.aiwuxia.scheme;

import java.io.Serializable;
import java.text.DecimalFormat;

public class Usage implements Serializable{
	/**
	 * 
	 */
	private static final long serialVersionUID = -8892754113401403920L;
	public int completion_tokens;
	public int prompt_tokens;
	public int prompt_cache_hit_tokens;
	public int prompt_cache_miss_tokens;
	@Override
	public String toString() {
		return "返回token " + completion_tokens + "\n发送token " + prompt_tokens
				+ "\n缓存token " + prompt_cache_hit_tokens + "\n新增token "
				+ prompt_cache_miss_tokens ;
	}
	public void add(Usage another) {
		completion_tokens+=another.completion_tokens;
		prompt_tokens+=another.prompt_tokens;
		prompt_cache_hit_tokens+=another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens+=another.prompt_cache_miss_tokens;
	}
	public void add(com.khjxiaogu.aiwuxia.RespScheme.Usage another) {
		completion_tokens+=another.completion_tokens;
		prompt_tokens+=another.prompt_tokens;
		prompt_cache_hit_tokens+=another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens+=another.prompt_cache_miss_tokens;
	}
	public String calculatePrice() {
		DecimalFormat format=new DecimalFormat("#0.00#");
		return format.format(((prompt_cache_miss_tokens+completion_tokens)*2+0.2*prompt_cache_hit_tokens)/1000000);
	}

}