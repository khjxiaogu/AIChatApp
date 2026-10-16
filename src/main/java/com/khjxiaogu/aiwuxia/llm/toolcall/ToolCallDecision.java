package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 工具调用权限判定结果。
 * <p>
 * {@link ToolCallPermission} 监听器对每一次工具调用给出一个判定，共四种：
 * </p>
 * <ul>
 *   <li>{@link Kind#IGNORE}：不表态（无操作），不影响其它监听器的结果；</li>
 *   <li>{@link Kind#ALLOW}：允许直接执行；</li>
 *   <li>{@link Kind#ASK}：需要先取得用户/上层许可，由 {@link ToolCallApprover} 决定；</li>
 *   <li>{@link Kind#DENY}：拒绝执行，{@link #getReason()} 会作为工具结果回传给模型。</li>
 * </ul>
 * <p>
 * 多个监听器同时表态时按 <b>拒绝 &gt; 询问 &gt; 允许 &gt; 忽略</b> 合并，见
 * {@link ToolCallPermission#decide(java.util.Collection, String, String)}。
 * 本类不可变。
 * </p>
 */
public final class ToolCallDecision {

	/**
	 * 判定类型。
	 */
	public static enum Kind {
		/** 忽略（不表态） */
		IGNORE,
		/** 允许执行 */
		ALLOW,
		/** 需要许可后再执行 */
		ASK,
		/** 拒绝执行 */
		DENY
	}

	/** 忽略（不表态）的共享实例。 */
	public static final ToolCallDecision IGNORE = new ToolCallDecision(Kind.IGNORE, null);

	/** 允许执行的共享实例。 */
	public static final ToolCallDecision ALLOW = new ToolCallDecision(Kind.ALLOW, null);

	private final Kind kind;
	private final String reason;

	private ToolCallDecision(Kind kind, String reason) {
		this.kind = kind;
		this.reason = reason;
	}

	/**
	 * 忽略（不表态）：本监听器对这次调用没有意见，由其它监听器决定。
	 */
	public static ToolCallDecision ignore() {
		return IGNORE;
	}

	/** 允许执行。 */
	public static ToolCallDecision allow() {
		return ALLOW;
	}

	/**
	 * 需要先取得许可。
	 *
	 * @param reason 需要许可的原因，会展示给许可方
	 */
	public static ToolCallDecision ask(String reason) {
		return new ToolCallDecision(Kind.ASK, reason);
	}

	/**
	 * 拒绝执行。
	 *
	 * @param reason 拒绝原因，会作为工具结果回传给模型
	 */
	public static ToolCallDecision deny(String reason) {
		return new ToolCallDecision(Kind.DENY, reason);
	}

	public Kind getKind() {
		return kind;
	}

	/** 是否可以直接执行。 */
	public boolean isAllowed() {
		return kind == Kind.ALLOW;
	}

	/** 是否需要先取得许可。 */
	public boolean needsApproval() {
		return kind == Kind.ASK;
	}

	/** 是否拒绝执行。 */
	public boolean isDenied() {
		return kind == Kind.DENY;
	}

	/** 是否不表态（无操作）。 */
	public boolean isIgnored() {
		return kind == Kind.IGNORE;
	}

	/** 拒绝或需要许可的原因，允许/忽略时为 null。 */
	public String getReason() {
		return reason;
	}

	/** 原因文本，为空时返回给定的兜底文本。 */
	public String getReasonOr(String fallback) {
		return reason == null || reason.isEmpty() ? fallback : reason;
	}

	@Override
	public String toString() {
		return reason == null ? kind.name() : kind.name() + "(" + reason + ")";
	}
}
