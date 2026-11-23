package com.khjxiaogu.aiwuxia.vision;

import java.util.Locale;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.TextCounter;

/**
 * H3 / NVFP4 视频生成的算力时长计费。
 * <p>
 * 计费口径：<b>每 1 小时计算时间 = 1,000,000 配额</b>，即
 * {@code 配额 = 估算总耗时(秒) / 3600 * 1,000,000}。耗时按下面的公式估算：
 * </p>
 * <pre>
 * F      = 最小的 f &gt;= 24*sec 且 f % 17 == 5          // 17k+5 帧网格
 * T_lat  = ((F - 5) / 17) * 5 + 2                       // 视频 latent 帧数
 * n      = T_lat * (W/32) * (H/32)
 *          + 2 * round(40 * sec)                        // 双声道音频 token
 *          + T_text                                     // 文本 token，500~1500
 *
 * t_step = a * n + b * n^2
 * INT8   a = 4.94e-4, b = 3.86e-8
 * NVFP4  a = 6.10e-4, b = 4.80e-8
 *
 * L      = 总权重(GB) * 1000 / 磁盘读吞吐(MB/s)
 * C_enc  = 15~90 秒（随文本 token 线性）
 * C_dec  = 5~20 秒（随 宽*高*帧数 线性）
 * T_total = L + C_enc + steps * t_step + C_dec
 * </pre>
 * <p>
 * 本用量在视频生成成功后由 MCP 上报，{@link #getEquivantTokens()} 直接返回应扣的配额。
 * </p>
 */
public class H3VideoUsage implements UsageIntf<H3VideoUsage> {

	/** 每 1 小时计算时间对应的配额。 */
	public static final double QUOTA_PER_HOUR = 1_000_000d;
	/** 默认磁盘顺序读吞吐（MB/s）。 */
	public static final double DEFAULT_DISK_MBPS = 1000d;
	/** 文本 token 下限。 */
	public static final int MIN_TEXT_TOKENS = 500;
	/** 文本 token 上限。 */
	public static final int MAX_TEXT_TOKENS = 1500;
	/** 文本编码器前向耗时下限（秒）。 */
	public static final double C_ENC_MIN_SECONDS = 15d;
	/** 文本编码器前向耗时上限（秒）。 */
	public static final double C_ENC_MAX_SECONDS = 90d;
	/** VAE 解码耗时下限（秒）。 */
	public static final double C_DEC_MIN_SECONDS = 5d;
	/** VAE 解码耗时上限（秒）。 */
	public static final double C_DEC_MAX_SECONDS = 20d;
	/** 解码耗时达到上限的参考像素帧数（1344×768 / 15 秒）。 */
	public static final double DEC_REFERENCE_PIXEL_FRAMES = 1344d * 768d * 362d;

	/** 累计消耗的计算时间（秒）；usage 只保存这个总量，方便直接相加。 */
	public double estimatedSeconds;

	/** 供 Gson 反序列化使用。 */
	public H3VideoUsage() {
	}

	/**
	 * 按一次视频的参数计算用量。
	 *
	 * @param width      画面宽
	 * @param height     画面高
	 * @param seconds    目标时长（秒）
	 * @param steps      采样步数
	 * @param sparse     保留兼容；a/b 已按目标运行配置拟合，不再二次缩放
	 * @param model      主模型档位
	 * @param textTokens 文本 token 估算值
	 */
	public H3VideoUsage(int width, int height, double seconds, int steps, boolean sparse,
			H3VideoApi.ModelTier model, int textTokens) {
		this(width, height, seconds, steps, sparse, model, textTokens, diskMbps());
	}

	/**
	 * @param diskMbps 磁盘顺序读吞吐（MB/s），非正数时使用默认值
	 */
	public H3VideoUsage(int width, int height, double seconds, int steps, boolean sparse,
			H3VideoApi.ModelTier model, int textTokens, double diskMbps) {
		this.estimatedSeconds = estimateSeconds(width, height, seconds, steps, sparse,
				model == null ? H3VideoApi.ModelTier.INT8 : model, textTokens, diskMbps);
	}

