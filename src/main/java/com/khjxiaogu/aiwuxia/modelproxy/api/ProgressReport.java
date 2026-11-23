package com.khjxiaogu.aiwuxia.modelproxy.api;

/**
 * 本地模型执行进度，独立的值对象。
 */
public final class ProgressReport {

	/** 进度状态。 */
	public enum Status {
		/** 正在等待本地资源（排队）。 */
		QUEUED,
		/** 已把请求下发给本地软件（执行中）。 */
		RUNNING
	}

	/** 状态。 */
	public final Status status;
	/** 进度比例，0~1；小于 0 表示没有真实进度。 */
	public final double ratio;
	/** 预计总耗时（毫秒），0 表示未知。 */
	public final long etaMs;
	/** 说明文本，可为空。 */
	public final String message;

	public ProgressReport(Status status, double ratio, long etaMs, String message) {
		this.status = status;
		this.ratio = ratio;
		this.etaMs = etaMs;
		this.message = message == null ? "" : message;
	}

	/**
	 * 排队状态。
	 *
	 * @param message 说明
	 * @return 进度对象
	 */
	public static ProgressReport queued(String message) {
		return new ProgressReport(Status.QUEUED, -1d, 0L, message);
	}

	/**
	 * 执行中状态。
	 *
	 * @param message 说明
	 * @param etaMs   预计总耗时（毫秒）
	 * @return 进度对象
	 */
	public static ProgressReport running(String message, long etaMs) {
		return new ProgressReport(Status.RUNNING, -1d, etaMs, message);
	}

	/**
	 * 执行中状态（带进度比例）。
	 *
	 * @param message 说明
	 * @param ratio   进度比例 0~1
	 * @param etaMs   预计总耗时（毫秒）
	 * @return 进度对象
	 */
	public static ProgressReport running(String message, double ratio, long etaMs) {
		return new ProgressReport(Status.RUNNING, ratio, etaMs, message);
	}
}
