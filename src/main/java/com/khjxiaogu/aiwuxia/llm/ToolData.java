package com.khjxiaogu.aiwuxia.llm;

import java.util.Map;

public class ToolData {
	public final Tool tool;
	public final String name;
	public final String description;
	public final Map<String,String> params;
	public ToolData(Tool tool, String name, String description, Map<String,String> params) {
		super();
		this.tool = tool;
		this.name = name;
		this.description = description;
		this.params = params;
	}

}
