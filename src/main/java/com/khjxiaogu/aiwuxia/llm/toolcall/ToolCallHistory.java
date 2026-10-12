package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.List;

import com.google.gson.JsonArray;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

/**
 * 工具调用历史写入接口。
 * <p>
 * {@link ToolCallManager} 只负责“怎么调用”，不关心“历史请求长什么样”。
 * 各模型提供者实现本接口，把助手的工具调用请求与工具结果按自身协议写入请求历史
 * （通常是 OpenAI 兼容的 {@code messages} 数组）。
 * </p>
 */
public interface ToolCallHistory {

	/**
	 * 创建 OpenAI 兼容协议的历史写入器。
	 *
	 * @param messages 请求体中的 messages 数组
	 */
	public static ToolCallHistory of(JsonArray messages) {
		return new JsonToolCallHistory(messages);
	}

	/** 空实现：历史由调用方自行处理（例如只做一次性请求的场景）。 */
	public static final ToolCallHistory NONE = new ToolCallHistory() {
		@Override
		public void appendAssistantToolCalls(String reasonerText, List<ToolCall> toolCalls) {
		}

		@Override
		public void appendToolResult(ToolContent result) {
		}
	};

	/**
	 * 追加一条助手消息，内容包含思维链文本与工具调用请求。
	 *
	 * @param reasonerText 本轮累积的思维链/正文文本，可能为空
	 * @param toolCalls    本轮模型请求的工具调用
	 */
	void appendAssistantToolCalls(String reasonerText, List<ToolCall> toolCalls);

	/**
	 * 追加一条工具结果消息。
	 *
	 * @param result 工具调用结果
	 */
	void appendToolResult(ToolContent result);
}
