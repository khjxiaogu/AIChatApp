package com.khjxiaogu.aiwuxia.llm;

import com.google.gson.JsonObject;

public interface Tool {
	public String run(JsonObject params);
}
