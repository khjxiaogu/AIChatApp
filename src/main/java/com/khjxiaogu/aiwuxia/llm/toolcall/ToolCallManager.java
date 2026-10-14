package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;

/**
 * 无状态的工具调用管理器。
 * <p>
 * 同时承担“工具注册表”（哪些工具可被调用）与“调用规则”（{@link ToolCallPolicy}）两个职责，
 * 是 {@code AIRequest} 携带的工具调用配置载体。
 * </p>
 * <p>
 * 本类<b>不保存任何过程状态</b>（不记录本轮收集了什么、已调用几次、是否提示过超限），
 * 所有字段 final，构造时对工具表做防御性拷贝并返回不可变视图，因此<b>线程安全、可全局共享/缓存</b>。
 * 单次模型调用过程中的可变状态全部放在 {@link ToolCallContext} 中，由
 * {@link ToolCallContext#create(ToolCallManager, com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput, ToolCallHistory)}
 * 创建，用完即弃。
 * </p>
 *
 * <p>注册工具与规则：</p>
 * <pre>{@code
 * ToolCallManager manager = ToolCallManager.builder()
 *     .tool(echoTool)
 *     .tools(otherTools)
 *     .maxRounds(10)
 *     .toolLimit("sdxl_gen_image", 3)
 *     .permission(ToolCallPermission.allowOnly("echo"))
 *     .build();
 * }</pre>
 *
 * @see ToolCallContext
 * @see ToolCallPolicy
 */
public class ToolCallManager {

	/** 不含任何工具的共享管理器，可用于“本次请求不提供工具”的场景。 */
	public static final ToolCallManager EMPTY = new ToolCallManager(Collections.<String, ToolData>emptyMap(),
			ToolCallPolicy.defaults());

	private final Map<String, ToolData> tools;
	private final ToolCallPolicy policy;

	/**
	 * @param tools  可用工具表（名称 → 工具），构造时拷贝，后续外部修改不影响本管理器
	 * @param policy 管理策略（轮次/总次数/单工具次数/权限/许可），为 null 时使用默认策略
	 */
	public ToolCallManager(Map<String, ToolData> tools, ToolCallPolicy policy) {
		this.tools = tools == null ? Collections.<String, ToolData>emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(tools));
		this.policy = policy == null ? ToolCallPolicy.defaults() : policy;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 基于当前管理器创建修改器，用于在已有工具与策略之上追加/调整。
	 */
	public Builder toBuilder() {
		return builder().tools(tools).policy(policy);
	}

	/** 可用工具表（不可变视图）。 */
	public Map<String, ToolData> getTools() {
		return tools;
	}

	/** 当前管理策略。 */
	public ToolCallPolicy getPolicy() {
		return policy;
	}

	/** 指定名称的工具是否可用。 */
	public boolean hasTool(String toolName) {
		return tools.containsKey(toolName);
	}

