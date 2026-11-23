package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 工具调用状态监听器。
 * <p>
 * 由 {@link ToolCallManager} 在工具调用排队、开始、上报进度、结束时回调。
 * 回调可能发生在工具线程上，实现方必须自己保证线程安全，并且不能让异常影响工具执行。
 * </p>
 */
public interface ToolCallListener {

	/**
	 * 收到一次工具调用状态事件。
	 *
	 * @param event 状态事件，不为 null
	 */
	void onToolCallEvent(ToolCallEvent event);
}
