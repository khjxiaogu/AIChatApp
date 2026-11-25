package com.khjxiaogu.aiwuxia.mcp.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.khjxiaogu.aiwuxia.llm.Tool;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.UrlFileResolver;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;

/**
 * 把会话工具适配成 MCP 的 URL 上下文。
 * <p>
 * MCP 服务端直接暴露的静态工具由 {@link McpServer} 自己的 {@link ToolCallManager} 解析 URL；
 * 但 {@code exposeSessionTools} 暴露出来的是各个会话自己的工具，它们使用会话自己的对象存储，
 * 不能套用 MCP 服务的 {@code mcpStorage}。本类给这类工具绑定一个会话存储的
 * {@link UrlFileResolver}，并在对外说明中显示 URL 上下文。
 * </p>
 * <p>
 * 真正的 URL 下载/转存逻辑仍在 {@link ToolCallManager}/{@link UrlFileResolver}，本类只负责
 * 选择正确的存储并复用同一套解析流程。
 * </p>
 */
public final class McpUrlAttachmentTool {

	private final ObjectStorageProvider storage;

	private McpUrlAttachmentTool(ObjectStorageProvider storage) {
		this.storage = storage;
	}

	/**
	 * 包装单个工具；没有 URL 参数或存储不可用时原样返回。
	 *
	 * @param tool    原工具
	 * @param storage 会话自己的对象存储
	 * @return 包装后的工具
	 */
	public static ToolData wrap(ToolData tool, ObjectStorageProvider storage) {
		if (tool == null || tool.tool == null || storage == null || !tool.hasUrlParams()) {
			return tool;
		}
		return new McpUrlAttachmentTool(storage).decorate(tool);
	}

	/**
	 * 批量包装工具；空集合返回空列表。
	 *
	 * @param tools   原工具集合，可为 null
	 * @param storage 会话自己的对象存储
	 * @return 包装后的工具列表
	 */
	public static List<ToolData> wrapAll(Collection<ToolData> tools, ObjectStorageProvider storage) {
		if (tools == null || tools.isEmpty()) {
			return Collections.emptyList();
		}
		List<ToolData> wrapped = new ArrayList<>(tools.size());
		for (ToolData tool : tools) {
			wrapped.add(wrap(tool, storage));
		}
		return wrapped;
	}

	/** 用会话存储包装工具，并保持原始元数据。 */
	private ToolData decorate(ToolData tool) {
		ToolData urlTool = tool.forUrlContext();
		Tool original = tool.tool;
		// 每个包装工具复用同一个管理器即可；它只负责 URL → 会话存储 ID 的转换。
		final ToolCallManager resolverManager = new ToolCallManager()
				.setFileResolver(new UrlFileResolver(storage, true));
		Tool wrapped = (params, output, context) -> {
			if (tool.async) {
				return CompletableFuture.supplyAsync(
						() -> resolverManager.resolveToolArguments(urlTool, params))
						.thenCompose(resolved -> original.run(resolved, output, context));
			}
			String resolved = resolverManager.resolveToolArguments(urlTool, params);
			return original.run(resolved, output, context);
		};
		return new ToolData(wrapped, urlTool.name, urlTool.description, urlTool.params, urlTool.presenter,
				urlTool.async, urlTool.maxCalls, urlTool.sessionOnly, urlTool.orderSensitive, urlTool.paramLabels,
				urlTool.optionalParams, Collections.emptyMap());
	}
}
