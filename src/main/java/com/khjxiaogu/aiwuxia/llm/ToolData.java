package com.khjxiaogu.aiwuxia.llm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;

/**
 * 一个工具的完整定义。
 * <p>
 * 本类只描述工具本身，不再充当多工具集合的容器。多个工具如果需要共享配置或状态，
 * 由实现 {@link ToolProvider} 的类统一提供；相互独立的工具直接使用本类即可。
 * </p>
 * <p>
 * {@link #async} 与 {@link #maxCalls} 是工具的默认注册参数，注册时可被
 * {@code ToolCallManager} 的重载覆盖；{@link #orderSensitive} 声明本工具是顺序屏障
 * （默认整批并发执行）。
 * </p>
 */
public class ToolData {
	public final Tool tool;
	public final String name;
	public final String description;
	public final Map<String, String> params;
	/**
	 * 工具调用显示转换器：把原始调用信息转换为用户友好的简述与具体内容。
	 * <p>默认转换器随工具一起创建：简述为工具名称、具体内容为空。</p>
	 */
	public final ToolCallPresenter presenter;
	/** 默认是否异步：true 时管理器立即返回“任务已创建”，完成时通知会话。 */
	public final boolean async;
	/** 默认单工具最大调用次数，-1 表示不限。 */
	public final int maxCalls;
	/**
	 * 是否为会话专属工具。
	 * <p>会话专属工具不会通过公共 MCP 服务端暴露给远程用户，例如 QQ/绘画会话的发送图片工具。</p>
	 */
	public final boolean sessionOnly;
	/**
	 * 调用顺序是否重要。
	 * <p>
	 * 一批工具调用默认并发执行。声明顺序重要的工具是<b>屏障</b>：
	 * 启动它之前先等此前所有同步调用结束，它（若为同步工具）真正执行完成后
	 * 才启动后续调用；声明为异步的工具只返回任务回执，不会被等待。
	 * 发送图片/语音这类用户可见的副作用、以及“先下载再使用”这类有前后依赖的
	 * 工具应当声明顺序重要。
	 * </p>
	 */
	public final boolean orderSensitive;

	public ToolData(Tool tool, String name, String description, Map<String, String> params) {
		this(tool, name, description, params, ToolCallPresenter.defaults());
	}

	public ToolData(Tool tool, String name, String description, Map<String, String> params,
			ToolCallPresenter presenter) {
		this(tool, name, description, params, presenter, false, -1, false, false);
	}

	public ToolData(Tool tool, String name, String description, Map<String, String> params,
			ToolCallPresenter presenter, boolean async, int maxCalls, boolean sessionOnly) {
		this(tool, name, description, params, presenter, async, maxCalls, sessionOnly, false);
	}

	public ToolData(Tool tool, String name, String description, Map<String, String> params,
			ToolCallPresenter presenter, boolean async, int maxCalls, boolean sessionOnly, boolean orderSensitive) {
		super();
		this.tool = tool;
		this.name = name;
		this.description = description;
		this.params = params;
		this.presenter = presenter == null ? ToolCallPresenter.defaults() : presenter;
		this.async = async;
		this.maxCalls = maxCalls;
		this.sessionOnly = sessionOnly;
		this.orderSensitive = orderSensitive;
	}

	/** 三参数、直接返回文本的简单工具实现。 */
	@FunctionalInterface
	public interface TextTool {
		String run(String params, AIOutput output, ToolCallManager manager);
	}

	public static class Builder {
		private Tool tool;
		private String name;
		private String description;
		private Map<String, String> params = new HashMap<>();
		private ToolCallPresenter presenter;
		private boolean async;
		private int maxCalls = -1;
		private boolean sessionOnly;
		private boolean orderSensitive;

		public Builder tool(Tool tool) {
			this.tool = tool;
			return this;
		}

		/**
		 * 设置只使用参数的简单工具实现。
		 * <p>内部会适配为返回已完成的 {@link ToolResult}。</p>
		 *
		 * @param simple 参数文本到结果文本的转换
		 * @return 当前 Builder
		 */
		public Builder tool(Function<String, String> simple) {
			this.tool = (params, output, manager) -> CompletableFuture
					.completedFuture(ToolResult.text(simple.apply(params)));
			return this;
		}

