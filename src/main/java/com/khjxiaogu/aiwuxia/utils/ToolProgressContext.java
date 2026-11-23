package com.khjxiaogu.aiwuxia.utils;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 把“当前工具调用的进度接收方”绑定到执行工具的那条线程上。
 * <p>
 * {@link com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext} 在调用工具前设置一个 sink；
 * 工具内部调用 {@code LocalModel.require(...)} 时，本地模型握手器可以从当前线程取到它，
 * 把连接器上报的 {@code queued/running/progress} 转回工具调用的状态事件。
 * 这样媒体工具不需要修改签名，只有真正发起本地模型请求的路径才会绑定。
 * </p>
 */
public final class ToolProgressContext {

	private static final ThreadLocal<Consumer<ToolProgress>> CURRENT = new ThreadLocal<>();

	private ToolProgressContext() {
	}

	/** 当前线程绑定的进度接收方，没有时返回 null。 */
	public static Consumer<ToolProgress> current() {
		return CURRENT.get();
	}

	/**
	 * 在绑定进度接收方的线程上执行一段代码，执行完恢复原来的绑定。
	 *
	 * @param sink 进度接收方，可为 null（此时直接执行）
	 * @param body 要执行的代码
	 * @param <T>  返回值类型
	 * @return 代码的返回值
	 */
	public static <T> T callWith(Consumer<ToolProgress> sink, Supplier<T> body) {
		if (sink == null) {
			return body.get();
		}
		Consumer<ToolProgress> previous = CURRENT.get();
		CURRENT.set(sink);
		try {
			return body.get();
		} finally {
			if (previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		}
	}
}
