package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 异步工具调用许可请求接口（由外部的用户界面 / 上层业务实现）。
 * <p>
 * 实现方在适当时候（用户点击允许 / 拒绝、连接断开等）调用
 * {@link ToolApprovalRequest#setResult(boolean)}；<b>不得在方法内长时间阻塞等待异步答复</b>，
 * 否则会占住发起工具调用的线程。若实现是同步弹窗（答复在本方法返回前就已回填，
 * 例如桌面端 {@code JOptionPane} 确认框），则天然满足该要求。
 * </p>
 */
@FunctionalInterface
public interface ToolApprovalRequester {

	/**
	 * 异步请求一次工具调用许可。
	 *
	 * @param request 许可请求记录，实现方需在其上回填结果
	 */
	void requestApproval(ToolApprovalRequest request);
}
