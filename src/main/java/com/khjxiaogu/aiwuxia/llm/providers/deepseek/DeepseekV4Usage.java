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
	/**
	 * 服务端联网工具的调用次数（官方字段 {@code usage.server_tool_use}）。
	 * <p>
	 * 只用于日志展示：官方已取消服务端搜索/抓取的按次收费，这些次数<b>不参与计价</b>，
	 * {@link #getEquivantTokens()} 不计入。字段在没有任何联网调用时由官方省略，故可能为 null。
	 * </p>
	 */
	public ServerToolUse server_tool_use;

	/** 服务端联网工具的调用次数（免费，仅展示）。 */
	public static class ServerToolUse {
		/** 联网搜索次数（不含图片搜索）。 */
		public long tool_search;
		/** 网页抓取次数。 */
		public long tool_fetch;
		/** 图片搜索次数。 */
		public long tool_image_search;

		public ServerToolUse() {
		}

		public ServerToolUse(long tool_search, long tool_fetch, long tool_image_search) {
			this.tool_search = tool_search;
			this.tool_fetch = tool_fetch;
			this.tool_image_search = tool_image_search;
		}

		public void add(ServerToolUse another) {
			if (another == null) {
				return;
			}
			tool_search += another.tool_search;
			tool_fetch += another.tool_fetch;
			tool_image_search += another.tool_image_search;
		}

		public void set(ServerToolUse another) {
			if (another == null) {
				tool_search = 0;
				tool_fetch = 0;
				tool_image_search = 0;
				return;
			}
			tool_search = another.tool_search;
			tool_fetch = another.tool_fetch;
			tool_image_search = another.tool_image_search;
		}

		@Override
		public String toString() {
			return "服务端联网：搜索 " + tool_search + " 次、抓取 " + tool_fetch + " 次、图片搜索 " + tool_image_search
					+ " 次（不计费）";
		}
	}

	public DeepseekV4Usage() {
		this.completion_tokens_details = new CompletionTokenDetail();
	}

	@Override
	public String getUsageName() {
		return "Deepseek V4 对话";
	}

	@Override
	public String toString() {
		return "返回token " + completion_tokens +
			"\n发送token " + prompt_tokens +
			"\n缓存token " + prompt_cache_hit_tokens +
			"\n新增token " + prompt_cache_miss_tokens +
			"\n总token数 " + total_tokens +
			"\n生成token详情:\n" + completion_tokens_details +
			(server_tool_use == null ? "" : "\n" + server_tool_use);
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
		if (another.server_tool_use != null) {
			if (this.server_tool_use == null) {
				this.server_tool_use = new ServerToolUse();
			}
			this.server_tool_use.add(another.server_tool_use);
		}
	}
    public void zoomEquivantly() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(shanghai);
        int hour = now.getHour();
        boolean isPeak = ((hour >= 9 && hour < 12) || (hour >= 14 && hour < 18))&&now.getDayOfWeek().getValue()<6;
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
		// 服务端联网次数只做记录，不参与计价
		if (another.server_tool_use != null) {
			if (this.server_tool_use == null) {
				this.server_tool_use = new ServerToolUse();
			}
			this.server_tool_use.set(another.server_tool_use);
		} else {
			this.server_tool_use = null;
		}
	}


    public double getEquivantTokens() {
        return prompt_cache_hit_tokens * 0.01
             + prompt_cache_miss_tokens * 0.5
             + completion_tokens * 2;
    }
}