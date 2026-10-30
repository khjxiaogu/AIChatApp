package com.khjxiaogu.aiwuxia.state.history.message;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;

/**
 * 工具调用返回的结果内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含工具的唯一标识符与结果内容。
 * 结果内容（{@link #getResult()}）是 {@link MessageContents}，可以按顺序包含文本、图片等，
 * <b>只提供给模型进一步处理，一般不展示给用户</b>；需要给用户看的图片/音频等应通过工具结果的
 * 附件通道返回（见 {@code ToolResult#getAttachments()}）。
 * </p>
 * <p>
 * 另携带结果的用户友好显示（{@link ToolCallDisplay}：简述 + 具体内容），
 * 由工具自己的显示转换器生成，仅用于展示，不参与持久化与模型请求。
 * </p>
 */
public class ToolContent implements MessageContent {
	final String toolId;
	final MessageContents result;
	/** 结果的用户友好显示；仅用于展示，不持久化 */
	transient ToolCallDisplay display;

	/**
	 * 使用工具 ID 和纯文本结果构造工具结果内容（无用户友好显示）。
	 *
	 * @param toolId 工具调用的唯一标识符，不可为 null
	 * @param result 工具执行后的返回结果文本，不可为 null
	 */
	public ToolContent(String toolId, String result) {
		this(toolId, new MutableMessageContents(result), null);
	}

	/**
	 * 使用工具 ID 和结果内容构造工具结果内容（无用户友好显示）。
	 *
	 * @param toolId 工具调用的唯一标识符，不可为 null
	 * @param result 工具执行后的返回结果内容，不可为 null
	 */
	public ToolContent(String toolId, MessageContents result) {
		this(toolId, result, null);
	}

	/**
	 * 使用工具 ID、纯文本结果与用户友好显示构造工具结果内容。
	 *
	 * @param toolId  工具调用的唯一标识符，不可为 null
	 * @param result  工具执行后的返回结果文本，不可为 null
	 * @param display 结果的用户友好显示，可为 null
	 */
	public ToolContent(String toolId, String result, ToolCallDisplay display) {
		this(toolId, new MutableMessageContents(result), display);
	}

	/**
	 * 使用工具 ID、结果内容与用户友好显示构造工具结果内容。
	 *
	 * @param toolId  工具调用的唯一标识符，不可为 null
	 * @param result  工具执行后的返回结果内容，不可为 null
	 * @param display 结果的用户友好显示，可为 null
	 */
	public ToolContent(String toolId, MessageContents result, ToolCallDisplay display) {
		super();
		this.toolId = toolId;
		this.result = result == null ? MessageContents.EMPTY : result;
		this.display = display;
	}

	/**
	 * 获取结果的用户友好显示（简述 + 具体内容）。
	 * <p>没有显示信息时返回 {@link ToolCallDisplay#EMPTY}。</p>
	 */
	public ToolCallDisplay getDisplay() {
		return display == null ? ToolCallDisplay.EMPTY : display;
	}

	/**
	 * 是否携带生成时计算好的用户友好显示。
	 * <p>
	 * 显示信息不随历史持久化，因此从存储重新加载后本方法返回 {@code false}；
	 * 此时应由工具调用管理器按当前注册的工具重算
	 * （{@code ToolCallManager#resultDisplayFor}）。
	 * </p>
	 */
	public boolean hasDisplay() {
		return display != null;
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
		return result == null ? "" : result.toText();
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
	 * 获取工具执行的结果内容（只提供给模型处理，一般不展示给用户）。
	 *
	 * @return 结果内容，永不为 null
	 */
	public MessageContents getResult() {
		return result == null ? MessageContents.EMPTY : result;
	}

	@Override
	public MessageContent copy() {
		return this;
	}
}
