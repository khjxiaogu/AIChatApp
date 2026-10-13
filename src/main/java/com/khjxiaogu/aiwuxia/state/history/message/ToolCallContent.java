package com.khjxiaogu.aiwuxia.state.history.message;

import java.util.ArrayList;
import java.util.List;

import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;

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
 * <p>
 * 此外还携带由 {@link ToolCallPresenter} 转换出的用户友好显示
 * （{@link ToolCallDisplay}：简述替代工具名称 + 具体内容），与工具调用一一对应；
 * 显示信息仅用于展示，不参与持久化与模型请求。
 * </p>
 */
public class ToolCallContent implements MessageContent {

	List<ToolCall> toolCalls;

	/** 与 {@link #toolCalls} 一一对应的用户友好显示；仅用于展示，不持久化 */
	transient List<ToolCallDisplay> displays;

	/**
	 * 使用指定的工具调用列表构造工具调用内容（显示使用默认值：简述为工具名称、内容为空）。
	 *
	 * @param toolCalls 工具调用列表，不可为 null
	 */
	public ToolCallContent(List<ToolCall> toolCalls) {
		this(toolCalls, null);
	}

	/**
	 * 使用指定的工具调用列表与其用户友好显示构造工具调用内容。
	 *
	 * @param toolCalls 工具调用列表，不可为 null
	 * @param displays  与 {@code toolCalls} 顺序一致的显示列表，可为 null
	 */
	public ToolCallContent(List<ToolCall> toolCalls, List<ToolCallDisplay> displays) {
		super();
		this.toolCalls = toolCalls;
		this.displays = displays;
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

	/**
	 * 获取与工具调用一一对应的用户友好显示。
	 * <p>未转换的项按顺序回退为默认显示（简述为工具名称、具体内容为空）。</p>
	 *
	 * @return 与 {@link #getToolCalls()} 等长的显示列表
	 */
	public List<ToolCallDisplay> getDisplays() {
		List<ToolCallDisplay> result = new ArrayList<>(toolCalls.size());
		for (int i = 0; i < toolCalls.size(); i++) {
			result.add(getDisplay(i));
		}
		return result;
	}

	/**
	 * 获取第 {@code index} 个工具调用的用户友好显示。
	 *
	 * @param index 工具调用下标
	 * @return 显示信息，越界时返回 {@link ToolCallDisplay#EMPTY}
	 */
	public ToolCallDisplay getDisplay(int index) {
		if (displays != null && index >= 0 && index < displays.size()) {
			ToolCallDisplay display = displays.get(index);
			if (display != null) {
				return display;
			}
		}
		if (index >= 0 && index < toolCalls.size()) {
			return ToolCallPresenter.defaultDisplay(toolCalls.get(index));
		}
		return ToolCallDisplay.EMPTY;
	}

	/**
	 * 获取第 {@code index} 个工具调用的用户友好简述（替代工具名称）。
	 *
	 * @param index 工具调用下标
	 */
	public String getBrief(int index) {
		return getDisplay(index).getBrief();
	}

	@Override
	public MessageContent copy() {
		return this;
	}
}