	/**
	 * 估算整条任务耗时（秒）。
	 *
	 * @param width      画面宽（32 的倍数）
	 * @param height     画面高（32 的倍数）
	 * @param seconds    目标时长（秒）
	 * @param steps      采样步数
	 * @param sparse     保留兼容；a/b 已按目标运行配置拟合，不再二次缩放
	 * @param model      主模型档位
	 * @param textTokens 文本 token，500~1500
	 * @param diskMbps   磁盘顺序读吞吐（MB/s），非正数时使用默认值
	 * @return 估算总耗时（秒）
	 */
	public static double estimateSeconds(int width, int height, double seconds, int steps,
			boolean sparse, H3VideoApi.ModelTier model, int textTokens, double diskMbps) {
		H3VideoApi.ModelTier tier = model == null ? H3VideoApi.ModelTier.INT8 : model;
		int frames = H3VideoApi.snapFrames(seconds);
		int latentFrames = ((frames - H3VideoApi.FRAME_OFFSET) / H3VideoApi.FRAME_STEP) * 5 + 2;
		double audioTokens = 2d * Math.round(40d * seconds);
		double text = clampTextTokens(textTokens);
		double tokens = latentFrames * (width / 32d) * (height / 32d) + audioTokens + text;
		// a/b 是按目标运行配置（默认稀疏开启）拟合的有效系数，直接按 t_step = a*n + b*n^2 计算；
		// sparse 参数保留在接口上仅用于兼容旧调用，不再二次缩放。
		double stepSeconds = tier.stepLinear() * tokens + tier.stepQuadratic() * tokens * tokens;
		double loadSeconds = tier.loadGb() * 1000d / (diskMbps > 0 ? diskMbps : DEFAULT_DISK_MBPS);
		double encodeSeconds = C_ENC_MIN_SECONDS + (C_ENC_MAX_SECONDS - C_ENC_MIN_SECONDS)
				* ((text - MIN_TEXT_TOKENS) / (double) (MAX_TEXT_TOKENS - MIN_TEXT_TOKENS));
		double pixelFrames = (double) width * height * frames;
		double decodeSeconds = C_DEC_MIN_SECONDS + (C_DEC_MAX_SECONDS - C_DEC_MIN_SECONDS)
				* Math.min(1d, pixelFrames / DEC_REFERENCE_PIXEL_FRAMES);
		return loadSeconds + encodeSeconds + Math.max(1, steps) * stepSeconds + decodeSeconds;
	}

	/**
	 * 按提示词长度估算文本 token：每个字符约 1 token，夹在 500~1500。
	 *
	 * @param prompt 提示词，可为 null
	 * @return 文本 token
	 */
	public static int textTokens(String prompt) {
		return prompt == null ? MIN_TEXT_TOKENS : clampTextTokens(TextCounter.countWords(prompt));
	}

	/** 读取磁盘吞吐：{@code -Dh3DiskMBps}，默认 1000 MB/s。 */
	public static double diskMbps() {
		String raw = System.getProperty("h3DiskMBps", "").trim();
		if (raw.isEmpty()) {
			return DEFAULT_DISK_MBPS;
		}
		try {
			double value = Double.parseDouble(raw);
			return value > 0 ? value : DEFAULT_DISK_MBPS;
		} catch (NumberFormatException ex) {
			return DEFAULT_DISK_MBPS;
		}
	}

	private static int clampTextTokens(int value) {
		return Math.max(MIN_TEXT_TOKENS, Math.min(MAX_TEXT_TOKENS, value));
	}

	@Override
	public void add(H3VideoUsage another) {
		if (another == null) {
			return;
		}
		estimatedSeconds += another.estimatedSeconds;
	}

	@Override
	public void set(H3VideoUsage another) {
		if (another == null) {
			return;
		}
		estimatedSeconds = another.estimatedSeconds;
	}

	/** 应扣配额：累计计算秒数 / 3600 * 1,000,000。 */
	@Override
	public double getEquivantTokens() {
		return estimatedSeconds / 3600d * QUOTA_PER_HOUR;
	}

	/** 与 {@link #getEquivantTokens()} 等价的别名。 */
	public double getQuota() {
		return getEquivantTokens();
	}

	/** 累计消耗的计算时间（秒）。 */
	public double getEstimatedSeconds() {
		return estimatedSeconds;
	}

	@Override
	public String getUsageName() {
		return "H3 视频生成（算力时长）";
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "H3 视频累计计算 %.1f 秒，配额 %.0f", estimatedSeconds, getEquivantTokens());
	}
}
