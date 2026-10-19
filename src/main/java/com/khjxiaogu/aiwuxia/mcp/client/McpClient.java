package com.khjxiaogu.aiwuxia.mcp.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;

/**
 * MCP 客户端抽象。
 * <p>
 * 屏蔽传输方式（HTTP / stdio）的差异，向上只提供 MCP 语义：
 * 初始化握手、列出远程工具、调用远程工具。
 * 实现类负责 JSON-RPC 编解码与错误映射，{@link McpToolBridge} 负责把结果转换为本地 {@code ToolData}。
 * </p>
 * <p>
 * 实例不是线程安全的：调用方需要串行使用，或在外部加锁。
 * </p>
 */
public interface McpClient extends Closeable {

    /**
     * 客户端名称（通常等于配置中的 server 名称），用于工具前缀与日志。
     *
     * @return 名称，永不为 null
     */
    String getName();

    /**
     * 执行 MCP 初始化握手（initialize + notifications/initialized）。
     * <p>重复调用是幂等的：已初始化时直接返回。</p>
     *
     * @throws IOException  传输层失败
     * @throws McpException 协议错误或服务端拒绝
     */
    void initialize() throws IOException, McpException;

    /**
     * 是否已完成初始化。
     *
     * @return 已初始化返回 true
     */
    boolean isInitialized();

    /**
     * 协商后的协议版本。
     *
     * @return 版本字符串，未初始化时为 null
     */
    String getProtocolVersion();

    /**
     * 列出远程服务端的全部工具（自动跟随分页游标）。
     *
     * @return 工具列表，永不 null
     * @throws IOException  传输层失败
     * @throws McpException 协议错误
     */
    List<McpRemoteTool> listTools() throws IOException, McpException;

    /**
     * 调用远程工具。
     *
     * @param name      远程工具名称
     * @param arguments 参数对象，null 视为空参数
     * @return 工具返回的文本内容（多个文本片段已拼接）
     * @throws IOException  传输层失败
     * @throws McpException 协议错误、工具不存在、配额不足等
     */
    String callTool(String name, JsonObject arguments) throws IOException, McpException;

    /** 关闭客户端并释放连接（HTTP 为无状态，stdio 会结束子进程）。 */
    @Override
    void close() throws IOException;
}
