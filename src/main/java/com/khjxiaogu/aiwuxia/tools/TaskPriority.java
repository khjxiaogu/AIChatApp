package com.khjxiaogu.aiwuxia.tools;

/**
 * 本地模型任务在资源锁上的调度优先级。
 * <p>
 * 只影响“资源忙时谁先拿到资源”，不会抢占已经在跑的任务。等级按数值从小到大调度：
 * </p>
 * <ul>
 *   <li>{@link #HIGH}：简单快速的任务，例如 SDXL / Qwen 生图、本机 TTS；</li>
 *   <li>{@link #NORMAL}：普通任务，例如 SVS 歌声合成；</li>
 *   <li>{@link #LOW}：重型长任务，例如 H3 视频生成。</li>
 * </ul>
 * <p>
 * 任何等级的任务在等待期间被插队达到上限（默认 5 次）后都会转为优先调度，因此低优先级任务
 * 不会被饿死。没有显式指定优先级的旧调用按 {@link #NORMAL} 处理，保持先来先得。
 * </p>
 */
public enum TaskPriority {

	/** 快速任务：生图、语音等。 */
	HIGH(0),
	/** 普通任务：歌声合成等。 */
	NORMAL(1),
	/** 重型长任务：视频生成。 */
	LOW(2);

	private final int rank;

	TaskPriority(int rank) {
		this.rank = rank;
	}

	/**
	 * 调度等级，数值越小越先调度。
	 *
	 * @return 等级数值
	 */
	public int rank() {
		return rank;
	}
}
