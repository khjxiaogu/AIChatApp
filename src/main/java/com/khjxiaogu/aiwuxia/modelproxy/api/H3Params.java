package com.khjxiaogu.aiwuxia.modelproxy.api;

/**
 * MiniMax H3（本地 ComfyUI）调用的参数，独立的值对象。
 * <p>
 * 保留旧协议里的全部字段名，编解码器逐字段映射；视频/帧等媒体先用附件 id 或链接表示。
 * </p>
 */
public final class H3Params {

	/** 动作：生视频/图生视频/视频生视频。 */
	public final String action;
	/** 提示词。 */
	public final String prompt;
	/** 负面提示词。 */
	public final String negative;
	/** 模型档位：int8 / nvfp4。 */
	public final String model;
	/** 采样步数，0 表示按档位默认值。 */
	public final int steps;
	/** 是否稀疏采样。 */
	public final boolean sparse;
	/** 宽度。 */
	public final int width;
	/** 高度。 */
	public final int height;
	/** 帧数，0 表示按秒数换算。 */
	public final int frames;
	/** 随机种子。 */
	public final long seed;
	/** 秒数。 */
	public final double seconds;
	/** 首帧（附件 id 或链接）。 */
	public final String firstFrame;
	/** 尾帧（附件 id 或链接）。 */
	public final String lastFrame;
	/** 首段视频。 */
	public final String firstVideo;
	/** 尾段视频。 */
	public final String lastVideo;
	/** 配音/截帧使用的视频。 */
	public final String video;
	/** 截帧位置。 */
	public final int frame;
	/** 首段起点。 */
	public final int firstStart;
	/** 首段长度。 */
	public final int firstLength;
	/** 尾段起点。 */
	public final int lastStart;
	/** 尾段长度。 */
	public final int lastLength;
	/** 局部配音起点。 */
	public final double start;
	/** 局部配音长度。 */
	public final double length;

	public H3Params(String action, String prompt, String negative, String model, int steps, boolean sparse, int width,
			int height, int frames, long seed, double seconds, String firstFrame, String lastFrame, String firstVideo,
			String lastVideo, String video, int frame, int firstStart, int firstLength, int lastStart, int lastLength,
			double start, double length) {
		this.action = action == null ? "" : action;
		this.prompt = prompt == null ? "" : prompt;
		this.negative = negative == null ? "" : negative;
		this.model = model == null ? "" : model;
		this.steps = steps;
		this.sparse = sparse;
		this.width = width;
		this.height = height;
		this.frames = frames;
		this.seed = seed;
		this.seconds = seconds;
		this.firstFrame = emptyToNull(firstFrame);
		this.lastFrame = emptyToNull(lastFrame);
		this.firstVideo = emptyToNull(firstVideo);
		this.lastVideo = emptyToNull(lastVideo);
		this.video = emptyToNull(video);
		this.frame = frame;
		this.firstStart = firstStart;
		this.firstLength = firstLength;
		this.lastStart = lastStart;
		this.lastLength = lastLength;
		this.start = start;
		this.length = length;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}
}
