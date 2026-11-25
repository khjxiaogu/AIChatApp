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
package com.khjxiaogu.aiwuxia.llm.providers.mimo;

import com.khjxiaogu.aiwuxia.llm.scheme.CompletionTokenDetail;
import com.khjxiaogu.aiwuxia.llm.scheme.TokenDetail;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class MimoUsage implements UsageIntf<MimoUsage> {

	public long completion_tokens;
	public long prompt_tokens;
	/**
	 * 输入 token 明细：新版接口把缓存命中、图片/视频/音频输入 token 放在这里
	 * （{@code cached_tokens} / {@code image_tokens} / {@code video_tokens} / {@code audio_tokens}）。
	 */
	public TokenDetail prompt_tokens_details;
	/** 旧版接口的缓存命中 token；新版接口改用 {@link #prompt_tokens_details} 的 {@code cached_tokens}。 */
	public long prompt_cache_hit_tokens;
	/** 旧版接口的缓存未命中 token；新版接口需要按 {@code prompt_tokens - cached_tokens} 推算。 */
	public long prompt_cache_miss_tokens;
	public long total_tokens; // 新增字段
	public CompletionTokenDetail completion_tokens_details; // 新增字段

	public MimoUsage() {
		this.completion_tokens_details = new CompletionTokenDetail();
		this.prompt_tokens_details = new TokenDetail();
	}

	@Override
	public String getUsageName() {
		return "Mimo 对话";
	}

	@Override
	public String toString() {
		return "返回token " + completion_tokens +
			"\n发送token " + prompt_tokens +
			"\n发送token详情:\n" + prompt_tokens_details +
			"\n缓存命中token " + cacheHitTokens() +
			"\n缓存未命中token " + cacheMissTokens() +
			"\n总token数 " + total_tokens +
			"\n生成token详情:\n" + completion_tokens_details;
	}

	/**
	 * 缓存命中的输入 token 数。
	 * <p>
	 * 新版接口放在 {@code prompt_tokens_details.cached_tokens}，旧版接口直接给
	 * {@code prompt_cache_hit_tokens}；两者都没有时按 0 处理。
	 * </p>
	 *
	 * @return 缓存命中 token 数
	 */
	public long cacheHitTokens() {
		if (prompt_cache_hit_tokens > 0) {
			return prompt_cache_hit_tokens;
		}
		return prompt_tokens_details == null ? 0 : prompt_tokens_details.cached_tokens;
	}

	/**
	 * 缓存未命中的输入 token 数。
	 * <p>
	 * 旧版接口直接给 {@code prompt_cache_miss_tokens}；新版接口没有该字段，用
	 * {@code prompt_tokens - cached_tokens} 推算，保证图片/视频/音频等输入 token
	 * 也能进入计费，而不是只算输出。
	 * </p>
	 *
	 * @return 缓存未命中 token 数（不小于 0）
	 */
	public long cacheMissTokens() {
		if (prompt_cache_miss_tokens > 0) {
			return prompt_cache_miss_tokens;
		}
		long miss = prompt_tokens - cacheHitTokens();
		return miss > 0 ? miss : 0;
	}

	public synchronized void add(MimoUsage another) {
		completion_tokens += another.completion_tokens;
		prompt_tokens += another.prompt_tokens;
		prompt_cache_hit_tokens += another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens += another.prompt_cache_miss_tokens;
		total_tokens += another.total_tokens;
		if (another.prompt_tokens_details != null) {
			this.prompt_tokens_details.add(another.prompt_tokens_details);
		}
		if (another.completion_tokens_details != null) {
			this.completion_tokens_details.add(another.completion_tokens_details);
		}
	}

	public synchronized void set(MimoUsage another) {
		completion_tokens = another.completion_tokens;
		prompt_tokens = another.prompt_tokens;
		prompt_cache_hit_tokens = another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens = another.prompt_cache_miss_tokens;
		total_tokens = another.total_tokens;
		if (another.prompt_tokens_details != null) {
			this.prompt_tokens_details.set(another.prompt_tokens_details);
		} else {
			this.prompt_tokens_details = new TokenDetail();
		}
		if (another.completion_tokens_details != null) {
			this.completion_tokens_details.set(another.completion_tokens_details);
		} else {
			this.completion_tokens_details = new CompletionTokenDetail();
		}
	}

	public double getEquivantTokens() {
		return completion_tokens * 1d + cacheHitTokens() * 0.01d + cacheMissTokens() * .5d;
	}

}
