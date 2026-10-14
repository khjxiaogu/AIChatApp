package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.ToolCallCollector;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

/**
 * 单次模型调用（一次生成）的工具调用上下文。
 * <p>
 * 承载这次调用过程中的全部可变状态：工具调用增量收集器、轮次计数、总调用次数、
 * 各工具调用次数、超限提示标记。{@link ToolCallManager} 本身无状态，调用方为每次生成
 * 创建（并最终丢弃）一个本对象，从而把“管理规则”和“单次执行状态”彻底分离。
 * </p>
 *
 * <p>本类负责（全部通过 {@link ToolCallManager} 提供的工具表与 {@link ToolCallPolicy} 执行）：</p>
 * <ul>
 *   <li><b>调用次数</b>：轮次上限、总次数上限、<b>各工具执行次数上限</b>，超限时统一回传提示文本；</li>
 *   <li><b>权限</b>：每个调用执行前询问权限策略；</li>
 *   <li><b>许可</b>：权限判定为“需要许可”时交由许可方决定是否执行；</li>
 *   <li><b>处理</b>：工具查找、执行、异常兜底、结果推送到流式输出、写入请求历史；</li>
 *   <li><b>显示</b>：调用与结果都经工具的 {@link ToolCallPresenter} 转换为用户友好显示
 *       （简述替代工具名 + 具体内容），随 {@code ToolCallContent}/{@code ToolContent} 下发。</li>
 * </ul>
 *
 * <p>
 * 计数使用原子变量，因此并发收集增量的计数不会丢失；但 {@link #process(String)}
 * 涉及“收集 → 执行 → 写历史”的顺序语义，必须由调用方保证同一时刻只有一个线程在处理。
 * </p>
 */
public class ToolCallContext {

	/**
	 * 一轮工具调用的处理结果。
	 */
	public static class ProcessResult {
		private final ToolCallContent toolCallContent;
		private final List<ToolContent> toolResults;
		private final int round;
		private final int callCount;
		private final boolean limitReached;
		private final boolean interrupted;
		private final boolean shouldContinue;

		ProcessResult(ToolCallContent toolCallContent, List<ToolContent> toolResults, int round, int callCount,
				boolean limitReached, boolean interrupted, boolean shouldContinue) {
			this.toolCallContent = toolCallContent;
			this.toolResults = toolResults;
			this.round = round;
			this.callCount = callCount;
			this.limitReached = limitReached;
			this.interrupted = interrupted;
			this.shouldContinue = shouldContinue;
		}

		/**
		 * 本次工具调用内容（包含所有工具调用请求）；若在调用前即被中断则为 null。
		 */
		public ToolCallContent getToolCallContent() {
			return toolCallContent;
		}

		/** 本次模型请求的工具调用列表，未被处理时为 null。 */
		public List<ToolCall> getToolCalls() {
			return toolCallContent == null ? null : toolCallContent.getToolCalls();
		}

		/** 所有工具执行结果，顺序与工具调用一致。 */
		public List<ToolContent> getToolResults() {
			return toolResults;
		}

		/** 已处理的工具调用轮次（含本次）。 */
		public int getRound() {
			return round;
		}

		/** 至本次为止实际执行的工具调用总次数。 */
		public int getCallCount() {
			return callCount;
		}

		/** 本次是否因超出调用次数限制而未执行任何工具。 */
		public boolean isLimitReached() {
			return limitReached;
		}

		/** 本次处理过程中生成是否已被中断。 */
		public boolean isInterrupted() {
			return interrupted;
		}

		/**
		 * 提供者是否应继续下一轮请求。
		 * <p>
		 * 中断、或超限后已经给过模型一次回复机会时为 false。
		 * </p>
		 */
		public boolean shouldContinue() {
			return shouldContinue;
		}
	}

	private final ToolCallManager manager;
	private final StreamedAIOutput output;
	private final ToolCallHistory history;

	/**
	 * 本轮的增量收集器。
	 * <p>不同轮次可能由不同的 SSE 回调线程进入，用 volatile 保证引用可见性。</p>
	 */
	private volatile ToolCallCollector collector = new ToolCallCollector();
	/** 已处理的工具调用轮次 */
	private final AtomicInteger rounds = new AtomicInteger();
	/** 已实际执行的工具调用总次数 */
	private final AtomicInteger calls = new AtomicInteger();
	/** 各工具已实际执行的次数 */
	private final Map<String, AtomicInteger> toolCalls = new ConcurrentHashMap<>();
	/** 是否已经因超限提示过模型一次 */
	private final AtomicBoolean limitNotified = new AtomicBoolean();
	/** 本轮是否已有调用因触及次数上限而未执行 */
	private final AtomicBoolean callLimitExhausted = new AtomicBoolean();

	ToolCallContext(ToolCallManager manager, StreamedAIOutput output, ToolCallHistory history) {
		this.manager = manager;
		this.output = output;
		this.history = history == null ? ToolCallHistory.NONE : history;
	}

