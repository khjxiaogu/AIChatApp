package com.khjxiaogu.aiwuxia.mcp.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.khjxiaogu.aiwuxia.llm.ToolData;

/**
 * MCP 服务端工具注册表。
 * <p>
 * 负责把本地已有的 {@link ToolData} 暴露给 MCP：静态注册的工具（{@link #register(ToolData)}）
 * 对所有令牌可见；动态来源（{@link #addSource(McpToolSource)}）按用户返回工具，
 * 静态工具与来源工具同名时以静态工具为准。
 * </p>
 * <p>
 * 本类只做“查表”，不执行工具、不校验令牌，执行与计费由 {@link McpServer} 负责。
 * 所有公开方法都是线程安全的，可在运行期间随时增删工具。
 * </p>
 */
public class McpToolRegistry {

    /** 静态工具表：名称 → 工具定义，保持注册顺序。 */
    private final Map<String, ToolData> tools = new LinkedHashMap<>();
    /** 动态工具来源。 */
    private final List<McpToolSource> sources = new CopyOnWriteArrayList<>();

    /**
     * 注册一个工具，同名覆盖。
     *
     * @param tool 工具定义，null 忽略
     */
    public synchronized void register(ToolData tool) {
        if (tool != null && tool.name != null && !tool.name.isEmpty()) {
            tools.put(tool.name, tool);
        }
    }

    /**
     * 批量注册工具，同名覆盖。
     *
     * @param toRegister 工具集合，可为 null
     */
    public synchronized void registerAll(Collection<ToolData> toRegister) {
        if (toRegister == null) {
            return;
        }
        for (ToolData tool : toRegister) {
            register(tool);
        }
    }

    /**
     * 注销静态工具。
     *
     * @param name 工具名称
     * @return 原先是否存在
     */
    public synchronized boolean unregister(String name) {
        return name != null && tools.remove(name) != null;
    }

    /** 清空全部静态工具（动态来源不受影响）。 */
    public synchronized void clear() {
        tools.clear();
    }

    /**
     * 添加一个动态工具来源（重复添加同一实例会被忽略）。
     *
     * @param source 来源，null 忽略
     */
    public void addSource(McpToolSource source) {
        if (source != null && !sources.contains(source)) {
            sources.add(source);
        }
    }

    /**
     * 移除动态工具来源。
     *
     * @param source 来源
     * @return 原先是否存在
     */
    public boolean removeSource(McpToolSource source) {
        return source != null && sources.remove(source);
    }

    /**
     * 当前静态注册的工具（只读快照）。
     *
     * @return 工具列表，永不 null
     */
    public synchronized List<ToolData> getStaticTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 列出指定用户可见的全部工具（静态 + 动态来源，按名称去重）。
     *
     * @param uid 访问令牌所属用户 ID，可为 null
     * @return 工具列表，永不 null
     */
    public List<ToolData> list(String uid) {
        Map<String, ToolData> merged = new LinkedHashMap<>();
        synchronized (this) {
            merged.putAll(tools);
        }
        for (McpToolSource source : sources) {
            Collection<ToolData> provided;
            try {
                provided = source.getTools(uid);
            } catch (Throwable ex) {
                ex.printStackTrace();
                continue;
            }
            if (provided == null) {
                continue;
            }
            for (ToolData tool : provided) {
                if (tool != null && tool.name != null && !merged.containsKey(tool.name)) {
                    merged.put(tool.name, tool);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 查找指定用户可调用的工具。
     *
     * @param name 工具名称
     * @param uid  访问令牌所属用户 ID，可为 null
     * @return 工具定义，不存在返回 null
     */
    public ToolData find(String name, String uid) {
        if (name == null) {
            return null;
        }
        synchronized (this) {
            ToolData tool = tools.get(name);
            if (tool != null) {
                return tool;
            }
        }
        for (McpToolSource source : sources) {
            Collection<ToolData> provided;
            try {
                provided = source.getTools(uid);
            } catch (Throwable ex) {
                ex.printStackTrace();
                continue;
            }
            if (provided == null) {
                continue;
            }
            for (ToolData tool : provided) {
                if (tool != null && name.equals(tool.name)) {
                    return tool;
                }
            }
        }
        return null;
    }

    /**
     * 全部已注册工具的名称（静态工具，用于冲突检测）。
     *
     * @return 名称集合
     */
    public synchronized Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }
}
