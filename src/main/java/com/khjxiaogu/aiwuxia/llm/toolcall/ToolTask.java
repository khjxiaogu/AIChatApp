package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.concurrent.CompletableFuture;

import com.khjxiaogu.aiwuxia.llm.ToolResult;

/**
 * 一次异步工具调用的任务记录。
 * <p>
 * 工具显式声明为异步时，{@link ToolCallManager} 会为这次调用创建本对象并立即向模型返回
 * “任务已创建”，任务完成后由管理器通知会话。
 * </p>
 */
public final class ToolTask {

	/** 任务状态。 */
	public enum State {
		RUNNING, SUCCEEDED, FAILED, CANCELLED
	}

	private final String id;
	private final String toolName;
	private final String toolCallId;
	private final ToolCallDisplay display;
	private final long createdAt;
	private final CompletableFuture<ToolResult> future;
	private volatile State state = State.RUNNING;
	private volatile ToolResult result;
	private volatile Throwable error;
	private volatile long completedAt;

	ToolTask(String id, String toolName, String toolCallId, ToolCallDisplay display,
			CompletableFuture<ToolResult> future) {
		this.id = id;
		this.toolName = toolName;
		this.toolCallId = toolCallId;
		this.display = display == null ? ToolCallDisplay.EMPTY : display;
		this.future = future;
		this.createdAt = System.currentTimeMillis();
	}

	/** 任务 ID，例如 {@code task-3}。 */
	public String getId() {
		return id;
	}

	/** 工具名称。 */
	public String getToolName() {
		return toolName;
	}

	/** 模型原始 tool_call id；外部通知任务（定时、角色呼叫）为 null。 */
	public String getToolCallId() {
		return toolCallId;
	}

	/** 工具调用的用户友好显示。 */
	public ToolCallDisplay getDisplay() {
		return display;
	}

	/** 创建时间（毫秒）。 */
	public long getCreatedAt() {
		return createdAt;
	}

	/** 完成时间（毫秒），未完成时为 0。 */
	public long getCompletedAt() {
		return completedAt;
	}

	/** 当前状态。 */
	public State getState() {
		return state;
	}

	/** 成功时的结果，其他状态为 null。 */
	public ToolResult getResult() {
		return result;
	}

	/** 失败时的异常，其他状态为 null。 */
	public Throwable getError() {
		return error;
	}

	/** 底层 future。 */
	public CompletableFuture<ToolResult> getFuture() {
		return future;
	}

	/** 是否仍在执行。 */
	public boolean isRunning() {
		return state == State.RUNNING;
	}

	/** 是否成功完成。 */
	public boolean isSucceeded() {
		return state == State.SUCCEEDED;
	}

	/** 是否失败。 */
	public boolean isFailed() {
		return state == State.FAILED;
	}

	/** 是否已取消。 */
	public boolean isCancelled() {
		return state == State.CANCELLED;
	}

	synchronized void complete(ToolResult result) {
		if (state != State.RUNNING) {
			return;
		}
		this.result = result == null ? ToolResult.text("") : result;
		this.state = State.SUCCEEDED;
		this.completedAt = System.currentTimeMillis();
	}

	synchronized void fail(Throwable error) {
		if (state != State.RUNNING) {
			return;
		}
		this.error = error;
		this.state = State.FAILED;
		this.completedAt = System.currentTimeMillis();
	}

	synchronized void cancel() {
		if (state != State.RUNNING) {
			return;
		}
		this.state = State.CANCELLED;
		this.completedAt = System.currentTimeMillis();
	}

	@Override
	public String toString() {
		return "ToolTask [id=" + id + ", tool=" + toolName + ", state=" + state + "]";
	}
}