	/**
	 * 创建一次模型调用的上下文。
	 * <p>
	 * 未提供管理器（{@code manager == null}）时，返回一个等效于“没有任何工具”的上下文：
	 * 工具表为空、策略为默认值，任何工具调用都会被判定为工具不存在并原样回传给模型。
	 * </p>
	 *
	 * @param manager 工具调用管理器（工具注册表 + 调用规则），可为 null
	 * @param output  流式输出对象，工具调用与结果会实时推送给读取端，可为 null
	 * @param history 请求历史写入器，可为 null（等价于 {@link ToolCallHistory#NONE}）
	 * @return 新的调用上下文，用完即可丢弃
	 */
	public static ToolCallContext create(ToolCallManager manager, StreamedAIOutput output, ToolCallHistory history) {
		return new ToolCallContext(manager == null ? ToolCallManager.EMPTY : manager, output, history);
	}

	/**
	 * 创建一次模型调用的上下文（等效于不提供管理器，即没有任何工具）。
	 *
	 * @param output  流式输出对象，可为 null
	 * @param history 请求历史写入器，可为 null
	 */
	public static ToolCallContext create(StreamedAIOutput output, ToolCallHistory history) {
		return create(null, output, history);
	}

	/**
	 * 收集流式响应中的工具调用增量。
	 *
	 * @param delta 单个工具调用增量
	 */
	public void collect(ToolCall delta) {
		if (delta != null) {
			collector.collect(delta);
		}
	}

	/**
	 * 批量收集流式响应中的工具调用增量。
	 *
	 * @param deltas 工具调用增量列表，可为 null
	 */
	public void collect(List<ToolCall> deltas) {
		if (deltas == null) {
			return;
		}
		for (ToolCall delta : deltas) {
			collect(delta);
		}
	}

	/** 当前是否已收集到工具调用。 */
	public boolean hasCollectedCalls() {
		return collector.isValid();
	}

	/**
	 * 统一处理当前收集到的工具调用。
	 * <p>
	 * 依次完成：计数校验（轮次/总次数/单工具次数）→ 权限判定 → 许可 → 执行 → 结果推送与历史写入。
	 * 处理后收集器自动重置，可直接进入下一轮。
	 * </p>
	 *
	 * @param reasonerText 本轮累积的思维链/正文文本，用于写入助手历史消息，可为 null
	 * @return 处理结果；本轮没有工具调用时返回 null
	 */
	public ProcessResult process(String reasonerText) {
		List<ToolCall> toolCalls = collector.build();
		if (toolCalls.isEmpty()) {
			return null;
		}
		// 本轮的增量已消费，重置以便下一轮收集
		collector = new ToolCallCollector();

		// 生成已被中断：不执行工具，也不写入历史，提示提供者结束循环
		if (isInterrupted()) {
			return new ProcessResult(null, Collections.<ToolContent>emptyList(), rounds.get(), calls.get(), false, true,
					false);
		}

		int round = rounds.incrementAndGet();
		// 原始调用信息 → 用户友好显示（简述替代工具名 + 具体内容），交给转换器处理
		List<ToolCallDisplay> displays = buildCallDisplays(toolCalls);
		ToolCallContent toolCallContent = new ToolCallContent(toolCalls, displays);
		emit(toolCallContent);
		history.appendAssistantToolCalls(reasonerText, toolCalls);

		boolean limitReached = false;
		boolean shouldContinue;
		List<ToolContent> results = new ArrayList<>(toolCalls.size());

		if (manager.getPolicy().hasRoundLimit() && round > manager.getPolicy().getMaxRounds()) {
			// 轮次超限：本轮全部拒绝执行
			limitReached = true;
			shouldContinue = !limitNotified.getAndSet(true);
			for (int i = 0; i < toolCalls.size(); i++) {
				results.add(notExecuted(toolCalls.get(i), manager.getPolicy().getLimitMessage(), displays.get(i)));
			}
		} else {
			callLimitExhausted.set(false);
			for (int i = 0; i < toolCalls.size(); i++) {
				results.add(execute(toolCalls.get(i), displays.get(i)));
			}
			limitReached = callLimitExhausted.get();
			if (limitReached) {
				shouldContinue = !limitNotified.getAndSet(true);
			} else {
				shouldContinue = true;
			}
		}

		for (ToolContent result : results) {
			emit(result);
			history.appendToolResult(result);
		}

		boolean interrupted = isInterrupted();
		if (interrupted) {
			shouldContinue = false;
		}

		return new ProcessResult(toolCallContent, Collections.unmodifiableList(results), rounds.get(), calls.get(),
				limitReached, interrupted, shouldContinue);
	}

