package com.khjxiaogu.aiwuxia.vision;

import java.util.Locale;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * Seedance 视频生成用量。
 * <p>
 * 按本站计费口径估算视频 token：
 * </p>
 * <pre>
 * 视频 token = 输出宽 × 输出高 × 输出帧率 × 输出时长 / 1024
 * </pre>
 * <p>
 * 官方价格为 <b>70 元/百万视频 token</b>。本站配额统一按
 * <b>2 元/百万等效 token</b> 折算（见 {@code UsageTracker#calculatePrice()}），
 * 因此计入配额的等效 token 需要乘上 {@code 70 / 2 = 35}：
 * </p>
 * <pre>
 * 等效配额 token = 视频 token × 35
 * 实际扣费金额   = 视频 token × 70 / 1,000,000 元
 * </pre>
 * <p>
 * 输出宽高和帧率由 {@link SeedanceVideoGenerator} 按分辨率/画幅推导，
 * 也可以通过配置 {@code videoWidth} / {@code videoHeight} / {@code videoFps} 覆盖。
 * </p>
 */
public class SeedanceVideoUsage implements UsageIntf<SeedanceVideoUsage> {

	/** 官方价格：70 元/百万视频 token。 */
	public static final double PRICE_PER_MILLION_TOKENS = 70d;
	/** 本站配额折算价格：2 元/百万等效 token。 */
	public static final double QUOTA_PRICE_PER_MILLION_TOKENS = 2d;
	/** 视频 token 折算成配额等效 token 的倍率（70 / 2 = 35）。 */
	public static final double EQUIVALENT_TOKEN_MULTIPLIER = PRICE_PER_MILLION_TOKENS
			/ QUOTA_PRICE_PER_MILLION_TOKENS;

	/** 原始视频 token 数（宽 × 高 × 帧率 × 时长 / 1024）。 */
	public double videoTokens;
	/** 累计生成的视频条数。 */
	public long count;

	/** 供 Gson 反序列化使用。 */
	public SeedanceVideoUsage() {
	}

	/**
	 * 按一次视频的输出参数计算用量。
	 *
	 * @param width    输出宽
	 * @param height   输出高
	 * @param fps      输出帧率
	 * @param duration 输出时长（秒）
	 */
	public SeedanceVideoUsage(int width, int height, int fps, int duration) {
		this.videoTokens = computeVideoTokens(width, height, fps, duration);
		this.count = 1;
	}

	/**
	 * 直接用原始视频 token 数创建用量。
	 *
	 * @param videoTokens 原始视频 token
	 */
	public SeedanceVideoUsage(double videoTokens) {
		this.videoTokens = videoTokens;
		this.count = 1;
	}

	/**
	 * 按计费公式计算一次视频的原始 token 数。
	 *
	 * @param width    输出宽
	 * @param height   输出高
	 * @param fps      输出帧率
	 * @param duration 输出时长（秒）
	 * @return 原始视频 token
	 */
	public static double computeVideoTokens(int width, int height, int fps, int duration) {
		return (double) width * height * fps * duration / 1024d;
	}

	@Override
	public void add(SeedanceVideoUsage another) {
		videoTokens += another.videoTokens;
		count += another.count;
	}

	@Override
	public void set(SeedanceVideoUsage another) {
		videoTokens = another.videoTokens;
		count = another.count;
	}

	@Override
	public double getEquivantTokens() {
		return videoTokens * EQUIVALENT_TOKEN_MULTIPLIER;
	}

	/** 原始视频 token 数（未乘 70/2 倍率）。 */
	public double getVideoTokens() {
		return videoTokens;
	}

	/** 按 70 元/百万 token 计算的金额（元）。 */
	public double getPriceYuan() {
		return videoTokens * PRICE_PER_MILLION_TOKENS / 1_000_000d;
	}

	@Override
	public String getUsageName() {
		return "Seedance 视频生成";
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "视频 %d 条，视频token %.0f，约 %.4f 元（70元/百万token）",
				count, videoTokens, getPriceYuan());
	}
}
