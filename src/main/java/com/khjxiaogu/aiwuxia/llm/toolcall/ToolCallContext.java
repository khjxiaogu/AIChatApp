package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.ToolCallCollector;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

/**
 * 单次模型调用（一次生成）的工具调用上下文。
 * <p>
 * 承载这次调用过程中的全部可变状态：工具调用增量收集器、轮次计数、总调用次数、
 * 各工具调用次数、共享次数组计数、超限提示标记。调用规则（次数、权限、许可）由可变的
 * {@link ToolCallManager} 直接提供，调用方为每次生成创建（并最终丢弃）一个本对象，
 * 从而把“管理规则”和“单次执行状态”分离。
 * </p>
 * <p>
 * 工具统一返回 {@link CompletableFuture}。同步工具等待完成后返回真实结果；注册为异步的工具
 * 立即返回“任务已创建，任务ID：...”，任务完成由 {@link ToolCallManager} 通知会话。
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

		/**
		 * 至本次为止实际执行的、计入总次数上限的工具调用次数
		 * （声明为不限次的工具不占用该额度，不计入其中）。
		 */
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
	/** 各共享次数组已实际执行的次数 */
	private final Map<Integer, AtomicInteger> groupCalls = new ConcurrentHashMap<>();
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
	 * 工具表为空、规则为默认值，任何工具调用都会被判定为工具不存在并原样回传给模型。
	 * </p>
	 *
	 * @param manager 工具调用管理器（工具注册表 + 调用规则），可为 null
	 * @param output  流式输出对象，工具调用与结果会实时推送给读取端，可为 null
	 * @param history 请求历史写入器，可为 null（等价于 {@link ToolCallHistory#NONE}）
	 * @return 新的调用上下文，用完即可丢弃
	 */
	public static ToolCallContext create(ToolCallManager manager, StreamedAIOutput output, ToolCallHistory history) {
		return new ToolCallContext(manager == null ? new ToolCallManager() : manager, output, history);
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
	 * 依次完成：计数校验（轮次/总次数/单工具次数/共享组次数）→ 权限判定 → 许可 → 执行
	 * → 结果推送与历史写入。处理后收集器自动重置，可直接进入下一轮。
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

		int round = countsRoundLimit(toolCalls) ? rounds.incrementAndGet() : rounds.get();
		// 原始调用信息 → 用户友好显示（简述替代工具名 + 具体内容），交给转换器处理
		List<ToolCallDisplay> displays = buildCallDisplays(toolCalls);
		ToolCallContent toolCallContent = new ToolCallContent(toolCalls, displays);
		emit(toolCallContent);
		history.appendAssistantToolCalls(reasonerText, toolCalls);

		boolean limitReached = false;
		boolean shouldContinue;
		List<ToolContent> results = new ArrayList<>(toolCalls.size());

		// 轮次超限时只拒绝受轮次限制的调用，声明为不限次的工具仍照常执行
		boolean roundExceeded = manager.hasRoundLimit() && round > manager.getMaxRounds();
		callLimitExhausted.set(false);
		for (int i = 0; i < toolCalls.size(); i++) {
			ToolCall call = toolCalls.get(i);
			if (roundExceeded && !manager.isUnlimited(nameOf(call))) {
				limitReached = true;
				results.add(notExecuted(call, manager.getLimitMessage(), displays.get(i)));
			} else {
				results.add(execute(call, displays.get(i)));
			}
		}
		limitReached = limitReached || callLimitExhausted.get();
		shouldContinue = limitReached ? !limitNotified.getAndSet(true) : true;

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
	 */
	private ToolContent execute(ToolCall call, ToolCallDisplay callDisplay) {
		if (call.function == null) {
			return notExecuted(call, manager.getMissingToolMessage(), callDisplay);
		}
		ToolRegistration registration = manager.getRegistration(call.function.name);
		if (registration == null) {
			return notExecuted(call, manager.getMissingToolMessage(), callDisplay);
		}
		ToolData data = registration.getData();
		// 声明为不限次的工具不参与任何次数校验，也不占用总次数上限的额度
		boolean unlimited = manager.isUnlimited(call.function.name);
		if (!unlimited && manager.hasCallLimit() && calls.get() >= manager.getMaxCalls()) {
			callLimitExhausted.set(true);
			return notExecuted(call, manager.getLimitMessage(), callDisplay);
		}
		int groupId = registration.getGroupId();
		if (!unlimited && groupId > 0 && manager.hasGroupLimit(groupId)) {
			int groupLimit = manager.getGroupMaxCalls(groupId);
			if (getGroupCallCount(groupId) >= groupLimit) {
				callLimitExhausted.set(true);
				return notExecuted(call, manager.getGroupLimitMessage(), callDisplay);
			}
		}
		// 单个工具的执行次数上限（属于共享组时以组上限为准）
		if (!unlimited && groupId <= 0 && registration.getMaxCalls() >= 0
				&& getToolCallCount(call.function.name) >= registration.getMaxCalls()) {
			callLimitExhausted.set(true);
			return notExecuted(call, manager.getToolLimitMessage(), callDisplay);
		}

		// 权限由管理器上的全部监听器合并判定（拒绝 > 询问 > 允许 > 忽略，遇拒绝短路）
		ToolCallDecision decision = manager.checkPermission(call.function.name, call.function.arguments);
		if (decision == null) {
			decision = ToolCallDecision.ALLOW;
		}
		if (decision.needsApproval()) {
			ToolCallApprover approver = manager.getApprover();
			boolean approved = approver != null && approver.approve(call.function.name, call.function.arguments,
					decision.getReason());
			// 许可原因只用于向许可方说明，回传给模型的是“未获许可”的结果
			decision = approved ? ToolCallDecision.ALLOW : ToolCallDecision.deny(manager.getUnapprovedMessage());
		}
		if (!decision.isAllowed()) {
			return notExecuted(call, decision.getReasonOr(ToolCallManager.DEFAULT_DENY_MESSAGE), callDisplay);
		}

		if (!unlimited) {
			// 只有受上限约束的调用才占用总次数额度
			calls.incrementAndGet();
		}
		toolCalls.computeIfAbsent(call.function.name, n -> new AtomicInteger()).incrementAndGet();
		if (groupId > 0) {
			groupCalls.computeIfAbsent(groupId, n -> new AtomicInteger()).incrementAndGet();
		}
		try {
			CompletableFuture<ToolResult> future = data.tool == null ? null
					: data.tool.run(call.function.arguments, output, manager);
			if (future == null) {
				return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
			}
			if (registration.isAsync()) {
				ToolTask task = manager.submitTask(call.function.name, call.id, future, callDisplay);
				if (task == null) {
					return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
				}
				return new ToolContent(call.id, manager.asyncReply(task), ToolCallDisplay.of(callDisplay.getBrief()));
			}
			ToolResult result = future.get();
			if (result == null) {
				return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
			}
			attachContents(result.getAttachments());
			return new ToolContent(call.id, result.getText(), buildResultDisplay(call, callDisplay, result.getText()));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
		} catch (ExecutionException ex) {
			ex.printStackTrace();
			return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
		} catch (Throwable ex) {
			ex.printStackTrace();
			return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
		}
	}

	/** 把同步工具结果中的附件交给本次输出的监听方（通常是会话）。 */
	private void attachContents(List<MessageContent> attachments) {
		if (output == null || attachments == null || attachments.isEmpty()) {
			return;
		}
		for (MessageContent attachment : attachments) {
			if (attachment != null) {
				output.attachContent(attachment);
			}
		}
	}

	/**
	 * 本批调用是否需要消耗一轮配额。
	 * <p>
	 * 只要本批中存在至少一个受轮次限制的调用就计一轮；全部由声明为不限次的工具组成时不计轮次。
	 * </p>
	 *
	 * @param toolCalls 本批工具调用
	 * @return 是否计入轮次
	 */
	private boolean countsRoundLimit(List<ToolCall> toolCalls) {
		for (ToolCall call : toolCalls) {
			if (!manager.isUnlimited(nameOf(call))) {
				return true;
			}
		}
		return false;
	}

	/** 调用对应的工具名称，缺失时为 null。 */
	private static String nameOf(ToolCall call) {
		return call == null || call.function == null ? null : call.function.name;
	}

	/** 未真正执行的调用（不存在/超限/无权限/无许可/出错）：沿用调用的简述，不带结果内容。 */
	private static ToolContent notExecuted(ToolCall call, String message, ToolCallDisplay callDisplay) {
		return new ToolContent(call.id, message, ToolCallDisplay.of(callDisplay.getBrief()));
	}

	/**
	 * 把原始调用信息转换为用户友好显示，交给管理器的统一实现。
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
		groupCalls.clear();
		limitNotified.set(false);
		callLimitExhausted.set(false);
	}

	/** 已处理的、计入轮次上限的工具调用轮次。 */
	public int getRounds() {
		return rounds.get();
	}

	/** 已实际执行的、计入总次数上限的工具调用次数。 */
	public int getCallCount() {
		return calls.get();
	}

	/** 指定工具已实际执行的次数（含不限次工具，仅用于统计）。 */
	public int getToolCallCount(String toolName) {
		AtomicInteger count = toolCalls.get(toolName);
		return count == null ? 0 : count.get();
	}

	/** 指定共享组已实际执行的次数。 */
	public int getGroupCallCount(int groupId) {
		AtomicInteger count = groupCalls.get(groupId);
		return count == null ? 0 : count.get();
	}

	/** 剩余可用轮次，未限制时返回 -1。 */
	public int getRemainingRounds() {
		return manager.hasRoundLimit() ? Math.max(0, manager.getMaxRounds() - rounds.get()) : -1;
	}

	/** 剩余可用调用总次数，未限制时返回 -1。 */
	public int getRemainingCalls() {
		return manager.hasCallLimit() ? Math.max(0, manager.getMaxCalls() - calls.get()) : -1;
	}

	/** 指定工具剩余可用执行次数，未配置上限时返回 -1。 */
	public int getRemainingCallsOf(String toolName) {
		ToolRegistration registration = manager.getRegistration(toolName);
		if (registration == null) {
			return -1;
		}
		int groupId = registration.getGroupId();
		if (groupId > 0 && manager.hasGroupLimit(groupId)) {
			return Math.max(0, manager.getGroupMaxCalls(groupId) - getGroupCallCount(groupId));
		}
		if (registration.getMaxCalls() < 0) {
			return -1;
		}
		return Math.max(0, registration.getMaxCalls() - getToolCallCount(toolName));
	}

	/** 当前使用的工具调用管理器（提供工具表与调用规则）。 */
	public ToolCallManager getManager() {
		return manager;
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
