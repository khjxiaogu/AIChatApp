package com.khjxiaogu.aiwuxia.llm.message;

import java.util.List;

import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;

public class ToolCallContent implements MessageContent {

	List<ToolCall> toolCalls;
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
	public List<ToolCall> getToolCalls() {
		return toolCalls;
	}
}
