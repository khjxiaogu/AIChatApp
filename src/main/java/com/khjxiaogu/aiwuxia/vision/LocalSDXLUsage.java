package com.khjxiaogu.aiwuxia.vision;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * 本地 SDXL 绘图用量。
 * <p>
 * 价格：
 * </p>
 * <ul>
 *   <li>每生成一张图片：{@value #TOKENS_PER_IMAGE} token；</li>
 *   <li>每次 WD1.4 图片反推（interrogate）：{@value #TOKENS_PER_INTERROGATE} token。</li>
 * </ul>
 */
public class LocalSDXLUsage implements UsageIntf<LocalSDXLUsage> {

	/** 每张生成图片的价格（token）。 */
	public static final int TOKENS_PER_IMAGE = 5000;
	/** 每次图片反推的价格（token）。 */
	public static final int TOKENS_PER_INTERROGATE = 500;

	int count;
	int interrogate;

	public LocalSDXLUsage(int count) {
		super();
		this.count = count;
	}

	public LocalSDXLUsage(int count, int interrogate) {
		super();
		this.count = count;
		this.interrogate = interrogate;
	}

	@Override
	public void add(LocalSDXLUsage another) {
		count += another.count;
		interrogate += another.interrogate;
	}

	@Override
	public void set(LocalSDXLUsage another) {
		count = another.count;
		interrogate = another.interrogate;
	}

	@Override
	public double getEquivantTokens() {
		// 图片 5000 token/张，图片反推 500 token/次
		return count * (double) TOKENS_PER_IMAGE + interrogate * (double) TOKENS_PER_INTERROGATE;
	}

	@Override
	public String getUsageName() {
		return "本地绘图";
	}

	@Override
	public String toString() {
		return "图片生成" + count + "，推理" + interrogate;
	}
}
