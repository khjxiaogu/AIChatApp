package com.khjxiaogu.aiwuxia.voice;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * SVS（歌声转换）用量。
 * <p>
 * 价格：每输出 0.6 MiB 计 5000 配额 token；不足 0.6 MiB 的部分按比例折算。
 * 如果请求关闭伴奏分离（{@code do_split=false}），只按输出体积的 1/3 计费。
 * </p>
 */
public class LocalSVSUsage implements UsageIntf<LocalSVSUsage> {

	/** 每 0.6 MiB 输出的价格（token）。 */
	public static final double TOKENS_PER_UNIT = 5000d;
	/** 计费单位：0.6 MiB（二进制）。 */
	public static final double UNIT_BYTES = 0.6d * 1024d * 1024d;
	/** 关闭伴奏分离时的计费系数。 */
	public static final double NO_SPLIT_FACTOR = 1d / 3d;

	private long bytes;
	/** 按伴奏分离系数折算后的计费字节数，用于正确的 add/set。 */
	private double billedBytes;

	public LocalSVSUsage(long bytes) {
		this(bytes, true);
	}

	/**
	 * @param bytes   输出音频字节数
	 * @param doSplit 是否启用伴奏分离；false 时按 1/3 计费
	 */
	public LocalSVSUsage(long bytes, boolean doSplit) {
		this.bytes = Math.max(0L, bytes);
		this.billedBytes = this.bytes * (doSplit ? 1d : NO_SPLIT_FACTOR);
	}

	@Override
	public void add(LocalSVSUsage another) {
		if (another != null) {
			bytes += Math.max(0L, another.bytes);
			billedBytes += Math.max(0d, another.billedBytes);
		}
	}

	@Override
	public void set(LocalSVSUsage another) {
		if (another == null) {
			bytes = 0L;
			billedBytes = 0d;
		} else {
			bytes = Math.max(0L, another.bytes);
			billedBytes = Math.max(0d, another.billedBytes);
		}
	}

	@Override
	public double getEquivantTokens() {
		// 价格：5000 token / 0.6 MiB 输出；未开启伴奏分离时按 1/3 折算
		return billedBytes / UNIT_BYTES * TOKENS_PER_UNIT;
	}

	@Override
	public String getUsageName() {
		return "本地歌声合成";
	}

	/** 输出字节数。 */
	public long getBytes() {
		return bytes;
	}

	@Override
	public String toString() {
		return String.format("SVS 输出 %.2f MiB", bytes / (1024d * 1024d));
	}
}
