package com.khjxiaogu.aiwuxia.llm.toolcall;

import com.khjxiaogu.aiwuxia.llm.ToolData;

/**
 * 管理器内部的一条工具注册记录：工具定义 + 注册时决定的执行策略。
 * <p>
 * {@link #groupId} 为 0 表示不与其他工具共享调用次数；大于 0 时表示属于某个共享次数组。
 * 一个工具同一时间只能属于一个组，重新绑定会覆盖旧组。
 * </p>
 */
public final class ToolRegistration {

	private final ToolData data;
	private boolean async;
	private int maxCalls;
	private int groupId;

	ToolRegistration(ToolData data, boolean async, int maxCalls) {
		this.data = data;
		this.async = async;
		this.maxCalls = maxCalls < 0 ? -1 : maxCalls;
	}

	/** 工具定义。 */
	public ToolData getData() {
		return data;
	}

	/** 工具名称。 */
	public String getName() {
		return data.name;
	}

	/** 是否异步执行。 */
	public boolean isAsync() {
		return async;
	}

	void setAsync(boolean async) {
		this.async = async;
	}

	/**
	 * 本工具的调用顺序是否重要。
	 * <p>
	 * 同一批工具调用默认并发执行；顺序重要的工具作为屏障，等待此前同步调用完成后再执行，
	 * 它（若为同步工具）完成后才启动后续调用；异步工具不被等待。
	 * 取值来自 {@link ToolData#orderSensitive}。
	 * </p>
	 *
	 * @return 顺序重要返回 {@code true}
	 */
	public boolean isOrderSensitive() {
		return data.orderSensitive;
	}

	/** 单工具最大调用次数，-1 表示不限；属于共享组时该值不参与执行判断。 */
	public int getMaxCalls() {
		return maxCalls;
	}

	void setMaxCalls(int maxCalls) {
		this.maxCalls = maxCalls < 0 ? -1 : maxCalls;
	}

	/** 共享次数组 ID，0 表示未分组。 */
	public int getGroupId() {
		return groupId;
	}

	void setGroupId(int groupId) {
		this.groupId = groupId;
	}

	@Override
	public String toString() {
		return "ToolRegistration [name=" + data.name + ", async=" + async + ", orderSensitive="
				+ data.orderSensitive + ", maxCalls=" + maxCalls + ", groupId=" + groupId + "]";
	}
}
