package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用管理策略。
 * <p>
 * 集中描述“允许调用多少次、单个工具允许调用多少次、是否被允许、是否需要许可、超限时怎么回复”，
 * 由会话（{@code AISession#getToolCallPolicy()}）提供，随 {@code AIRequest} 传递，
 * 由 {@link ToolCallManager} 统一执行。各模型提供者与历史写入逻辑不再各自维护这些规则。
 * </p>
 * <p>本类不可变且线程安全，通过 {@link #builder()} 或 {@link #toBuilder()} 构造。</p>
 */
public class ToolCallPolicy {

	/** 默认允许的工具调用轮次上限（与旧版 {@code AIRequest.maxToolCall} 默认值一致）。 */
	public static final int DEFAULT_MAX_ROUNDS = 10;

	/** 默认超限提示文本。 */
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

	private final int maxRounds;
	private final int maxCalls;
	private final Map<String, Integer> toolCallLimits;
	private final ToolCallPermission permission;
	private final ToolCallApprover approver;
	private final String limitMessage;
	private final String toolLimitMessage;
	private final String missingToolMessage;
	private final String toolErrorMessage;
	private final String unapprovedMessage;

	protected ToolCallPolicy(Builder builder) {
		this.maxRounds = builder.maxRounds;
		this.maxCalls = builder.maxCalls;
		this.toolCallLimits = Collections.unmodifiableMap(new LinkedHashMap<>(builder.toolCallLimits));
		this.permission = builder.permission == null ? ToolCallPermission.allowAll() : builder.permission;
		this.approver = builder.approver;
		this.limitMessage = builder.limitMessage == null ? DEFAULT_LIMIT_MESSAGE : builder.limitMessage;
		this.toolLimitMessage = builder.toolLimitMessage == null ? DEFAULT_TOOL_LIMIT_MESSAGE : builder.toolLimitMessage;
		this.missingToolMessage = builder.missingToolMessage == null ? DEFAULT_MISSING_TOOL_MESSAGE : builder.missingToolMessage;
		this.toolErrorMessage = builder.toolErrorMessage == null ? DEFAULT_TOOL_ERROR_MESSAGE : builder.toolErrorMessage;
		this.unapprovedMessage = builder.unapprovedMessage == null ? DEFAULT_UNAPPROVED_MESSAGE : builder.unapprovedMessage;
	}

	/** 默认策略：最多 {@value #DEFAULT_MAX_ROUNDS} 轮，不限总次数，全部允许且自动许可。 */
	public static ToolCallPolicy defaults() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 基于当前策略创建修改器，便于只改其中一项。
	 */
	public Builder toBuilder() {
		return new Builder().maxRounds(maxRounds).maxCalls(maxCalls).toolLimits(toolCallLimits).permission(permission)
				.approver(approver).limitMessage(limitMessage).toolLimitMessage(toolLimitMessage)
				.missingToolMessage(missingToolMessage).toolErrorMessage(toolErrorMessage)
				.unapprovedMessage(unapprovedMessage);
	}

	/**
	 * 工具调用轮次上限（模型每请求一批工具调用记为一轮）。
	 * <p>小于等于 0 表示不限制轮次。</p>
	 */
	public int getMaxRounds() {
		return maxRounds;
	}

	/**
	 * 工具调用总次数上限（单个工具的成功执行记一次）。
	 * <p>小于等于 0 表示不限制总次数。</p>
	 */
	public int getMaxCalls() {
		return maxCalls;
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
	 *
	 * @param toolName 工具名称
	 * @return 上限值，未配置或小于等于 0 时返回 0（表示该工具不受单独限制）
	 */
	public int getToolCallLimit(String toolName) {
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
	 * 所有已配置的单个工具执行次数上限（不可变视图）。
	 */
	public Map<String, Integer> getToolCallLimits() {
		return toolCallLimits;
	}

	/** 权限策略，不为 null。 */
	public ToolCallPermission getPermission() {
		return permission;
	}

	/** 许可方，未配置时为 null（此时需要许可的调用按不予许可处理）。 */
	public ToolCallApprover getApprover() {
		return approver;
	}

	/** 超过调用次数限制时回传给模型的内容。 */
	public String getLimitMessage() {
		return limitMessage;
	}

	/** 单个工具超过调用次数上限时回传给模型的内容。 */
	public String getToolLimitMessage() {
		return toolLimitMessage;
	}

	/** 工具不存在或已禁用时回传给模型的内容。 */
	public String getMissingToolMessage() {
		return missingToolMessage;
	}

	/** 工具执行抛异常时回传给模型的内容。 */
	public String getToolErrorMessage() {
		return toolErrorMessage;
	}

	/** 权限要求许可但未获许可时回传给模型的内容。 */
	public String getUnapprovedMessage() {
		return unapprovedMessage;
	}

	@Override
	public String toString() {
		return "ToolCallPolicy [maxRounds=" + maxRounds + ", maxCalls=" + maxCalls + ", permission=" + permission
				+ ", approver=" + approver + "]";
	}

	/**
	 * {@link ToolCallPolicy} 构建器。
	 */
	public static class Builder {
		private int maxRounds = DEFAULT_MAX_ROUNDS;
		private int maxCalls = 0;
		private final Map<String, Integer> toolCallLimits = new LinkedHashMap<>();
		private ToolCallPermission permission = ToolCallPermission.allowAll();
		private ToolCallApprover approver = ToolCallApprover.autoApprove();
		private String limitMessage;
		private String toolLimitMessage;
		private String missingToolMessage;
		private String toolErrorMessage;
		private String unapprovedMessage;

		/** 设置轮次上限，小于等于 0 表示不限制。 */
		public Builder maxRounds(int maxRounds) {
			this.maxRounds = maxRounds;
			return this;
		}

		/** 设置总调用次数上限，小于等于 0 表示不限制。 */
		public Builder maxCalls(int maxCalls) {
			this.maxCalls = maxCalls;
			return this;
		}

		/**
		 * 设置单个工具的执行次数上限。
		 *
		 * @param toolName 工具名称
		 * @param maxCalls 该工具的次数上限，小于等于 0 表示该工具不受单独限制
		 */
		public Builder toolLimit(String toolName, int maxCalls) {
			if (toolName != null) {
				this.toolCallLimits.put(toolName, maxCalls);
			}
			return this;
		}

		/**
		 * 批量设置单个工具的执行次数上限，语义同 {@link #toolLimit(String, int)}。
		 */
		public Builder toolLimits(Map<String, Integer> limits) {
			if (limits != null) {
				this.toolCallLimits.putAll(limits);
			}
			return this;
		}

		/** 设置权限策略。 */
		public Builder permission(ToolCallPermission permission) {
			this.permission = permission;
			return this;
		}

		/** 设置许可方。 */
		public Builder approver(ToolCallApprover approver) {
			this.approver = approver;
			return this;
		}

		/** 设置超过次数限制时的提示文本。 */
		public Builder limitMessage(String limitMessage) {
			this.limitMessage = limitMessage;
			return this;
		}

		/** 设置单个工具超过次数上限时的提示文本。 */
		public Builder toolLimitMessage(String toolLimitMessage) {
			this.toolLimitMessage = toolLimitMessage;
			return this;
		}

		/** 设置工具不存在时的提示文本。 */
		public Builder missingToolMessage(String missingToolMessage) {
			this.missingToolMessage = missingToolMessage;
			return this;
		}

		/** 设置工具执行出错时的提示文本。 */
		public Builder toolErrorMessage(String toolErrorMessage) {
			this.toolErrorMessage = toolErrorMessage;
			return this;
		}

		/** 设置权限要求许可但未获许可时的提示文本。 */
		public Builder unapprovedMessage(String unapprovedMessage) {
			this.unapprovedMessage = unapprovedMessage;
			return this;
		}

		public ToolCallPolicy build() {
			return new ToolCallPolicy(this);
		}
	}
}
