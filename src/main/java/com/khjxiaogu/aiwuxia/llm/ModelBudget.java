package com.khjxiaogu.aiwuxia.llm;

import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;

/**
 * 模型消费预算倍率估算。
 * <p>
 * 预算是“本次请求大概会花多少钱”的相对值，以 DeepSeek Flash 为基准 1。各提供商只需给出
 * 自己的每 token 等效单价，本工具按请求的提示规模与最大输出量折算成相对倍率。
 * </p>
 * <p>
 * 提示 token 按上下文字符数粗略折算；输出 token 取 {@code maxToken}，推理模式下再按推理强度
 * 放大（思维链通常会让实际输出成倍增加）。由于同一请求在比较不同提供商时使用相同的 token 估算，
 * 倍数只取决于各提供商的单价比例，估算误差不会改变排序方向。
 * </p>
 */
public final class ModelBudget {

	/** DeepSeek Flash 的提示 token 基准单价（每 token 等效配额）。 */
	public static final double BASE_PROMPT = 0.5;
	/** DeepSeek Flash 的输出 token 基准单价（每 token 等效配额）。 */
	public static final double BASE_COMPLETION = 2.0;

	/**
	 * Mimo Flash 档位：语音标注、回复判定等轻量任务默认使用的预算倍率。
	 * <p>
	 * 按提示与输出各占一半的典型轻量请求估算：Mimo Flash 的单价约为 DeepSeek Flash 的 0.6 倍。
	 * </p>
	 */
	public static final double MIMO = 0.6;

	/** 没有历史时给一个最小的提示规模，避免提示项在比值里完全消失。 */
	private static final double MIN_PROMPT_TOKENS = 500;

	/** 粗略的字符 → token 折算比例（中英文混排取折中值）。 */
	private static final double CHARS_PER_TOKEN = 2.0;

	private ModelBudget() {
	}

	/**
	 * 按提供商的每 token 单价估算相对 DeepSeek Flash 的消费倍率。
	 *
	 * @param request        请求
	 * @param promptCost     提供商的提示 token 单价（缓存未命中）
	 * @param completionCost 提供商的输出 token 单价
	 * @return 预算倍率，基准提供商的请求返回 1
	 */
	public static double ratio(AIRequest request, double promptCost, double completionCost) {
		double promptTokens = promptTokens(request);
		double completionTokens = completionTokens(request);
		double cost = promptTokens * promptCost + completionTokens * completionCost;
		double base = promptTokens * BASE_PROMPT + completionTokens * BASE_COMPLETION;
		return base <= 0 ? 1 : cost / base;
	}

	/** 估算本次请求的提示 token 数。 */
	public static double promptTokens(AIRequest request) {
		double chars = 0;
		if (request != null) {
			for (HistoryItem item : request.history) {
				if (item == null) {
					continue;
				}
				MessageContents contents = item.getContextContent();
				if (contents != null) {
					chars += contents.toText().length();
				}
			}
		}
		return Math.max(MIN_PROMPT_TOKENS, chars / CHARS_PER_TOKEN);
	}

	/** 估算本次请求的最大输出 token 数（推理模式按推理强度放大）。 */
	public static double completionTokens(AIRequest request) {
		double tokens = request == null || request.maxToken <= 0 ? 1024 : request.maxToken;
		if (request != null && request.category == ModelCategory.REASONING) {
			tokens *= reasoningFactor(request.strength);
		}
		return tokens;
	}

	/** 思维链对输出量的放大系数。 */
	private static double reasoningFactor(ReasoningStrength strength) {
		if (strength == null) {
			return 2;
		}
		switch (strength) {
		case NONE:
			return 1.5;
		case WEAK:
			return 2;
		case MEDIUM:
			return 3;
		case STRONG:
			return 4;
		default:
			return 2;
		}
	}
}
