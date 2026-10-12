package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.List;

import com.google.gson.JsonArray;
import com.khjxiaogu.aiwuxia.llm.HistoryRequestBuilder;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

/**
 * OpenAI 兼容协议的历史写入实现：直接向请求体的 {@code messages} 数组追加消息。
 * <p>
 * DeepSeek、MiMo、SiliconFlow 等提供者共用该实现，避免各自重复拼装
 * {@code assistant(tool_calls)} 与 {@code tool} 消息。
 * </p>
 */
public class JsonToolCallHistory implements ToolCallHistory {

	private final JsonArray messages;

	/**
	 * @param messages 请求体中的 messages 数组
	 */
	public JsonToolCallHistory(JsonArray messages) {
		this.messages = messages;
	}

	@Override
	public void appendAssistantToolCalls(String reasonerText, List<ToolCall> toolCalls) {
		if ((reasonerText == null || reasonerText.isEmpty()) && (toolCalls == null || toolCalls.isEmpty())) {
			return;
		}
		messages.add(HistoryRequestBuilder.createReasonerMessage(reasonerText == null ? "" : reasonerText, toolCalls));
	}

	@Override
	public void appendToolResult(ToolContent result) {
		messages.add(HistoryRequestBuilder.createToolMessage(result));
	}
}
