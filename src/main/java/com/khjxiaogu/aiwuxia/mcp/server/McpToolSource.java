package com.khjxiaogu.aiwuxia.mcp.server;

import java.util.Collection;

import com.khjxiaogu.aiwuxia.llm.ToolData;

/**
 * MCP 服务的工具来源。
 * <p>
 * 工具注册表（{@link McpToolRegistry}）除了保存静态注册的工具外，还可以挂载若干“动态来源”。
 * 每次 {@code tools/list} 或 {@code tools/call} 时，注册表会按访问令牌对应的用户 ID
 * 向来源询问当前可用工具，因此来源可以按用户返回不同的工具集合
 * （例如某个用户正在运行的 AI 会话中的工具）。
 * </p>
 * <p>
 * 实现必须是线程安全且快速的：来源会在每次 MCP 请求中被调用。
 * 静态注册的工具优先级高于来源返回的同名工具。
 * </p>
 */
public interface McpToolSource {

    /**
     * 来源名称，仅用于日志与排错。
     *
     * @return 名称，永不为 null
     */
    String getName();

    /**
     * 返回指定用户当前可用的工具。
     *
     * @param uid 访问令牌所属用户 ID，可能为 null
     * @return 工具集合，可为 null（等价于空集合）
     */
    Collection<ToolData> getTools(String uid);
}
