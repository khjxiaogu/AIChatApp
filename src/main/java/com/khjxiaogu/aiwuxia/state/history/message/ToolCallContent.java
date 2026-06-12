package com.khjxiaogu.aiwuxia.state.history.message;

import java.util.List;

import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;

/**
 * AI 模型发起的工具调用消息内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含一组 {@link ToolCall} 对象，
 * 表示 AI 模型请求执行的一个或多个工具调用。
 * </p>
 * <ul>
 *   <li>{@link #toText()} 始终返回空字符串 —— 工具调用不直接对应文本。</li>
 *   <li>{@link #copy()} 返回自身引用（{@code this}），因为该对象被视为不可变。</li>
 * </ul>
 */
public class ToolCallContent implements MessageContent {

	List<ToolCall> toolCalls;

	/**
	 * 使用指定的工具调用列表构造工具调用内容。
	 *
	 * @param toolCalls 工具调用列表，不可为 null
	 */
	public ToolCallContent(List<ToolCall> toolCalls) {
		super();
		this.toolCalls = toolCalls;
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
		return "";
	}

	@Override
	public String getType() {
		return "tool_call";
	}

	/**
	 * 获取该消息中包含的所有工具调用。
	 *
	 * @return 工具调用列表，不可为 null
	 */
	public List<ToolCall> getToolCalls() {
		return toolCalls;
	}

	@Override
	public MessageContent copy() {
		return this;
	}
}
