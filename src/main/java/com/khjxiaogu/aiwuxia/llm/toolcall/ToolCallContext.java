package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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
	 * 生成被用户中断时，尚未执行（或还没处理完）的工具调用统一回写的工具结果文本。
	 * <p>
	 * 模型可能已经下发了工具调用，但用户在这一轮里主动停止了生成：调用照常记进历史，
	 * 结果统一写成这句话，保证历史里 {@code tool_calls} 与 {@code tool} 消息成对出现，
	 * 后续请求不会因为缺少工具结果而失败。
	 * </p>
	 */
	public static final String INTERRUPTED_MESSAGE = "用户已停止生成。";

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
		 * 本次工具调用内容（包含所有工具调用请求）；本次没有工具调用时为 null。
		 * <p>
		 * 生成被中断时同样会带上模型已经下发的调用，此时
		 * {@link #getToolResults()} 里的结果统一为“用户已停止生成。”。
		 * </p>
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
	/**
	 * 工具执行线程池。
	 * <p>
	 * 一批工具调用默认并发运行，工具本体不再占住模型流读取线程。线程为守护线程，
	 * 不阻止进程退出。
	 * </p>
	 */
	private static final ExecutorService TOOL_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicInteger sequence = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "tool-call-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	});

	/**
	 * 一次工具调用的启动结果。
	 * <p>
	 * 要么已经直接得到结果（不存在/超限/无权限/无许可/出错，或异步任务已创建），
	 * 要么是正在运行的 future，由 {@link #awaitExecution(List, List, int)} 取回结果。
	 * </p>
	 */
	private static final class Execution {
		/** 已直接得到的结果；为 null 时表示工具正在运行 */
		private final ToolContent immediate;
		private final ToolCall call;
		private final ToolCallDisplay display;
		/** 同步工具的完成信号；异步工具不需要等待完成，为 null */
		private final CompletableFuture<ToolResult> future;
		/** 是否已经取过结果 */
		private boolean awaited;

		private Execution(ToolContent immediate, ToolCall call, ToolCallDisplay display,
				CompletableFuture<ToolResult> future) {
			this.immediate = immediate;
			this.call = call;
			this.display = display;
			this.future = future;
		}

		static Execution finished(ToolContent result) {
			return new Execution(result, null, null, null);
		}

		static Execution running(ToolCall call, ToolCallDisplay display, CompletableFuture<ToolResult> future) {
			return new Execution(null, call, display, future);
		}
	}

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
	 * <p>
	 * 执行方式：一批调用默认<b>并发</b>运行（工具本体放到独立线程池，结果的推送与历史写入仍按
	 * 模型给出的顺序进行）。声明了“顺序重要”的工具是<b>屏障</b>：启动它之前先等此前所有
	 * <b>同步</b>调用结束，它（若为同步工具）真正执行完成之后才启动后续调用；声明为异步的
	 * 工具只返回任务回执，不被屏障等待。屏障前后的非顺序敏感调用仍然可以并发执行。
	 * 等待屏障时检查中断：中断后不再启动后面的调用，已开始的调用等它跑完并保留结果，
	 * 未执行的调用结果统一写成 {@link #INTERRUPTED_MESSAGE}。
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

		// 生成已被中断：工具不再执行，但调用本身照常写入历史，结果统一写成“用户已停止生成。”
		if (isInterrupted()) {
			return writeInterrupted(toolCalls, reasonerText);
		}

		int round = countsRoundLimit(toolCalls) ? rounds.incrementAndGet() : rounds.get();
		// 原始调用信息 → 用户友好显示（简述替代工具名 + 具体内容），交给转换器处理
		List<ToolCallDisplay> displays = buildCallDisplays(toolCalls);
		ToolCallContent toolCallContent = new ToolCallContent(toolCalls, displays);
		emit(toolCallContent);
		history.appendAssistantToolCalls(reasonerText, toolCalls);

		// 轮次超限时只拒绝受轮次限制的调用，声明为不限次的工具仍照常执行
		boolean roundExceeded = manager.hasRoundLimit() && round > manager.getMaxRounds();
		callLimitExhausted.set(false);
		AtomicBoolean limitReached = new AtomicBoolean();
		List<ToolContent> results = new ArrayList<>(Collections.nCopies(toolCalls.size(), null));
		List<Execution> executions = new ArrayList<>(Collections.nCopies(toolCalls.size(), null));

		// 默认：同一批调用并发启动。顺序敏感工具是屏障：
		// 启动它之前先等本批此前所有调用（含异步任务）真正完成；它完成后才启动后续调用。
		for (int i = 0; i < toolCalls.size(); i++) {
			if (i > 0 && isInterrupted()) {
				fillInterrupted(toolCalls, results, displays, i);
				break;
			}
			ToolCall call = toolCalls.get(i);
			if (roundExceeded && !manager.isUnlimited(nameOf(call))) {
				limitReached.set(true);
				Execution execution = Execution.finished(notExecuted(call, manager.getLimitMessage(), displays.get(i)));
				executions.set(i, execution);
				results.set(i, execution.immediate);
				continue;
			}

			boolean orderSensitive = manager.isOrderSensitive(nameOf(call));
			if (orderSensitive) {
				// 屏障前：只等本批此前的同步调用完成；声明为异步的调用不等待实际完成
				awaitAll(executions, results);
				if (isInterrupted()) {
					fillInterrupted(toolCalls, results, displays, i);
					break;
				}
			}

			Execution execution = start(call, displays.get(i));
			executions.set(i, execution);
			if (execution.immediate != null) {
				results.set(i, execution.immediate);
			}

			if (orderSensitive) {
				// 屏障后：同步工具完成后才启动后续工具；异步工具只等任务创建，不等实际完成
				awaitExecution(executions, results, i);
				if (isInterrupted()) {
					fillInterrupted(toolCalls, results, displays, i + 1);
					break;
				}
			}
		}
		// 末尾非顺序敏感的调用：同步工具取结果，异步工具保持后台执行
		awaitAll(executions, results);

		boolean limitHit = limitReached.get() || callLimitExhausted.get();
		boolean shouldContinue = limitHit ? !limitNotified.getAndSet(true) : true;

		for (ToolContent result : results) {
			emit(result);
			history.appendToolResult(result);
		}

		boolean interrupted = isInterrupted();
		if (interrupted) {
			shouldContinue = false;
		}

		return new ProcessResult(toolCallContent, Collections.unmodifiableList(results), rounds.get(), calls.get(),
				limitHit, interrupted, shouldContinue);
	}

	/**
	 * 等待一组已启动调用完成，并按原位置写入结果。
	 * <p>
	 * 只等待同步工具；声明为异步的工具已经直接得到“任务已创建”的结果，
	 * 不会阻塞后续工具。
	 * </p>
	 *
	 * @param executions 与调用列表等长的启动记录，未启动处为 null
	 * @param results    结果列表
	 */
	private void awaitAll(List<Execution> executions, List<ToolContent> results) {
		for (int i = 0; i < executions.size(); i++) {
			if (executions.get(i) != null) {
				awaitExecution(executions, results, i);
			}
		}
	}

	/**
	 * 等待单个调用完成并写入结果。
	 * <p>
	 * 异步工具的结果已经是“任务已创建”，这里不再等待其实际完成；只有同步工具会阻塞等待。
	 * </p>
	 *
	 * @param executions 启动记录列表
	 * @param results    结果列表
	 * @param index      调用下标
	 * @return 工具结果，可能为 null（未启动）
	 */
	private ToolContent awaitExecution(List<Execution> executions, List<ToolContent> results, int index) {
		Execution execution = executions.get(index);
		if (execution == null) {
			return null;
		}
		if (execution.awaited) {
			return results.get(index);
		}
		if (execution.immediate != null) {
			results.set(index, execution.immediate);
			execution.awaited = true;
			return execution.immediate;
		}
		execution.awaited = true;
		ToolContent result = awaitFuture(execution.call, execution.display, execution.future);
		results.set(index, result);
		return result;
	}

	/** 把尚未执行的调用统一写成“用户已停止生成”。 */
	private static void fillInterrupted(List<ToolCall> toolCalls, List<ToolContent> results,
			List<ToolCallDisplay> displays, int from) {
		for (int i = Math.max(0, from); i < toolCalls.size(); i++) {
			if (results.get(i) == null) {
				results.set(i, notExecuted(toolCalls.get(i), INTERRUPTED_MESSAGE, displays.get(i)));
			}
		}
	}

	/**
	 * 生成被中断时，把还留在收集器里、没来得及走完正常处理流程的工具调用写进历史。
	 * <p>
	 * 正常中断不会截断模型的输出：模型会把手上的这一轮跑完，工具调用由
	 * {@link #process(String)} 处理。只有在流没有走到 {@code finish_reason=tool_calls}
	 * 就结束（例如连接中断、用户关闭页面）时才需要本方法兜底：把已经收集到的调用按
	 * “已下发但未执行”处理，调用照常推给读取端并写入历史，结果统一为
	 * {@link #INTERRUPTED_MESSAGE}。
	 * </p>
	 *
	 * @param reasonerText 本轮累积的思维链/正文文本，用于写入助手历史消息，可为 null
	 * @return 处理结果；没有已收集的工具调用时返回 null
	 */
	public ProcessResult flushInterrupted(String reasonerText) {
		List<ToolCall> toolCalls = collector.build();
		if (toolCalls.isEmpty()) {
			return null;
		}
		collector = new ToolCallCollector();
		return writeInterrupted(toolCalls, reasonerText);
	}

	/**
	 * 按“调用已下发、工具未执行”的语义写入一批工具调用与结果。
	 * <p>
	 * 与正常流程共用同一套输出与历史写入路径，因此前端看到的内容结构完全一致；
	 * 区别只是工具不会真正执行，结果固定为 {@link #INTERRUPTED_MESSAGE}，
	 * 且不计入轮次与调用次数。
	 * </p>
	 *
	 * @param toolCalls    模型下发的工具调用
	 * @param reasonerText 本轮累积的思维链/正文文本
	 * @return 处理结果（{@code interrupted} 为 true、{@code shouldContinue} 为 false）
	 */
	private ProcessResult writeInterrupted(List<ToolCall> toolCalls, String reasonerText) {
		List<ToolCallDisplay> displays = buildCallDisplays(toolCalls);
		ToolCallContent toolCallContent = new ToolCallContent(toolCalls, displays);
		emit(toolCallContent);
		history.appendAssistantToolCalls(reasonerText, toolCalls);

		List<ToolContent> results = new ArrayList<>(toolCalls.size());
		for (int i = 0; i < toolCalls.size(); i++) {
			ToolContent result = notExecuted(toolCalls.get(i), INTERRUPTED_MESSAGE, displays.get(i));
			results.add(result);
			emit(result);
			history.appendToolResult(result);
		}

		return new ProcessResult(toolCallContent, Collections.unmodifiableList(results), rounds.get(), calls.get(),
				false, true, false);
	}

	/**
	 * 启动单个工具调用：查找 → 次数校验 → 权限 → 许可 → 在工具线程池上开始执行。
	 * <p>
	 * 校验失败（不存在/超限/无权限/无许可/内部错误）直接返回带失败结果 {@link Execution#finished}；
	 * 可执行的调用返回 {@link Execution#running}，由 {@link #awaitExecution(List, List, int)} 等待完成。
	 * 声明为异步的工具在启动后立即登记异步任务并返回“任务已创建”，不在这里等待，也不阻塞后续工具。
	 * </p>
	 */
	private Execution start(ToolCall call, ToolCallDisplay callDisplay) {
		if (call.function == null) {
			return Execution.finished(notExecuted(call, manager.getMissingToolMessage(), callDisplay));
		}
		ToolRegistration registration = manager.getRegistration(call.function.name);
		if (registration == null) {
			return Execution.finished(notExecuted(call, manager.getMissingToolMessage(), callDisplay));
		}
		ToolData data = registration.getData();
		// 声明为不限次的工具不参与任何次数校验，也不占用总次数上限的额度
		boolean unlimited = manager.isUnlimited(call.function.name);
		if (!unlimited && manager.hasCallLimit() && calls.get() >= manager.getMaxCalls()) {
			callLimitExhausted.set(true);
			return Execution.finished(notExecuted(call, manager.getLimitMessage(), callDisplay));
		}
		int groupId = registration.getGroupId();
		if (!unlimited && groupId > 0 && manager.hasGroupLimit(groupId)) {
			int groupLimit = manager.getGroupMaxCalls(groupId);
			if (getGroupCallCount(groupId) >= groupLimit) {
				callLimitExhausted.set(true);
				return Execution.finished(notExecuted(call, manager.getGroupLimitMessage(), callDisplay));
			}
		}
		// 单个工具的执行次数上限（属于共享组时以组上限为准）
		if (!unlimited && groupId <= 0 && registration.getMaxCalls() >= 0
				&& getToolCallCount(call.function.name) >= registration.getMaxCalls()) {
			callLimitExhausted.set(true);
			return Execution.finished(notExecuted(call, manager.getToolLimitMessage(), callDisplay));
		}

		// 权限由管理器上的全部监听器合并判定（拒绝 > 询问 > 允许 > 忽略，遇拒绝短路）
		ToolCallDecision decision = manager.checkPermission(call.function.name, call.function.arguments);
		if (decision == null) {
			decision = ToolCallDecision.ALLOW;
		}
		if (decision.needsApproval()) {
			ToolCallApprover approver = manager.getApprover();
			// 参数名换成工具声明的人可读名称，审核界面不必直接展示 JSON
			boolean approved = approver != null && approver.approve(call.function.name, call.function.arguments,
					data.formatArguments(call.function.arguments), decision.getReason());
			// 许可原因只用于向许可方说明，回传给模型的是“未获许可”的结果
			decision = approved ? ToolCallDecision.ALLOW : ToolCallDecision.deny(manager.getUnapprovedMessage());
		}
		if (!decision.isAllowed()) {
			return Execution.finished(notExecuted(call, decision.getReasonOr(ToolCallManager.DEFAULT_DENY_MESSAGE), callDisplay));
		}

		if (!unlimited) {
			// 只有受上限约束的调用才占用总次数额度
			calls.incrementAndGet();
		}
		toolCalls.computeIfAbsent(call.function.name, n -> new AtomicInteger()).incrementAndGet();
		if (groupId > 0) {
			groupCalls.computeIfAbsent(groupId, n -> new AtomicInteger()).incrementAndGet();
		}
		if (data.tool == null) {
			return Execution.finished(notExecuted(call, manager.getToolErrorMessage(), callDisplay));
		}
		// 工具本体放到工具线程池上运行：同批调用因此可以并发执行，不再一个个阻塞等待
		CompletableFuture<ToolResult> future;
		try {
			future = CompletableFuture.supplyAsync(() -> {
				try {
					return data.tool.run(call.function.arguments, output, manager);
				} catch (Throwable ex) {
					throw new CompletionException(ex);
				}
			}, TOOL_EXECUTOR).thenCompose(next -> next == null ? CompletableFuture.<ToolResult>completedFuture(null)
					: next);
		} catch (Throwable ex) {
			ex.printStackTrace();
			return Execution.finished(notExecuted(call, manager.getToolErrorMessage(), callDisplay));
		}
		if (registration.isAsync()) {
			ToolTask task = manager.submitTask(call.function.name, call.id, future, callDisplay);
			if (task == null) {
				return Execution.finished(notExecuted(call, manager.getToolErrorMessage(), callDisplay));
			}
			// 异步工具立即返回任务回复，后续顺序敏感工具也不会等待它真正完成
			return Execution.finished(new ToolContent(call.id, manager.asyncReply(task),
					ToolCallDisplay.of(callDisplay.getBrief())));
		}
		return Execution.running(call, callDisplay, future);
	}

	/**
	 * 等待同步工具完成，并把结果转换成回传给模型/history 的工具结果。
	 * <p>
	 * 工具附件在这里交给会话写入当前上下文；等待期间线程被中断、工具执行抛异常
	 * 或返回 null 时统一转成工具错误结果。
	 * </p>
	 *
	 * @param call        原始调用
	 * @param callDisplay 调用显示
	 * @param future      完成信号
	 * @return 工具结果，永不为 null
	 */
	private ToolContent awaitFuture(ToolCall call, ToolCallDisplay callDisplay,
			CompletableFuture<ToolResult> future) {
		try {
			ToolResult result = future == null ? null : future.get();
			if (result == null) {
				return notExecuted(call, manager.getToolErrorMessage(), callDisplay);
			}
			// 工具附件给用户（由会话写入上下文并发送），结果内容只交给模型处理
			manager.publishAttachments(result.getAttachments());
			String resultText = result.getText();
			return new ToolContent(call.id, result.getContent(), buildResultDisplay(call, callDisplay, resultText));
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
