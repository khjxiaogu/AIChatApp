/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.respscheme;

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
	public int voice_tokens;
	@Override
	public String toString() {
		return "返回token " + completion_tokens + "\n发送token " + prompt_tokens
				+ "\n缓存token " + prompt_cache_hit_tokens + "\n新增token "
				+ prompt_cache_miss_tokens +"\n预估价格："+calculatePrice();
	}
	public synchronized void add(Usage another) {
		completion_tokens+=another.completion_tokens;
		prompt_tokens+=another.prompt_tokens;
		prompt_cache_hit_tokens+=another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens+=another.prompt_cache_miss_tokens;
		voice_tokens+=another.voice_tokens;
	}
	public synchronized void set(Usage another) {
		completion_tokens=another.completion_tokens;
		prompt_tokens=another.prompt_tokens;
		prompt_cache_hit_tokens=another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens=another.prompt_cache_miss_tokens;
		voice_tokens=another.voice_tokens;
	}
	public synchronized void appendVoiceTokens(int num) {
		voice_tokens+=num;
	}
	public String calculatePrice() {
		DecimalFormat format=new DecimalFormat("#0.00###");
		return format.format(((prompt_cache_miss_tokens+completion_tokens)*2+0.2*prompt_cache_hit_tokens)/1000000d+(voice_tokens/10000*3));
	}

}