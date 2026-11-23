package com.khjxiaogu.aiwuxia.llm.toolcall;

/**
 * 一次工具调用的状态事件（仅用于展示，不写入历史、不回传模型）。
 * <p>
 * 由 {@link ToolCallContext} 产生，经 {@link ToolCallManager} 的监听器交给会话，
 * 会话再转成 WebSocket 的 {@code tool_status} 帧。
 * </p>
 */
public final class ToolCallEvent {

	private final String callId;
	private final String tool;
	private final String name;
	private final ToolCallState state;
	private final int round;
	private final int index;
	private final int count;
	/** 事件时间（毫秒）。 */
	private final long time;
	/** 真正开始执行的时间；排队/未执行时为 0。 */
	private final long startedAt;
	/** 预计总耗时（毫秒），可为 null。 */
	private final Long etaMs;
	/** 进度比例 0~1，可为 null。 */
	private final Double progress;
	/** 阶段说明或失败原因，可为空串。 */
	private final String message;

	public ToolCallEvent(String callId, String tool, String name, ToolCallState state, int round, int index,
			int count, long time, long startedAt, Long etaMs, Double progress, String message) {
		this.callId = callId == null ? "" : callId;
		this.tool = tool == null ? "" : tool;
		this.name = name == null ? "" : name;
		this.state = state == null ? ToolCallState.RUNNING : state;
		this.round = round;
		this.index = index;
		this.count = count;
		this.time = time;
		this.startedAt = startedAt;
		this.etaMs = etaMs == null || etaMs <= 0 ? null : etaMs;
		this.progress = progress == null ? null : Double.valueOf(Math.max(0d, Math.min(1d, progress)));
		this.message = message == null ? "" : message;
	}

	/** 模型 tool_call id（同一批调用内唯一）。 */
	public String getCallId() {
		return callId;
	}

	/** 原始工具名。 */
	public String getTool() {
		return tool;
	}

	/** 用户友好简述。 */
	public String getName() {
		return name;
	}

	/** 当前状态。 */
	public ToolCallState getState() {
		return state;
	}

	/** 第几轮工具调用（1 起）。 */
	public int getRound() {
		return round;
	}

	/** 本批第几个（0 起）。 */
	public int getIndex() {
		return index;
	}

	/** 本批共几个。 */
	public int getCount() {
		return count;
	}

	/** 事件时间。 */
	public long getTime() {
		return time;
	}

	/** 开始执行的时间；未开始时为 0。 */
	public long getStartedAt() {
		return startedAt;
	}

	/** 预计总耗时（毫秒），可为 null。 */
	public Long getEtaMs() {
		return etaMs;
	}

	/** 进度比例，可为 null。 */
	public Double getProgress() {
		return progress;
	}

	/** 阶段说明或失败原因。 */
	public String getMessage() {
		return message;
	}

	@Override
	public String toString() {
		return "ToolCallEvent [callId=" + callId + ", tool=" + tool + ", state=" + state + ", etaMs=" + etaMs
				+ ", progress=" + progress + ", message=" + message + "]";
	}
}