		/**
		 * 设置三参数、直接返回文本的工具实现。
		 * <p>内部会适配为返回已完成的 {@link ToolResult}。</p>
		 *
		 * @param textTool 参数、输出与管理器到结果文本的转换
		 * @return 当前 Builder
		 */
		public Builder toolText(TextTool textTool) {
			this.tool = (params, output, manager) -> CompletableFuture
					.completedFuture(ToolResult.text(textTool.run(params, output, manager)));
			return this;
		}

		/**
		 * 设置返回纯文本 future 的工具实现。
		 *
		 * @param futureTool 参数文本到文本 future 的转换
		 * @return 当前 Builder
		 */
		public Builder toolFuture(Function<String, CompletableFuture<String>> futureTool) {
			this.tool = (params, output, manager) -> futureTool.apply(params)
					.thenApply(ToolResult::text);
			return this;
		}

		/**
		 * 设置返回完整结果 future 的工具实现。
		 *
		 * @param resultTool 参数文本到结果 future 的转换
		 * @return 当前 Builder
		 */
		public Builder toolResult(Function<String, CompletableFuture<ToolResult>> resultTool) {
			this.tool = (params, output, manager) -> resultTool.apply(params);
			return this;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		/**
		 * 设置默认是否异步。
		 *
		 * @param async true 表示异步
		 * @return 当前 Builder
		 */
		public Builder async(boolean async) {
			this.async = async;
			return this;
		}

		/** 将工具标记为异步。 */
		public Builder async() {
			return async(true);
		}

		/** 将工具标记为同步（默认值）。 */
		public Builder sync() {
			return async(false);
		}

		/**
		 * 设置默认单工具最大调用次数。
		 *
		 * @param maxCalls 最大次数，-1 表示不限
		 * @return 当前 Builder
		 */
		public Builder maxCalls(int maxCalls) {
			this.maxCalls = maxCalls < 0 ? -1 : maxCalls;
			return this;
		}

		public Builder callLimit(int maxCalls) {
			return maxCalls(maxCalls);
		}

		/**
		 * 标记为会话专属工具（不通过公共 MCP 服务端暴露）。
		 *
		 * @return 当前 Builder
		 */
		public Builder sessionOnly() {
			this.sessionOnly = true;
			return this;
		}

		public Builder sessionOnly(boolean sessionOnly) {
			this.sessionOnly = sessionOnly;
			return this;
		}

		/**
		 * 声明本工具的调用顺序重要。
		 * <p>
		 * 同一批工具调用默认并发执行；只要其中含有顺序重要的工具，整批就按模型给出的顺序
		 * 逐个执行。有前后依赖（先下载再使用）或用户可见副作用（发送图片/语音）的工具应声明本项。
		 * </p>
		 *
		 * @return 当前 Builder
		 */
		public Builder orderSensitive() {
			return orderSensitive(true);
		}

		/**
		 * 设置调用顺序是否重要。
		 *
		 * @param orderSensitive {@code true} 表示同一批工具调用必须按顺序逐个执行
		 * @return 当前 Builder
		 */
		public Builder orderSensitive(boolean orderSensitive) {
			this.orderSensitive = orderSensitive;
			return this;
		}

		/**
		 * 设置工具调用显示转换器（简述替代工具名称，具体内容用于展示）。
		 * <p>不设置时使用默认转换器：简述为工具名称、具体内容为空。</p>
		 *
		 * @param presenter 显示转换器
		 * @return 当前 Builder
		 */
		public Builder presenter(ToolCallPresenter presenter) {
			this.presenter = presenter;
			return this;
		}

		// 添加单个参数
		public Builder putParam(String key, String value) {
			this.params.put(key, value);
			return this;
		}

		// 批量添加参数（覆盖已有键）
		public Builder params(Map<String, String> params) {
			this.params.putAll(params);
			return this;
		}

		public ToolData build() {
			// 防御性复制 + 不可变视图，保证外部无法修改 ToolData 内部的 Map
			Map<String, String> immutableParams = new HashMap<>(params);
			return new ToolData(tool, name, description, immutableParams, presenter, async, maxCalls, sessionOnly,
					orderSensitive);
		}

		public Builder(String name, String description) {
			super();
			this.name = name;
			this.description = description;
		}
	}
}
