package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.Locale;

/**
 * 一次工具调用对前端可见的状态。
 * <p>
 * 前端只展示 {@link #QUEUED}（排队中）与 {@link #RUNNING}（执行中/进度）；
 * 其余状态用于让前端结束展示，不在界面上显示“完成/失败”等文字。
 * </p>
 */
public enum ToolCallState {
	/** 排队中：本地模型资源锁被占用，请求已到连接器但还没真正执行。 */
	QUEUED,
	/** 执行中：工具请求已经下发。 */
	RUNNING,
	/** 已成功结束（仅用于清理前端状态）。 */
	SUCCEEDED,
	/** 已失败结束。 */
	FAILED,
	/** 已取消（用户停止、连接关闭）。 */
	CANCELLED,
	/** 未执行（超限、无权限、工具不存在等）。 */
	REJECTED;

	/** 是否为终态。 */
	public boolean isTerminal() {
		return this != QUEUED && this != RUNNING;
	}

	/** 下发给前端的小写状态名。 */
	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
