package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一次工具调用许可请求的记录对象，包含请求信息与结果回调。
 * <p>
 * 由 {@link ToolApprovalManager} 创建并交给 {@link ToolApprovalRequester}；
 * 请求方（用户界面 / 上层业务）在得到用户答复后调用 {@link #setResult(boolean)}
 * （或 {@link #approve()}/{@link #deny()}）解除等待线程的阻塞。
 * </p>
 * <p>
 * 与常见实现不同的是：重复设置结果、超时后再收到答复等竞态不会抛异常，
 * 而是以返回值标明“本次答复是否被接受”，避免把已过期的用户点击变成服务端异常。
 * </p>
 */
public class ToolApprovalRequest {

	private final String id = UUID.randomUUID().toString();
	private final String userId;
	private final String toolName;
	private final String arguments;
	private final String displayArguments;
	private final String reason;
	private final long createTime = System.currentTimeMillis();
	private final CompletableFuture<Boolean> future = new CompletableFuture<>();

	/**
	 * @param userId    发起请求的会话/用户标识，用于按用户串行化
	 * @param toolName  待执行的工具名称
	 * @param arguments 模型给出的原始参数（JSON 字符串）
	 * @param reason    需要许可的原因，可为 null
	 */
	public ToolApprovalRequest(String userId, String toolName, String arguments, String reason) {
		this(userId, toolName, arguments, null, reason);
	}

	/**
	 * @param userId           发起请求的会话/用户标识，用于按用户串行化
	 * @param toolName         待执行的工具名称
	 * @param arguments        模型给出的原始参数（JSON 字符串）
	 * @param displayArguments 面向用户的参数文本（参数名已换成可读名称），可为 null
	 * @param reason           需要许可的原因，可为 null
	 */
	public ToolApprovalRequest(String userId, String toolName, String arguments, String displayArguments,
			String reason) {
		this.userId = userId;
		this.toolName = toolName;
		this.arguments = arguments;
		this.displayArguments = displayArguments;
		this.reason = reason;
	}

	/** 本次请求的唯一标识，答复时按它回填结果。 */
	public String getId() {
		return id;
	}

	public String getUserId() {
		return userId;
	}

	public String getToolName() {
		return toolName;
	}

	public String getArguments() {
		return arguments;
	}

	/**
	 * 面向用户的参数文本：参数名已换成工具声明的可读名称，每行一个参数。
	 * <p>工具未声明可读名称或没有可读参数时为 null，调用方应回退到 {@link #getArguments()}。</p>
	 *
	 * @return 可读参数文本，可能为 null
	 */
	public String getDisplayArguments() {
		return displayArguments;
	}

	/** 需要许可的原因，可能为 null。 */
	public String getReason() {
		return reason;
	}

	/** 请求创建时间（毫秒时间戳）。 */
	public long getCreateTime() {
		return createTime;
	}

	/**
	 * 设置许可结果，解除等待线程的阻塞。
	 *
	 * @param result true 许可，false 拒绝
	 * @return 本次调用是否被接受；若结果已设置过（超时、断线取消或重复答复）返回 false，
	 *         且不会改变已有结果
	 */
	public boolean setResult(boolean result) {
		return future.complete(result);
	}

	/** 便捷方法：许可本次调用。 */
	public boolean approve() {
		return setResult(true);
	}

	/** 便捷方法：拒绝本次调用。 */
	public boolean deny() {
		return setResult(false);
	}

	/** 是否已有结果。 */
	public boolean isCompleted() {
		return future.isDone();
	}

	/**
	 * 注册结果回调（供管理器内部使用）：结果到达时触发，若已结束则立即触发。
	 *
	 * @param action 结果消费者，第一个参数为许可结果
	 */
	void whenComplete(java.util.function.BiConsumer<Boolean, Throwable> action) {
		future.whenComplete(action);
	}

	@Override
	public String toString() {
		return "ToolApprovalRequest [id=" + id + ", userId=" + userId + ", toolName=" + toolName + ", completed="
				+ isCompleted() + "]";
	}
}
