package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 一次工具调用许可的结果：是否许可，以及拒绝时可附带的原因。
 * <p>
 * 相比单纯的 {@code boolean}，本类允许审核方在拒绝时说明原因；
 * 该原因会作为工具结果回传给模型，让模型知道“为什么不能做”，
 * 而不是只收到一句笼统的“未获许可”。
 * </p>
 * <p>
 * 原因为空（null/空白）时由调用方回退到 {@code ToolCallManager} 配置的未许可提示文本。
 * 本类不可变，{@link #ALLOW} 与 {@link #DENY} 是共享实例。
 * </p>
 */
public final class ToolApprovalResult {

	/** 许可本次调用。 */
	public static final ToolApprovalResult ALLOW = new ToolApprovalResult(true, null);

	/** 拒绝本次调用，不附带原因（回传给模型时使用默认未许可提示）。 */
	public static final ToolApprovalResult DENY = new ToolApprovalResult(false, null);

	private final boolean approved;
	private final String reason;

	private ToolApprovalResult(boolean approved, String reason) {
		this.approved = approved;
		// 许可时忽略原因：原因只对“拒绝”有意义
		this.reason = approved ? null : normalize(reason);
	}

	/** 许可本次调用。 */
	public static ToolApprovalResult allow() {
		return ALLOW;
	}

	/**
	 * 拒绝本次调用，并附带会回传给模型的原因。
	 *
	 * @param reason 拒绝原因，可为 null/空白（表示使用默认提示）
	 */
	public static ToolApprovalResult deny(String reason) {
		String normalized = normalize(reason);
		return normalized == null ? DENY : new ToolApprovalResult(false, normalized);
	}

	/** 按布尔值构造结果，拒绝时不附带原因。 */
	public static ToolApprovalResult of(boolean approved) {
		return approved ? ALLOW : DENY;
	}

	/**
	 * 按布尔值与拒绝原因构造结果；许可时忽略原因。
	 *
	 * @param approved 是否许可
	 * @param reason   拒绝原因，可为 null/空白
	 */
	public static ToolApprovalResult of(boolean approved, String reason) {
		return approved ? ALLOW : deny(reason);
	}

	private static String normalize(String reason) {
		if (reason == null) {
			return null;
		}
		String trimmed = reason.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** 是否许可。 */
	public boolean isApproved() {
		return approved;
	}

	/** 是否拒绝（{@link #isApproved()} 的反面）。 */
	public boolean isDenied() {
		return !approved;
	}

	/** 拒绝原因；许可或未提供原因时返回 null。 */
	public String getReason() {
		return reason;
	}

	/** 拒绝原因，为空时返回给定的兜底文本。 */
	public String getReasonOr(String fallback) {
		return reason == null ? fallback : reason;
	}

	@Override
	public String toString() {
		return approved ? "ALLOW" : reason == null ? "DENY" : "DENY(" + reason + ")";
	}
}
