package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 工具调用许可方。
 * <p>
 * 当 {@link ToolCallPermission} 返回 {@link ToolCallDecision#ask(String)} 时，
 * {@link ToolCallManager} 会调用本接口取得许可，只有返回 {@code true} 才真正执行工具。
 * </p>
 * <p>
 * 注意：调用发生在模型生成的线程上，实现若需要询问用户，请使用不依赖生成线程的等待方式
 * （例如异步回调或独立线程），不要在生成线程所占用的事件循环里同步等待，否则可能死锁。
 * </p>
 */
@FunctionalInterface
public interface ToolCallApprover {

	/** 未取得许可时回传给模型的结果文本。 */
	public static final String DEFAULT_DENY_RESULT = "用户未许可执行该工具，请改用其它方式或询问用户。";

	/**
	 * 请求许可。
	 *
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数（JSON 字符串）
	 * @param reason    需要许可的原因，可能为 null
	 * @return 取得许可返回 true，否则 false
	 */
	boolean approve(String toolName, String arguments, String reason);

	/**
	 * 请求许可，并附带面向用户的参数文本（参数名已换成工具声明的可读名称）。
	 * <p>
	 * 默认实现忽略 {@code displayArguments} 并委托给
	 * {@link #approve(String, String, String)}，已有实现无需修改；
	 * 需要在审核界面展示可读参数的实现（例如 {@link ManagedToolCallApprover}）可覆盖本方法。
	 * </p>
	 *
	 * @param toolName         工具名称
	 * @param arguments        模型给出的原始参数（JSON 字符串）
	 * @param displayArguments 面向用户的参数文本，可能为 null
	 * @param reason           需要许可的原因，可能为 null
	 * @return 取得许可返回 true，否则 false
	 */
	default boolean approve(String toolName, String arguments, String displayArguments, String reason) {
		return approve(toolName, arguments, reason);
	}

	/**
	 * 请求许可，并返回可能附带拒绝原因的结果。
	 * <p>
	 * {@link ToolCallContext} 优先调用本方法：拒绝时 {@link ToolApprovalResult#getReason()}
	 * 会作为工具结果回传给模型，模型因此能知道具体的拒绝原因。
	 * </p>
	 * <p>
	 * 默认实现委托给 {@link #approve(String, String, String, String)}，拒绝时不附带原因；
	 * 需要向模型说明拒绝原因的实现（例如 {@link ManagedToolCallApprover}、QQ 审核）应覆盖本方法。
	 * 只实现 {@link #approve(String, String, String)} 的旧代码无需修改。
	 * </p>
	 *
	 * @param toolName         工具名称
	 * @param arguments        模型给出的原始参数（JSON 字符串）
	 * @param displayArguments 面向用户的参数文本，可能为 null
	 * @param reason           需要许可的原因，可能为 null
	 * @return 许可结果，不为 null
	 */
	default ToolApprovalResult decide(String toolName, String arguments, String displayArguments, String reason) {
		return approve(toolName, arguments, displayArguments, reason) ? ToolApprovalResult.allow()
				: ToolApprovalResult.deny(null);
	}

	/**
	 * 请求许可（不区分原始参数与可读参数），语义同
	 * {@link #decide(String, String, String, String)}。
	 */
	default ToolApprovalResult decide(String toolName, String arguments, String reason) {
		return decide(toolName, arguments, null, reason);
	}

	/** 自动许可（默认策略：不打断流程）。 */
	public static ToolCallApprover autoApprove() {
		return (name, args, reason) -> true;
	}

	/** 一律不予许可（失败时关闭，用于要求许可但无人值守的场景）。 */
	public static ToolCallApprover denyAll() {
		return (name, args, reason) -> false;
	}

	/**
	 * 一律不予许可，并给出统一的拒绝原因（会回传给模型）。
	 *
	 * @param reason 拒绝原因，可为 null（等价于 {@link #denyAll()}）
	 */
	public static ToolCallApprover denyAll(String reason) {
		return new ToolCallApprover() {
			@Override
			public boolean approve(String toolName, String arguments, String requestReason) {
				return false;
			}

			@Override
			public ToolApprovalResult decide(String toolName, String arguments, String displayArguments,
					String requestReason) {
				return ToolApprovalResult.deny(reason);
			}
		};
	}
}
