package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;

/**
 * 工具调用管理器（可变）。
 * <p>
 * 同时承担“工具注册表”（哪些工具可被调用）与“调用规则”
 * （轮次上限、总次数上限、单工具次数上限、不限次工具、权限监听器、许可、各类超限提示文本）
 * 两个职责，是 {@code AIRequest} 携带的工具调用配置载体。
 * 原先独立的 {@code ToolCallPolicy} 已合并进本类，规则直接在本类上读写。
 * </p>
 * <p>
 * 权限采用监听器模式：可挂任意多个 {@link ToolCallPermission}，按注册顺序询问，
 * 结果按“拒绝 &gt; 询问 &gt; 允许 &gt; 忽略”合并，遇拒绝短路，见
 * {@link #checkPermission(String, String)}。
 * </p>
 * <p>
 * 本类<b>可变</b>：工具表与规则都可以随时增删改，修改对之后所有使用该管理器的生成立即生效，
 * 因此不需要 builder/toBuilder 重建对象，也不需要缓存失效重建。可变的代价是线程安全由调用方保证：
 * 注册工具、调整规则应在生成开始前完成；生成过程中只读取规则，不要在生成线程之外并发修改。
 * </p>
 * <p>
 * 单次模型调用过程中的可变状态（已调用几次、是否提示过超限）不放在本类里，
 * 而是由 {@link ToolCallContext} 承载：
 * </p>
 * <pre>{@code
 * ToolCallManager manager = new ToolCallManager()
 *     .registerTool(echoTool)
 *     .registerTools(otherTools)
 *     .setMaxRounds(10)
 *     .setToolCallLimit("sdxl_gen_image", 3)
 *     .addUnlimitedTool("web_view")
 *     .addPermissionListener(ToolCallPermission.requireApproval("高危工具需确认", "shell"));
 * }</pre>
 *
 * @see ToolCallContext
 */
public class ToolCallManager {

	/** 默认允许的工具调用轮次上限（与旧版 {@code AIRequest.maxToolCall} 默认值一致）。 */
	public static final int DEFAULT_MAX_ROUNDS = 10;

	/** 默认轮次超限提示文本。 */
	public static final String DEFAULT_LIMIT_MESSAGE = "已达最大工具调用轮次，请暂停工作并明确用户指示。";

	/** 默认单个工具超限提示文本。 */
	public static final String DEFAULT_TOOL_LIMIT_MESSAGE = "该工具调用次数已达上限，请暂停使用该工具或明确用户指示。";

	/** 默认拒绝提示文本。 */
	public static final String DEFAULT_DENY_MESSAGE = ToolCallPermission.DEFAULT_DENY_REASON;

	/** 默认未许可提示文本。 */
	public static final String DEFAULT_UNAPPROVED_MESSAGE = ToolCallApprover.DEFAULT_DENY_RESULT;

	/** 默认工具不存在提示文本。 */
	public static final String DEFAULT_MISSING_TOOL_MESSAGE = "tool不存在或已禁用。";

	/** 默认工具内部错误提示文本。 */
	public static final String DEFAULT_TOOL_ERROR_MESSAGE = "tool发生内部错误。";

	/** 可用工具表（名称 → 工具），随注册与注销直接变化。 */
	private final Map<String, ToolData> tools = new LinkedHashMap<>();

	/** 工具调用轮次上限（模型每请求一批工具调用记为一轮），小于等于 0 表示不限制。 */
	private int maxRounds = DEFAULT_MAX_ROUNDS;
	/** 工具调用总次数上限，小于等于 0 表示不限制。 */
	private int maxCalls = 0;
	/** 各工具的执行次数上限，未列出或不大于 0 表示该工具不受单独限制。 */
	private final Map<String, Integer> toolCallLimits = new LinkedHashMap<>();
	/** 不受任何调用次数限制的工具名称。 */
	private final Set<String> unlimitedTools = new LinkedHashSet<>();
	/** 权限监听器：按注册顺序逐个询问，结果按“拒绝 > 询问 > 允许 > 忽略”合并。 */
	private final List<ToolCallPermission> permissionListeners = new ArrayList<>();
	/** 许可方，可为 null（此时需要许可的调用按不予许可处理）。 */
	private ToolCallApprover approver = ToolCallApprover.autoApprove();
	private String limitMessage = DEFAULT_LIMIT_MESSAGE;
	private String toolLimitMessage = DEFAULT_TOOL_LIMIT_MESSAGE;
	private String missingToolMessage = DEFAULT_MISSING_TOOL_MESSAGE;
	private String toolErrorMessage = DEFAULT_TOOL_ERROR_MESSAGE;
	private String unapprovedMessage = DEFAULT_UNAPPROVED_MESSAGE;

