package com.khjxiaogu.aiwuxia.state.history.message;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;

/**
 * 工具调用返回的结果内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含工具的唯一标识符和执行结果字符串。
 * {@link #toText()} 直接返回工具的执行结果。
 * </p>
 * <p>
 * 另携带结果的用户友好显示（{@link ToolCallDisplay}：简述 + 具体内容），
 * 由工具自己的显示转换器生成，仅用于展示，不参与持久化与模型请求。
 * </p>
 */
public class ToolContent implements MessageContent {
	String toolId;
	String result;
	/** 结果的用户友好显示；仅用于展示，不持久化 */
	transient ToolCallDisplay display;

	/**
	 * 使用工具 ID 和执行结果构造工具结果内容（无用户友好显示）。
	 *
	 * @param toolId 工具调用的唯一标识符，不可为 null
	 * @param result 工具执行后的返回结果字符串，不可为 null
	 */
	public ToolContent(String toolId, String result) {
		this(toolId, result, null);
	}

	/**
	 * 使用工具 ID、执行结果与用户友好显示构造工具结果内容。
	 *
	 * @param toolId  工具调用的唯一标识符，不可为 null
	 * @param result  工具执行后的返回结果字符串，不可为 null
	 * @param display 结果的用户友好显示，可为 null
	 */
	public ToolContent(String toolId, String result, ToolCallDisplay display) {
		super();
		this.toolId = toolId;
		this.result = result;
		this.display = display;
	}

	/**
	 * 获取结果的用户友好显示（简述 + 具体内容）。
	 * <p>没有显示信息时返回 {@link ToolCallDisplay#EMPTY}。</p>
	 */
	public ToolCallDisplay getDisplay() {
		return display == null ? ToolCallDisplay.EMPTY : display;
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
		return new ToolContent(toolId, result, display);
	}
}
