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

import java.util.Locale;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * DeepSeek 网页搜索（Search API）用量。
 * <p>
 * Search 接口<b>按成功请求次数</b>计费，与 token 数、返回条数都无关
 * （见 <a href="https://api-docs.deepseek.com/zh-cn/guides/search">官方文档</a>）：
 * 每 1000 次成功请求 5 元，即每次 0.005 元；只有返回 HTTP 200 且 {@code results} 非空的请求才算成功。
 * 本类把“次”折算成系统统一的等效 token：本站价格口径为 <b>1 元 = 500000 token</b>
 * （{@code UsageTracker#calculatePrice()} 按 {@code token * 2 / 1000000} 折元），
 * 因此 0.005 元 = 2500 token。
 * </p>
 * <p>
 * 计费由 {@code DeepseekSearchMcp} 在拿到计费成功的响应后调用 {@code addUsage} 写入，
 * 请求失败、超时或搜索无结果时不会产生本用量，避免多扣。
 * </p>
 */
public class DeepseekSearchUsage implements UsageIntf<DeepseekSearchUsage> {

	/** 官方单价：每 1000 次成功请求 5 元。 */
	public static final double PRICE_PER_1000_REQUESTS = 5d;
	/** 官方单价折算到单次的金额（元）。 */
	public static final double PRICE_PER_REQUEST = PRICE_PER_1000_REQUESTS / 1000d;
	/** 价格口径：1 元对应的等效 token 数（1,000,000 token = 2 元）。 */
	public static final double TOKENS_PER_YUAN = 500000d;
	/** 一次成功搜索请求折算的等效 token 数（2500）。 */
	public static final double TOKENS_PER_REQUEST = PRICE_PER_REQUEST * TOKENS_PER_YUAN;

	/** 成功计费的搜索请求次数（HTTP 200 且 results 非空）。 */
	public long requests;

	public DeepseekSearchUsage() {
	}

	public DeepseekSearchUsage(long requests) {
		super();
		this.requests = requests;
	}

	@Override
	public void add(DeepseekSearchUsage another) {
		requests += another.requests;
	}

	@Override
	public void set(DeepseekSearchUsage another) {
		requests = another.requests;
	}

	@Override
	public double getEquivantTokens() {
		return requests * TOKENS_PER_REQUEST;
	}

	@Override
	public String getUsageName() {
		return "DeepSeek 网页搜索";
	}

	@Override
	public String toString() {
		return "搜索请求 " + requests + " 次（" + String.format(Locale.ROOT, "%.3f", requests * PRICE_PER_REQUEST) + " 元）";
	}

}
