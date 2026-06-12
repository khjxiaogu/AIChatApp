package com.khjxiaogu.aiwuxia.llm.message;

public class ToolContent implements MessageContent {
	String toolId;
	String result;
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

	public String getToolId() {
		return toolId;
	}

	public String getResult() {
		return result;
	}

	@Override
	public MessageContent copy() {
		return new ToolContent(toolId,result);
	}
}
