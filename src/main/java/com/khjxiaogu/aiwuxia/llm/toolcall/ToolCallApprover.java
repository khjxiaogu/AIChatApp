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

	/** 自动许可（默认策略：不打断流程）。 */
	public static ToolCallApprover autoApprove() {
		return (name, args, reason) -> true;
	}

	/** 一律不予许可（失败时关闭，用于要求许可但无人值守的场景）。 */
	public static ToolCallApprover denyAll() {
		return (name, args, reason) -> false;
	}
}
