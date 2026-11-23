package com.khjxiaogu.aiwuxia.vision;

import java.util.Locale;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * HunyuanVideo-Foley 视频配音用量。
 * <p>
 * 计算时间按 {@code T = 12 + 1.6 * 视频时长(秒)} 估算；配额仍按
 * <b>每 1 小时 = 1,000,000</b> 折算：
 * </p>
 * <pre>
 * 配额 = (12 + 1.6 * duration) / 3600 * 1,000,000
 * </pre>
 * <p>
 * 用量只保存累计计算时间，便于直接相加。
 * </p>
 */
public class FoleyVideoUsage implements UsageIntf<FoleyVideoUsage> {

	/** 每 1 小时计算时间对应的配额。 */
	public static final double QUOTA_PER_HOUR = 1_000_000d;
	/** 固定开销（秒）。 */
	public static final double BASE_SECONDS = 12d;
	/** 每秒视频对应的计算时间系数。 */
	public static final double SECONDS_PER_VIDEO_SECOND = 1.6d;

	/** 累计计算时间（秒）。 */
	public double estimatedSeconds;

	/** 供 Gson 反序列化使用。 */
	public FoleyVideoUsage() {
	}

	/**
	 * @param videoSeconds 视频时长（秒）
	 */
	public FoleyVideoUsage(double videoSeconds) {
		this.estimatedSeconds = estimateSeconds(videoSeconds);
	}

	/**
	 * 计算时间估算：{@code 12 + 1.6 * duration}。
	 *
	 * @param videoSeconds 视频时长（秒），负数按 0 处理
	 * @return 计算时间（秒）
	 */
	public static double estimateSeconds(double videoSeconds) {
		return BASE_SECONDS + SECONDS_PER_VIDEO_SECOND * Math.max(0d, videoSeconds);
	}

	@Override
	public void add(FoleyVideoUsage another) {
		if (another != null) {
			estimatedSeconds += another.estimatedSeconds;
		}
	}

	@Override
	public void set(FoleyVideoUsage another) {
		if (another != null) {
			estimatedSeconds = another.estimatedSeconds;
		}
	}

	@Override
	public double getEquivantTokens() {
		return estimatedSeconds / 3600d * QUOTA_PER_HOUR;
	}

	/** 累计计算时间（秒）。 */
	public double getEstimatedSeconds() {
		return estimatedSeconds;
	}

	/** 应扣配额。 */
	public double getQuota() {
		return getEquivantTokens();
	}

	@Override
	public String getUsageName() {
		return "视频配音（Foley）";
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "视频配音，累计计算 %.1f 秒，配额 %.0f", estimatedSeconds,
				getEquivantTokens());
	}
}
