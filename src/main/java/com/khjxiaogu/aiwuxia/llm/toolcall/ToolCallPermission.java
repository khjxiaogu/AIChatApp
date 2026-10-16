package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具调用权限监听器。
 * <p>
 * 管理器（{@link ToolCallManager}）可以挂任意多个监听器，执行每个工具调用之前按注册顺序逐个询问。
 * 监听器对一次调用可以给出四种回答：
 * </p>
 * <ul>
 *   <li>{@link ToolCallDecision#ignore()}：<b>无操作</b>，本次调用不表态，交给后续监听器；</li>
 *   <li>{@link ToolCallDecision#allow()}：<b>允许</b>执行；</li>
 *   <li>{@link ToolCallDecision#ask(String)}：<b>询问</b>，需要先取得许可，由 {@link ToolCallApprover} 决定；</li>
 *   <li>{@link ToolCallDecision#deny(String)}：<b>拒绝</b>执行，原因作为工具结果回传给模型。</li>
 * </ul>
 * <p>
 * 多个监听器的结果按 <b>拒绝 &gt; 询问 &gt; 允许 &gt; 忽略</b> 合并（高优先级覆盖低优先级）：
 * 一旦某个监听器拒绝，立刻返回拒绝并不再询问后续监听器；同一优先级取最先表态的那个监听器。
 * 全部监听器都忽略时视为“无权限要求”，由管理器按放行处理。合并逻辑见
 * {@link #decide(Collection, String, String)}。
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
	 * 对一次工具调用表态。
	 *
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数（JSON 字符串）
	 * @return 判定结果，不可为 null；不想表态时返回 {@link ToolCallDecision#ignore()}
	 */
	ToolCallDecision check(String toolName, String arguments);

	/**
	 * 合并一组监听器对同一次调用的判定。
	 * <p>
	 * 按注册顺序询问，结果按 <b>拒绝 &gt; 询问 &gt; 允许 &gt; 忽略</b> 覆盖：
	 * </p>
	 * <ul>
	 *   <li>遇到拒绝立即返回该拒绝，<b>不再询问后续监听器</b>；</li>
	 *   <li>否则记住优先级最高的判定（同优先级取最先表态的），继续询问以确认没有拒绝；</li>
	 *   <li>监听器返回 null 视为忽略；全部忽略时返回 {@link ToolCallDecision#IGNORE}。</li>
	 * </ul>
	 *
	 * @param listeners 监听器集合，可为 null
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数
	 * @return 合并后的判定，永不为 null
	 */
	public static ToolCallDecision decide(Collection<ToolCallPermission> listeners, String toolName, String arguments) {
		ToolCallDecision result = ToolCallDecision.IGNORE;
		if (listeners == null) {
			return result;
		}
		for (ToolCallPermission listener : listeners) {
			if (listener == null) {
				continue;
			}
			ToolCallDecision decision = listener.check(toolName, arguments);
			if (decision == null || decision.isIgnored()) {
				continue;
			}
			// 拒绝优先级最高：立即短路，不再询问后续监听器
			if (decision.isDenied()) {
				return decision;
			}
			// 询问高于允许；允许高于忽略；同优先级保留最先表态的结果
			if (result.isIgnored() || (result.isAllowed() && decision.needsApproval())) {
				result = decision;
			}
		}
		return result;
	}

	/** 对任何调用都不表态（无操作）的监听器。 */
	public static ToolCallPermission ignoreAll() {
		return (name, args) -> ToolCallDecision.IGNORE;
	}

	/** 允许所有工具调用。 */
	public static ToolCallPermission allowAll() {
		return (name, args) -> ToolCallDecision.ALLOW;
	}

	/** 拒绝所有工具调用。 */
	public static ToolCallPermission denyAll() {
		return (name, args) -> ToolCallDecision.deny(DEFAULT_DENY_REASON);
	}

	/**
	 * 白名单：只有名单内的工具被允许，其余工具拒绝。
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
	 * 许可名单：名单内的工具需要先取得许可，其余工具不表态（忽略，交由其它监听器决定）。
	 *
	 * @param needApproval 需要许可的工具名称集合
	 * @param reason       需要许可的原因
	 */
	public static ToolCallPermission requireApproval(Collection<String> needApproval, String reason) {
		Set<String> names = needApproval == null ? new HashSet<>() : new HashSet<>(needApproval);
		return (name, args) -> names.contains(name) ? ToolCallDecision.ask(reason) : ToolCallDecision.IGNORE;
	}

	/**
	 * 许可名单（可变参数形式），语义同 {@link #requireApproval(Collection, String)}。
	 */
	public static ToolCallPermission requireApproval(String reason, String... needApproval) {
		return requireApproval(Arrays.asList(needApproval), reason);
	}
}
