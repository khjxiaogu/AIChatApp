package com.khjxiaogu.aiwuxia.modelproxy.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Qwen-Image 调用的参数，独立的值对象。
 */
public final class QwenParams {

	/** 动作：文生图。 */
	public static final String TEXT2IMAGE = "text2image";
	/** 动作：图片编辑。 */
	public static final String EDIT = "edit";

	/** 动作。 */
	public final String action;
	/** 提示词。 */
	public final String prompt;
	/** 负面提示词。 */
	public final String negative;
	/** 宽度。 */
	public final int width;
	/** 高度。 */
	public final int height;
	/** 采样步数。 */
	public final int steps;
	/** 引导强度。 */
	public final double cfg;
	/** 随机种子。 */
	public final long seed;
	/** 是否极速草稿。 */
	public final boolean fast;
	/** 输入图片（附件 id 或链接）。 */
	public final List<String> images;

	public QwenParams(String action, String prompt, String negative, int width, int height, int steps, double cfg,
			long seed, boolean fast, List<String> images) {
		this.action = action == null || action.isEmpty() ? TEXT2IMAGE : action;
		this.prompt = prompt == null ? "" : prompt;
		this.negative = negative == null ? "" : negative;
		this.width = width;
		this.height = height;
		this.steps = steps;
		this.cfg = cfg;
		this.seed = seed;
		this.fast = fast;
		this.images = images == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(images));
	}

	/**
	 * 校验参数。
	 *
	 * @throws LocalModelApiException 动作未知、提示词为空或编辑缺少输入图
	 */
	public void validate() {
		if (!TEXT2IMAGE.equals(action) && !EDIT.equals(action)) {
			throw new LocalModelApiException("未知的 Qwen 动作：" + action + "（可用：text2image / edit）");
		}
		if (prompt.isEmpty()) {
			throw new LocalModelApiException("Qwen 缺少提示词 prompt");
		}
		if (EDIT.equals(action) && images.isEmpty()) {
			throw new LocalModelApiException("Qwen 改图需要至少一张输入图片");
		}
		if (width <= 0 || height <= 0 || steps <= 0) {
			throw new LocalModelApiException("Qwen 的尺寸或步数非法：" + width + "x" + height + " steps=" + steps);
		}
	}
}