	/**
	 * 按当前注册的工具，把原始工具调用转换为用户友好显示。
	 * <p>
	 * 显示信息不随历史持久化，因此历史从存储重新加载后可用本方法重算，
	 * 保证加载后工具调用简述与具体内容同样可见。未注册该工具、转换器缺失或抛异常时，
	 * 回退为默认显示（简述为工具名称、具体内容为空）。
	 * </p>
	 *
	 * @param call 原始工具调用
	 * @return 用户友好显示，永不为 null
	 */
	public ToolCallDisplay displayFor(ToolCall call) {
		ToolData data = toolOf(call);
		if (data != null && data.presenter != null) {
			try {
				ToolCallDisplay display = data.presenter.display(call);
				if (display != null) {
					return display;
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
		return ToolCallPresenter.defaultDisplay(call);
	}

	/**
	 * 按当前注册的工具，把工具结果转换为用户友好显示。
	 *
	 * @param call         对应的原始工具调用，可为 null
	 * @param callDisplay  该调用的显示（用于回退简述），可为 null
	 * @param result       工具返回的原始结果文本
	 * @return 结果显示，永不为 null
	 */
	public ToolCallDisplay resultDisplayFor(ToolCall call, ToolCallDisplay callDisplay, String result) {
		ToolData data = toolOf(call);
		if (data != null && data.presenter != null) {
			try {
				ToolCallDisplay display = data.presenter.resultDisplay(call, result);
				if (display != null && (!display.getBrief().isEmpty() || display.hasContent())) {
					return display;
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
		String brief = callDisplay == null ? ToolCallPresenter.toolName(call) : callDisplay.getBrief();
		return ToolCallDisplay.of(brief);
	}

	private ToolData toolOf(ToolCall call) {
		if (call == null || call.function == null || call.function.name == null) {
			return null;
		}
		return tools.get(call.function.name);
	}

	@Override
	public String toString() {
		return "ToolCallManager [tools=" + tools.size() + ", policy=" + policy + "]";
	}

	/**
	 * {@link ToolCallManager} 构建器：集成工具注册与调用规则设置。
	 */
	public static class Builder {
		private final Map<String, ToolData> tools = new LinkedHashMap<>();
		/** 为 null 表示未设置，构建时使用 {@link ToolCallPolicy#defaults()} */
		private ToolCallPolicy policy;

		/** 注册单个工具（同名覆盖）。 */
		public Builder tool(ToolData tool) {
			if (tool != null && tool.name != null) {
				tools.put(tool.name, tool);
			}
			return this;
		}

		/** 批量注册工具（同名覆盖）。 */
		public Builder tools(Collection<ToolData> tools) {
			if (tools != null) {
				for (ToolData tool : tools) {
					tool(tool);
				}
			}
			return this;
		}

		/** 批量注册工具（名称 → 工具，同名覆盖）。 */
		public Builder tools(Map<String, ToolData> tools) {
			if (tools != null) {
				this.tools.putAll(tools);
			}
			return this;
		}

		/** 直接设置完整管理策略，替换此前通过下方便捷方法设置的规则。 */
		public Builder policy(ToolCallPolicy policy) {
			this.policy = policy;
			return this;
		}

		/** 便捷方法：设置轮次上限。 */
		public Builder maxRounds(int maxRounds) {
			return policy(currentPolicy().toBuilder().maxRounds(maxRounds).build());
		}

		/** 便捷方法：设置总调用次数上限。 */
		public Builder maxCalls(int maxCalls) {
			return policy(currentPolicy().toBuilder().maxCalls(maxCalls).build());
		}

		/** 便捷方法：设置单个工具的执行次数上限。 */
		public Builder toolLimit(String toolName, int maxCalls) {
			return policy(currentPolicy().toBuilder().toolLimit(toolName, maxCalls).build());
		}

		/** 便捷方法：批量设置单个工具的执行次数上限。 */
		public Builder toolLimits(Map<String, Integer> limits) {
			return policy(currentPolicy().toBuilder().toolLimits(limits).build());
		}

		/** 便捷方法：设置权限策略。 */
		public Builder permission(ToolCallPermission permission) {
			return policy(currentPolicy().toBuilder().permission(permission).build());
		}

		/** 便捷方法：设置许可方。 */
		public Builder approver(ToolCallApprover approver) {
			return policy(currentPolicy().toBuilder().approver(approver).build());
		}

		/** 是否既没有注册工具、也没有设置任何规则。 */
		public boolean isEmpty() {
			return tools.isEmpty() && policy == null;
		}

		/** 是否只注册了工具而没有自定义规则。 */
		public boolean isDefaultPolicy() {
			return policy == null;
		}

		public ToolCallManager build() {
			return new ToolCallManager(tools, policy == null ? ToolCallPolicy.defaults() : policy);
		}

		private ToolCallPolicy currentPolicy() {
			return policy == null ? ToolCallPolicy.defaults() : policy;
		}
	}
}
