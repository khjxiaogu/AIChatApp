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
package com.khjxiaogu.aiwuxia.llm.providers.grok;

import com.khjxiaogu.aiwuxia.llm.scheme.CompletionTokenDetail;
import com.khjxiaogu.aiwuxia.llm.scheme.TokenDetail;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class GrokUsage implements UsageIntf<GrokUsage> {
	public long total_tokens;
	
	public long prompt_tokens;
	public TokenDetail prompt_tokens_details;
	public long completion_tokens;
	public CompletionTokenDetail completion_tokens_details;
	public GrokUsage() {
		prompt_tokens_details=new TokenDetail();
		completion_tokens_details=new CompletionTokenDetail();
	}
	
	// 建议添加构造方法完成初始化
    @Override
    public String toString() {
        return "总token数 " + total_tokens +
                "\n发送token " + prompt_tokens +
                "\n发送token详情:\n" + prompt_tokens_details +
                "\n返回token " + completion_tokens +
                "\n返回token详情:\n" + completion_tokens_details;
    }

    public synchronized void add(GrokUsage another) {
        this.total_tokens += another.total_tokens;
        this.prompt_tokens += another.prompt_tokens;
        this.completion_tokens += another.completion_tokens;
        if (another.prompt_tokens_details != null) {
            this.prompt_tokens_details.add(another.prompt_tokens_details);
        }
        if (another.completion_tokens_details != null) {
            this.completion_tokens_details.add(another.completion_tokens_details);
        }
    }

    public synchronized void set(GrokUsage another) {
        this.total_tokens = another.total_tokens;
        this.prompt_tokens = another.prompt_tokens;
        this.completion_tokens = another.completion_tokens;
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
	@Override
	public double getEquivantTokens() {
		long cached=prompt_tokens_details.cached_tokens;
		long uncached=prompt_tokens-cached;
		
		return ((completion_tokens+completion_tokens_details.reasoning_tokens)*.5d+cached*.05d+uncached*.2d)*(7d/2d);
	}


}