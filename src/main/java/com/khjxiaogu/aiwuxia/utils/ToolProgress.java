package com.khjxiaogu.aiwuxia.utils;

/**
 * 一次本地模型任务的进度。
 * <p>
 * 由本地模型连接器上报、经 {@link com.khjxiaogu.aiwuxia.modelproxy.LocalModelHandshaker} 转交给
 * 正在执行该工具调用的线程。只用于展示，不写入历史。
 * </p>
 */
public final class ToolProgress {

	/** 排队中：正在等待本地模型资源锁。 */
	public static final String STATUS_QUEUED = "queued";
	/** 执行中：请求已经下发给本地软件。 */
	public static final String STATUS_RUNNING = "running";

	private final String status;
	private final Double progress;
	private final Long etaMs;
	private final String message;

	/**
	 * @param status   状态（{@link #STATUS_QUEUED} / {@link #STATUS_RUNNING}）
	 * @param progress 进度比例，0~1，可为 null
	 * @param etaMs    预计总耗时（毫秒），可为 null
	 * @param message  说明，可为 null
	 */
	public ToolProgress(String status, Double progress, Long etaMs, String message) {
		this.status = status == null || status.isEmpty() ? STATUS_RUNNING : status;
		this.progress = progress == null ? null : Double.valueOf(Math.max(0d, Math.min(1d, progress)));
		this.etaMs = etaMs == null || etaMs <= 0 ? null : etaMs;
		this.message = message == null ? "" : message;
	}

	/** 状态字符串。 */
	public String getStatus() {
		return status;
	}

	/** 进度比例，可为 null。 */
	public Double getProgress() {
		return progress;
	}

	/** 预计总耗时（毫秒），可为 null。 */
	public Long getEtaMs() {
		return etaMs;
	}

	/** 说明文本，没有时为空串。 */
	public String getMessage() {
		return message;
	}

	@Override
	public String toString() {
		return "ToolProgress [status=" + status + ", progress=" + progress + ", etaMs=" + etaMs + ", message="
				+ message + "]";
	}
}