	/**
	 * 执行单个工具调用：查找 → 次数校验 → 权限 → 许可 → 执行，任何一步失败都以工具结果形式回传给模型。
	 *
	 * @param call         原始工具调用
	 * @param callDisplay  该调用的用户友好显示（由工具自己的转换器生成）
	 */
	private ToolContent execute(ToolCall call, ToolCallDisplay callDisplay) {
		ToolCallPolicy policy = manager.getPolicy();
		if (call.function == null) {
			return notExecuted(call, policy.getMissingToolMessage(), callDisplay);
		}
		ToolData data = manager.getTools().get(call.function.name);
		if (data == null) {
			return notExecuted(call, policy.getMissingToolMessage(), callDisplay);
		}
		if (policy.hasCallLimit() && calls.get() >= policy.getMaxCalls()) {
			callLimitExhausted.set(true);
			return notExecuted(call, policy.getLimitMessage(), callDisplay);
		}
		// 单个工具的执行次数上限
		if (policy.hasToolCallLimit(call.function.name)
				&& getToolCallCount(call.function.name) >= policy.getToolCallLimit(call.function.name)) {
			callLimitExhausted.set(true);
			return notExecuted(call, policy.getToolLimitMessage(), callDisplay);
		}

		ToolCallDecision decision = policy.getPermission() == null ? ToolCallDecision.ALLOW
				: policy.getPermission().check(call.function.name, call.function.arguments);
		if (decision == null) {
			decision = ToolCallDecision.ALLOW;
		}
		if (decision.needsApproval()) {
			ToolCallApprover approver = policy.getApprover();
			boolean approved = approver != null && approver.approve(call.function.name, call.function.arguments,
					decision.getReason());
			// 许可原因只用于向许可方说明，回传给模型的是“未获许可”的结果
			decision = approved ? ToolCallDecision.ALLOW : ToolCallDecision.deny(policy.getUnapprovedMessage());
		}
		if (!decision.isAllowed()) {
			return notExecuted(call, decision.getReasonOr(ToolCallPolicy.DEFAULT_DENY_MESSAGE), callDisplay);
		}

		calls.incrementAndGet();
		toolCalls.computeIfAbsent(call.function.name, n -> new AtomicInteger()).incrementAndGet();
		try {
			String result = data.tool.run(call.function.arguments);
			return new ToolContent(call.id, result, buildResultDisplay(call, callDisplay, result));
		} catch (Throwable ex) {
			ex.printStackTrace();
			return notExecuted(call, policy.getToolErrorMessage(), callDisplay);
		}
	}

	/** 未真正执行的调用（不存在/超限/无权限/无许可/出错）：沿用调用的简述，不带结果内容。 */
	private static ToolContent notExecuted(ToolCall call, String message, ToolCallDisplay callDisplay) {
		return new ToolContent(call.id, message, ToolCallDisplay.of(callDisplay.getBrief()));
	}

	/**
	 * 把原始调用信息转换为用户友好显示，交给管理器的统一实现
	 * （与历史重新加载后的重算使用同一套转换规则）。
	 */
	private List<ToolCallDisplay> buildCallDisplays(List<ToolCall> calls) {
		List<ToolCallDisplay> displays = new ArrayList<>(calls.size());
		for (ToolCall call : calls) {
			displays.add(manager.displayFor(call));
		}
		return displays;
	}

	/**
	 * 生成结果的具体内容显示，交给管理器的统一实现。
	 */
	private ToolCallDisplay buildResultDisplay(ToolCall call, ToolCallDisplay callDisplay, String result) {
		return manager.resultDisplayFor(call, callDisplay, result);
	}

	/** 重置收集器与全部计数，用于复用同一个上下文。 */
	public void reset() {
		collector = new ToolCallCollector();
		rounds.set(0);
		calls.set(0);
		toolCalls.clear();
		limitNotified.set(false);
		callLimitExhausted.set(false);
	}

	/** 已处理的工具调用轮次。 */
	public int getRounds() {
		return rounds.get();
	}

	/** 已实际执行的工具调用总次数。 */
	public int getCallCount() {
		return calls.get();
	}

	/** 指定工具已实际执行的次数。 */
	public int getToolCallCount(String toolName) {
		AtomicInteger count = toolCalls.get(toolName);
		return count == null ? 0 : count.get();
	}

	/** 剩余可用轮次，未限制时返回 -1。 */
	public int getRemainingRounds() {
		return manager.getPolicy().hasRoundLimit() ? Math.max(0, manager.getPolicy().getMaxRounds() - rounds.get()) : -1;
	}

	/** 剩余可用调用总次数，未限制时返回 -1。 */
	public int getRemainingCalls() {
		return manager.getPolicy().hasCallLimit() ? Math.max(0, manager.getPolicy().getMaxCalls() - calls.get()) : -1;
	}

	/** 指定工具剩余可用执行次数，未配置上限时返回 -1。 */
	public int getRemainingCallsOf(String toolName) {
		if (!manager.getPolicy().hasToolCallLimit(toolName)) {
			return -1;
		}
		return Math.max(0, manager.getPolicy().getToolCallLimit(toolName) - getToolCallCount(toolName));
	}

	/** 当前使用的无状态管理器。 */
	public ToolCallManager getManager() {
		return manager;
	}

	/** 当前使用的管理策略。 */
	public ToolCallPolicy getPolicy() {
		return manager.getPolicy();
	}

	private boolean isInterrupted() {
		return output != null && output.isInterrupted();
	}

	/** 推送内容到流式输出（输出对象可为 null，便于离线复用管理器）。 */
	private void emit(MessageContent content) {
		if (output != null) {
			output.putReasoner(content);
		}
	}
}
