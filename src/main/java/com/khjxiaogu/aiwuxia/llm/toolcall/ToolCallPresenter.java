package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;

/**
 * 工具调用显示转换器：把工具的原始调用信息处理成用户友好的显示。
 * <p>
 * 每个工具在创建时都会带上一个转换器（见 {@code ToolData}）：未指定时使用
 * {@link #defaults()} —— <b>简述为工具名称、具体内容为空</b>；工具也可以在注册时通过
 * {@code ToolData.Builder.presenter(...)} 提供自己的转换器，例如把
 * {@code sdxl_gen_image} 显示为“生成图片”并把提示词作为具体内容。
 * </p>
 * <p>
 * {@link ToolCallContext} 在处理工具调用与结果时会调用本转换器，把转换结果写入
 * {@code ToolCallContent}/{@code ToolContent}，各会话据此直接下发用户友好显示。
 * 转换器抛出的异常不会影响工具执行，会回退为默认显示。
 * </p>
 */
@FunctionalInterface
public interface ToolCallPresenter {

	/**
	 * 转换一次工具调用。
	 *
	 * @param call 原始工具调用（含工具名与参数字符串）
	 * @return 用户友好显示，返回 null 时回退为默认显示
	 */
	ToolCallDisplay display(ToolCall call);

	/**
	 * 转换一次工具结果。
	 * <p>
	 * 工具结果本身只提供给模型进一步处理，默认不展示给用户；需要展示时由本方法生成
	 * 用户友好的摘要/内容，而不是把原始结果直接透出。
	 * </p>
	 *
	 * @param call   对应的原始工具调用
	 * @param result 工具结果的文本表示
	 * @return 结果的具体内容显示，默认空（即只显示调用的简述）
	 */
	default ToolCallDisplay resultDisplay(ToolCall call, String result) {
		return ToolCallDisplay.EMPTY;
	}

	/** 默认转换器：简述为工具名称，具体内容为空。 */
	public static ToolCallPresenter defaults() {
		return ToolCallPresenter::defaultDisplay;
	}

	/** 默认显示：简述为工具名称。 */
	public static ToolCallDisplay defaultDisplay(ToolCall call) {
		return ToolCallDisplay.of(toolName(call));
	}

	/** 取工具名称（容错）。 */
	public static String toolName(ToolCall call) {
		return call == null || call.function == null || call.function.name == null ? "" : call.function.name;
	}

	/** 便捷方法：只用函数生成简述，具体内容为空。 */
	public static ToolCallPresenter ofBrief(Function<ToolCall, String> brief) {
		return call -> ToolCallDisplay.of(brief.apply(call));
	}

	/**
	 * 便捷方法：固定简述 + 把参数中的某个字段作为具体内容。
	 * <p>例如 {@code ofArgument("生成图片", "positive")} 会把提示词作为具体内容展示。</p>
	 *
	 * @param brief       用户友好简述
	 * @param argumentKey 参数名（JSON 字段）
	 */
	public static ToolCallPresenter ofArgument(String brief, String argumentKey) {
		return call -> {
			String value = argument(call, argumentKey);
			return value == null || value.isEmpty() ? ToolCallDisplay.of(brief)
					: ToolCallDisplay.of(brief, new MutableMessageContents(value));
		};
	}
	public static ToolCallPresenter ofName(String brief) {
		return call -> {
			return ToolCallDisplay.of(brief);
		};
	}
	/**
	 * 从工具调用参数（JSON 字符串）中取出指定字段的文本值。
	 *
	 * @param call 原始工具调用
	 * @param key  参数字段名
	 * @return 字段文本；参数缺失、格式错误或字段为空时返回 null
	 */
	public static String argument(ToolCall call, String key) {
		if (call == null || call.function == null || call.function.arguments == null || key == null) {
			return null;
		}
		try {
			JsonElement element = JsonParser.parseString(call.function.arguments);
			if (!element.isJsonObject()) {
				return null;
			}
			JsonElement value = element.getAsJsonObject().get(key);
			return value == null || value.isJsonNull() ? null : value.getAsString();
		} catch (Throwable ex) {
			return null;
		}
	}
}
