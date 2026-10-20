package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 异步工具任务监听器。
 * <p>
 * 由会话实现并注册到 {@link ToolCallManager}。任务完成回调可能发生在任意线程，
 * 实现方应只做轻量状态更新，把耗时的消息发送、续跑等操作调度到会话自己的命令线程。
 * </p>
 */
public interface ToolTaskListener {

	/** 任务已创建。默认空实现。 */
	default void onToolTaskCreated(ToolTask task) {
	}

	/** 任务成功完成。 */
	default void onToolTaskCompleted(ToolTask task) {
	}

	/** 任务失败。默认转调 {@link #onToolTaskCompleted(ToolTask)}，需要区分的实现可覆盖。 */
	default void onToolTaskFailed(ToolTask task) {
		onToolTaskCompleted(task);
	}
}
