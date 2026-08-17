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
package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.khjxiaogu.aiwuxia.llm.scheme.CompletionTokenDetail;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class DeepseekV4Usage implements UsageIntf<DeepseekV4Usage> {

	public long completion_tokens;
	public long prompt_tokens;
	public long prompt_cache_hit_tokens;
	public long prompt_cache_miss_tokens;
	public long total_tokens; 
	public CompletionTokenDetail completion_tokens_details;
	public DeepseekV4Usage() {
		this.completion_tokens_details = new CompletionTokenDetail();
	}

	@Override
	public String toString() {
		return "返回token " + completion_tokens +
			"\n发送token " + prompt_tokens +
			"\n缓存token " + prompt_cache_hit_tokens +
			"\n新增token " + prompt_cache_miss_tokens +
			"\n总token数 " + total_tokens +
			"\n生成token详情:\n" + completion_tokens_details;
	}

	public synchronized void add(DeepseekV4Usage another) {
		completion_tokens += another.completion_tokens;
		prompt_tokens += another.prompt_tokens;
		prompt_cache_hit_tokens += another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens += another.prompt_cache_miss_tokens;
		total_tokens += another.total_tokens;
		if (another.completion_tokens_details != null) {
			this.completion_tokens_details.add(another.completion_tokens_details);
		}
	}
    public void zoomEquivantly() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(shanghai);
        int hour = now.getHour();
        boolean isPeak = (hour >= 9 && hour < 12) || (hour >= 14 && hour < 18);
        float factor = isPeak ? 2.0f : 1.0f;

        if (factor != 1.0f) {
            this.completion_tokens = Math.round(this.completion_tokens * factor);
            this.prompt_cache_hit_tokens = Math.round(this.prompt_cache_hit_tokens * factor);
            this.prompt_cache_miss_tokens = Math.round(this.prompt_cache_miss_tokens * factor);
            this.prompt_tokens = Math.round(this.prompt_tokens * factor);
            this.total_tokens = Math.round(this.total_tokens * factor);
            if (this.completion_tokens_details != null) {
                this.completion_tokens_details.multiply(factor);
            }
        }
    }

	public synchronized void set(DeepseekV4Usage another) {
		completion_tokens = another.completion_tokens;
		prompt_tokens = another.prompt_tokens;
		prompt_cache_hit_tokens = another.prompt_cache_hit_tokens;
		prompt_cache_miss_tokens = another.prompt_cache_miss_tokens;
		total_tokens = another.total_tokens;
		if (another.completion_tokens_details != null) {
			this.completion_tokens_details.set(another.completion_tokens_details);
		} else {
			this.completion_tokens_details = new CompletionTokenDetail();
		}
	}


    public double getEquivantTokens() {
        return prompt_cache_hit_tokens * 0.025
             + prompt_cache_miss_tokens * 0.75
             + completion_tokens * 2.25;
    }
}