package com.khjxiaogu.aiwuxia.utils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.ToolData;

public class MCPTools {

    private final Map<String,ToolData> tools=new HashMap<>();
    public synchronized void register(ToolData tool) {
    	tools.put(tool.name, tool);
    }
    public ToolData get(String str) {
    	return tools.get(str);
    }
    public void addTool(AIRequest.Builder builder) {
    	builder.addTools(tools.values());
    }

    public void addTool(Collection<ToolData> col) {
    	col.addAll(tools.values());
    }
}
