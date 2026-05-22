package com.khjxiaogu.aiwuxia.utils;

import java.util.HashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.ToolData;

public class ToolRegistry {

    private static final Map<String,ToolData> tools=new HashMap<>();
    public static synchronized void register(ToolData tool) {
    	tools.put(tool.name, tool);
    }
    public static ToolData get(String str) {
    	return tools.get(str);
    }
}
