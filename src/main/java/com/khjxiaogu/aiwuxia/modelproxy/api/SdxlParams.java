package com.khjxiaogu.aiwuxia.modelproxy.api;

/**
 * SDXL 调用的参数。
 * <p>
 * 独立的值对象，不继承任何基类：一个 API 一份参数类，编解码器按模型类型选择。
 * 参考图只保存附件 id（{@link AttachmentRef#id}），由接收端按需拉取。
 * </p>
 */
public final class SdxlParams {

	/** 动作。 */
	public final SdxlAction action;
	/** 正面提示词。 */
	public final String prompt;
	/** 负面提示词。 */
	public final String negativePrompt;
	/** 采样步数。 */
	public final int steps;
	/** 宽度。 */
	public final int width;
	/** 高度。 */
	public final int height;
	/** 参考图附件 id；没有参考图时为 null。 */
	public final String image;
	/** 重绘幅度（只有图生图/分区图生图生效）。 */
	public final double denoising;
	/** 分区排布：true 上下，false 左右。 */
	public final boolean isRow;
	/** 分区比例，形如 {@code 1,1}。 */
	public final String ratio;

	public SdxlParams(SdxlAction action, String prompt, String negativePrompt, int steps, int width, int height,
			String image, double denoising, boolean isRow, String ratio) {
		this.action = action;
		this.prompt = prompt == null ? "" : prompt;
		this.negativePrompt = negativePrompt == null ? "" : negativePrompt;
		this.steps = steps;
		this.width = width;
		this.height = height;
		this.image = image == null || image.isEmpty() ? null : image;
		this.denoising = denoising;
		this.isRow = isRow;
		this.ratio = ratio == null ? "" : ratio;
	}

	/**
	 * 校验参数；不合法时抛出统一异常，客户端只需展示 message。
	 *
	 * @throws LocalModelApiException 动作缺失、尺寸/步数非法、分区参数不完整
	 */
	public void validate() {
		if (action == null) {
			throw new LocalModelApiException("SDXL 请求缺少 action（txt2img / img2img / regional / interrogate）");
		}
		// 反推只用到图片，不检查采样参数
		if (action != SdxlAction.INTERROGATE) {
			if (steps <= 0) {
				throw new LocalModelApiException("SDXL 的 steps 必须大于 0：" + steps);
			}
			if (width <= 0 || height <= 0) {
				throw new LocalModelApiException("SDXL 的尺寸非法：" + width + "x" + height);
			}
		}
		if (action == SdxlAction.REGIONAL && (ratio.isEmpty() || !ratio.contains(","))) {
			throw new LocalModelApiException("分区绘图需要形如 1,1 的区域比例：" + ratio);
		}
	}
}
