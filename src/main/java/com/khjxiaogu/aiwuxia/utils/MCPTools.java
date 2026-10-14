package com.khjxiaogu.aiwuxia.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.ToolData;

/**
 * 一个 MCP 模块注册的工具集合。
 * <p>
 * 模块只负责“生产工具”，不再关心工具最终放到哪里：
 * 使用时通过 {@code AISession#registerTools(MCPTools)} 直接注册进会话的工具调用管理器，
 * 由管理器统一处理调用次数、权限、许可与执行。
 * </p>
 */
public class MCPTools {

    private final Map<String,ToolData> tools=new HashMap<>();

    /** 注册一个工具（同名覆盖）。 */
    public synchronized void register(ToolData tool) {
    	tools.put(tool.name, tool);
    }

    /** 取指定名称的工具，不存在返回 null。 */
    public ToolData get(String str) {
    	return tools.get(str);
    }

    /** 本模块注册的全部工具（只读视图）。 */
    public synchronized Collection<ToolData> getTools() {
    	return Collections.unmodifiableCollection(new ArrayList<>(tools.values()));
    }

    /** 把本模块的工具合并到另一个模块（同名覆盖目标中的同名工具）。 */
    public synchronized void mergeInto(MCPTools target) {
    	if (target != null) {
    		target.tools.putAll(tools);
    	}
    }
}
