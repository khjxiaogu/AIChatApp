package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具调用权限策略。
 * <p>
 * 由会话/应用提供，{@link ToolCallManager} 在执行每个工具调用之前统一询问，
 * 用于集中判断“这次工具调用是否被允许”。
 * </p>
 * <p>
 * 实现必须线程安全且不得阻塞：需要用户确认的场景请返回 {@link ToolCallDecision#ask(String)}，
 * 由 {@link ToolCallApprover} 负责许可流程。
 * </p>
 */
@FunctionalInterface
public interface ToolCallPermission {

	/** 默认的权限拒绝原因。 */
	public static final String DEFAULT_DENY_REASON = "该工具调用未获得当前会话的调用权限。";

	/**
	 * 判断一次工具调用是否被允许。
	 *
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数（JSON 字符串）
	 * @return 判定结果，不可为 null
	 */
	ToolCallDecision check(String toolName, String arguments);

	/** 允许所有工具调用（默认策略）。 */
	public static ToolCallPermission allowAll() {
		return (name, args) -> ToolCallDecision.ALLOW;
	}

	/** 拒绝所有工具调用。 */
	public static ToolCallPermission denyAll() {
		return (name, args) -> ToolCallDecision.deny(DEFAULT_DENY_REASON);
	}

	/**
	 * 白名单：只有名单内的工具可被调用，其余拒绝。
	 *
	 * @param allowed 允许的工具名称集合
	 */
	public static ToolCallPermission allowOnly(Collection<String> allowed) {
		Set<String> names = allowed == null ? new HashSet<>() : new HashSet<>(allowed);
		return (name, args) -> names.contains(name) ? ToolCallDecision.ALLOW
				: ToolCallDecision.deny("工具 " + name + " 不在当前会话的可用范围内。");
	}

	/**
	 * 白名单（可变参数形式），语义同 {@link #allowOnly(Collection)}。
	 */
	public static ToolCallPermission allowOnly(String... allowed) {
		return allowOnly(Arrays.asList(allowed));
	}

	/**
	 * 许可名单：名单内的工具需要先取得许可，其余工具直接允许。
	 *
	 * @param needApproval 需要许可的工具名称集合
	 * @param reason       需要许可的原因
	 */
	public static ToolCallPermission requireApproval(Collection<String> needApproval, String reason) {
		Set<String> names = needApproval == null ? new HashSet<>() : new HashSet<>(needApproval);
		return (name, args) -> names.contains(name) ? ToolCallDecision.ask(reason) : ToolCallDecision.ALLOW;
	}

	/**
	 * 许可名单（可变参数形式），语义同 {@link #requireApproval(Collection, String)}。
	 */
	public static ToolCallPermission requireApproval(String reason, String... needApproval) {
		return requireApproval(Arrays.asList(needApproval), reason);
	}
}