	/** 创建一个不含任何工具、使用默认规则的管理器。 */
	public ToolCallManager() {
	}

	// ==================== 工具注册表 ====================

	/**
	 * 注册一个工具（同名覆盖）。
	 *
	 * @param tool 工具定义
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTool(ToolData tool) {
		if (tool != null && tool.name != null) {
			tools.put(tool.name, tool);
		}
		return this;
	}

	/**
	 * 批量注册工具（同名覆盖）。
	 *
	 * @param tools 工具定义集合，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(Collection<ToolData> tools) {
		if (tools != null) {
			for (ToolData tool : tools) {
				registerTool(tool);
			}
		}
		return this;
	}

	/**
	 * 批量注册工具（名称 → 工具，同名覆盖）。
	 *
	 * @param tools 工具表，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(Map<String, ToolData> tools) {
		if (tools != null) {
			this.tools.putAll(tools);
		}
		return this;
	}

	/**
	 * 注销一个工具。
	 *
	 * @param toolName 工具名称
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager unregisterTool(String toolName) {
		if (toolName != null) {
			tools.remove(toolName);
		}
		return this;
	}

	/**
	 * 清空工具表。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager clearTools() {
		tools.clear();
		return this;
	}

	/**
	 * 可用工具表（只读视图，随注册与注销实时变化）。
	 */
	public Map<String, ToolData> getTools() {
		return Collections.unmodifiableMap(tools);
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

	// ==================== 调用规则：轮次与次数 ====================

	/**
	 * 工具调用轮次上限（模型每请求一批工具调用记为一轮）。
	 * <p>小于等于 0 表示不限制轮次。</p>
	 */
	public int getMaxRounds() {
		return maxRounds;
	}

	/**
	 * 设置轮次上限，小于等于 0 表示不限制。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setMaxRounds(int maxRounds) {
		this.maxRounds = maxRounds;
		return this;
	}

	/**
	 * 工具调用总次数上限（单个工具的成功执行记一次）。
	 * <p>小于等于 0 表示不限制总次数；声明为不限次的工具不占用该额度。</p>
	 */
	public int getMaxCalls() {
		return maxCalls;
	}

	/**
	 * 设置总次数上限，小于等于 0 表示不限制。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setMaxCalls(int maxCalls) {
		this.maxCalls = maxCalls;
		return this;
	}

	/** 轮次是否受限。 */
	public boolean hasRoundLimit() {
		return maxRounds > 0;
	}

	/** 总次数是否受限。 */
	public boolean hasCallLimit() {
		return maxCalls > 0;
	}

	/**
	 * 单个工具的执行次数上限。
	 * <p>声明为不限次的工具始终返回 0（不受单独限制）。</p>
	 *
	 * @param toolName 工具名称
	 * @return 上限值，未配置、小于等于 0 或该工具不限次时返回 0（表示该工具不受单独限制）
	 */
	public int getToolCallLimit(String toolName) {
		if (isUnlimited(toolName)) {
			return 0;
		}
		Integer limit = toolCallLimits.get(toolName);
		return limit == null || limit <= 0 ? 0 : limit;
	}

	/**
	 * 单个工具是否配置了执行次数上限。
	 *
	 * @param toolName 工具名称
	 */
	public boolean hasToolCallLimit(String toolName) {
		return getToolCallLimit(toolName) > 0;
	}

	/**
	 * 设置单个工具的执行次数上限。
	 *
	 * @param toolName 工具名称
	 * @param maxCalls 该工具的次数上限，小于等于 0 表示该工具不受单独限制
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolCallLimit(String toolName, int maxCalls) {
		if (toolName != null) {
			toolCallLimits.put(toolName, maxCalls);
		}
		return this;
	}

	/**
	 * 批量设置单个工具的执行次数上限，语义同 {@link #setToolCallLimit(String, int)}。
	 *
	 * @param limits 工具名称到次数上限的映射，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolCallLimits(Map<String, Integer> limits) {
		toolCallLimits.clear();
		if (limits != null) {
			toolCallLimits.putAll(limits);
		}
		return this;
	}

	/**
	 * 清空所有单工具次数上限。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager clearToolCallLimits() {
		toolCallLimits.clear();
		return this;
	}

	/**
	 * 所有已配置的单个工具执行次数上限（只读视图）。
	 */
	public Map<String, Integer> getToolCallLimits() {
		return Collections.unmodifiableMap(toolCallLimits);
	}

	/**
	 * 不受任何调用次数限制的工具集合（只读视图）。
	 * <p>
	 * 集合中的工具：不校验总次数与单工具次数上限，也不计入轮次、不受轮次上限拦截，
	 * 即“调用多少次都可以”。默认空集合，即所有工具都受配置的上限约束。
	 * </p>
	 */
	public Set<String> getUnlimitedTools() {
		return Collections.unmodifiableSet(unlimitedTools);
	}

	/**
	 * 指定工具是否不受任何调用次数限制。
	 * <p>
	 * 不受限制的工具既不消耗轮次计数，也不会因轮次或次数超限而被拒绝执行；
	 * 其执行不占用总次数上限的额度（但会计入单工具调用次数用于统计）。
	 * </p>
	 *
	 * @param toolName 工具名称
	 * @return 该工具是否不受次数与轮次限制
	 */
	public boolean isUnlimited(String toolName) {
		return toolName != null && unlimitedTools.contains(toolName);
	}

	/**
	 * 声明单个工具不受任何调用次数限制（轮次、总次数、单工具次数都不约束它）。
	 * <p>
	 * 该工具既不消耗轮次计数，也不会因轮次或次数超限被拒绝执行，
	 * 其执行不占用总次数上限的额度。与 {@link #setToolCallLimit(String, int)} 冲突时以本声明为准。
	 * </p>
	 *
	 * @param toolName 工具名称
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager addUnlimitedTool(String toolName) {
		if (toolName != null) {
			unlimitedTools.add(toolName);
		}
		return this;
	}

	/**
	 * 批量声明不受任何调用次数限制的工具，语义同 {@link #addUnlimitedTool(String)}。
	 * <p>本方法会先清空已有声明再写入，便于会话按钩子整批覆盖。</p>
	 *
	 * @param toolNames 工具名称集合，可为 null（等价于清空）
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setUnlimitedTools(Collection<String> toolNames) {
		unlimitedTools.clear();
		if (toolNames != null) {
			for (String toolName : toolNames) {
				addUnlimitedTool(toolName);
			}
		}
		return this;
	}

	/**
	 * 取消某个工具的不限次声明。
	 *
	 * @param toolName 工具名称
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager removeUnlimitedTool(String toolName) {
		if (toolName != null) {
			unlimitedTools.remove(toolName);
		}
		return this;
	}

	// ==================== 调用规则：权限、许可与提示文本 ====================

	/**
	 * 已注册的权限监听器（只读视图，按注册顺序）。
	 */
	public List<ToolCallPermission> getPermissionListeners() {
		return Collections.unmodifiableList(permissionListeners);
	}

	/**
	 * 追加一个权限监听器。
	 * <p>
	 * 监听器按注册顺序被询问，可返回忽略（无操作）/允许/询问/拒绝，
	 * 结果按“拒绝 &gt; 询问 &gt; 允许 &gt; 忽略”合并，遇拒绝立即短路。
	 * </p>
	 *
	 * @param listener 监听器，null 忽略
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager addPermissionListener(ToolCallPermission listener) {
		if (listener != null) {
			permissionListeners.add(listener);
		}
		return this;
	}

	/**
	 * 移除一个权限监听器（按等值比较，重复注册时只移除最先出现的一个）。
	 *
	 * @param listener 监听器
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager removePermissionListener(ToolCallPermission listener) {
		permissionListeners.remove(listener);
		return this;
	}

	/**
	 * 清空全部权限监听器（此后所有调用都无权限要求，直接放行）。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager clearPermissionListeners() {
		permissionListeners.clear();
		return this;
	}

	/**
	 * 整批替换权限监听器（先清空再按集合顺序写入），便于按钩子整批覆盖。
	 *
	 * @param listeners 监听器集合，可为 null（等价于清空）
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setPermissionListeners(Collection<ToolCallPermission> listeners) {
		permissionListeners.clear();
		if (listeners != null) {
			for (ToolCallPermission listener : listeners) {
				addPermissionListener(listener);
			}
		}
		return this;
	}

	/**
	 * 询问全部权限监听器并合并出这次工具调用的权限判定。
	 * <p>
	 * 合并规则为“拒绝 &gt; 询问 &gt; 允许 &gt; 忽略”，遇到拒绝立即返回且不再询问后续监听器；
	 * 全部监听器都忽略时返回 {@link ToolCallDecision#ALLOW}（无权限要求即放行）。
	 * </p>
	 *
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数
	 * @return 权限判定，永不为 null
	 */
	public ToolCallDecision checkPermission(String toolName, String arguments) {
		ToolCallDecision decision = ToolCallPermission.decide(permissionListeners, toolName, arguments);
		return decision.isIgnored() ? ToolCallDecision.ALLOW : decision;
	}

	/** 许可方，未配置时为 null（此时需要许可的调用按不予许可处理）。 */
	public ToolCallApprover getApprover() {
		return approver;
	}

	/**
	 * 设置许可方，可为 null（表示需要许可的调用一律不予许可）。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setApprover(ToolCallApprover approver) {
		this.approver = approver;
		return this;
	}

	/** 超过调用次数限制时回传给模型的内容。 */
	public String getLimitMessage() {
		return limitMessage;
	}

	/**
	 * 设置超过次数限制时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setLimitMessage(String limitMessage) {
		this.limitMessage = limitMessage == null ? DEFAULT_LIMIT_MESSAGE : limitMessage;
		return this;
	}

	/** 单个工具超过调用次数上限时回传给模型的内容。 */
	public String getToolLimitMessage() {
		return toolLimitMessage;
	}

	/**
	 * 设置单个工具超过次数上限时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolLimitMessage(String toolLimitMessage) {
		this.toolLimitMessage = toolLimitMessage == null ? DEFAULT_TOOL_LIMIT_MESSAGE : toolLimitMessage;
		return this;
	}

	/** 工具不存在或已禁用时回传给模型的内容。 */
	public String getMissingToolMessage() {
		return missingToolMessage;
	}

	/**
	 * 设置工具不存在时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setMissingToolMessage(String missingToolMessage) {
		this.missingToolMessage = missingToolMessage == null ? DEFAULT_MISSING_TOOL_MESSAGE : missingToolMessage;
		return this;
	}

	/** 工具执行抛异常时回传给模型的内容。 */
	public String getToolErrorMessage() {
		return toolErrorMessage;
	}

	/**
	 * 设置工具执行出错时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolErrorMessage(String toolErrorMessage) {
		this.toolErrorMessage = toolErrorMessage == null ? DEFAULT_TOOL_ERROR_MESSAGE : toolErrorMessage;
		return this;
	}

	/** 权限要求许可但未获许可时回传给模型的内容。 */
	public String getUnapprovedMessage() {
		return unapprovedMessage;
	}

	/**
	 * 设置权限要求许可但未获许可时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setUnapprovedMessage(String unapprovedMessage) {
		this.unapprovedMessage = unapprovedMessage == null ? DEFAULT_UNAPPROVED_MESSAGE : unapprovedMessage;
		return this;
	}

	@Override
	public String toString() {
		return "ToolCallManager [tools=" + tools.size() + ", maxRounds=" + maxRounds + ", maxCalls=" + maxCalls
				+ ", unlimitedTools=" + unlimitedTools + ", permissionListeners=" + permissionListeners.size()
				+ ", approver=" + approver + "]";
	}
}
