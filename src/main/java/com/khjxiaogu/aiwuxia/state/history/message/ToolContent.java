package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 工具调用返回的结果内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含工具的唯一标识符和执行结果字符串。
 * {@link #toText()} 直接返回工具的执行结果。
 * </p>
 */
public class ToolContent implements MessageContent {
	String toolId;
	String result;

	/**
	 * 使用工具 ID 和执行结果构造工具结果内容。
	 *
	 * @param toolId 工具调用的唯一标识符，不可为 null
	 * @param result 工具执行后的返回结果字符串，不可为 null
	 */
	public ToolContent(String toolId, String result) {
		super();
		this.toolId = toolId;
		this.result = result;
	}

	@Override
	public boolean isPlainText() {
		return false;
	}

	@Override
	public boolean isTextRepresentable() {
		return true;
	}

	@Override
	public String toText() {
		return result;
	}

	@Override
	public String getType() {
		return "tool";
	}

	/**
	 * 获取工具调用的唯一标识符。
	 *
	 * @return 工具 ID 字符串
	 */
	public String getToolId() {
		return toolId;
	}

	/**
	 * 获取工具执行的结果。
	 *
	 * @return 结果字符串
	 */
	public String getResult() {
		return result;
	}

	@Override
	public MessageContent copy() {
		return new ToolContent(toolId, result);
	}
}
