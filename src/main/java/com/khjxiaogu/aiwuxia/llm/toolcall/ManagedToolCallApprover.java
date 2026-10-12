package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 基于 {@link ToolApprovalManager} 的阻塞式许可方实现。
 * <p>
 * 作为 {@link ToolCallApprover} 交给 {@code ToolCallPolicy} 后：每当某次工具调用需要许可，
 * 本类会向用户/上层发起异步请求（由 {@link ToolApprovalRequester} 决定如何询问），
 * 并在当前线程上等待答复；同一会话（userId）的请求被串行化，超时或等待被中断时按拒绝处理。
 * </p>
 * <p>
 * 使用前提：答复必须来自另一个线程（例如 WebSocket 收到用户消息的处理线程），
 * 且该线程不能阻塞在发起工具调用的线程所占用的事件循环上，否则会死锁。
 * </p>
 */
public class ManagedToolCallApprover implements ToolCallApprover {

	private final ToolApprovalManager manager;
	private final String userId;

	public ManagedToolCallApprover(ToolApprovalManager manager, String userId) {
		this.manager = manager;
		this.userId = userId;
	}

	@Override
	public boolean approve(String toolName, String arguments, String reason) {
		try {
			return manager.request(userId, toolName, arguments, reason);
		} catch (InterruptedException e) {
			// 等待被中断：恢复中断标记并按拒绝处理
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public ToolApprovalManager getManager() {
		return manager;
	}

	public String getUserId() {
		return userId;
	}

	@Override
	public String toString() {
		return "ManagedToolCallApprover [userId=" + userId + "]";
	}
}
