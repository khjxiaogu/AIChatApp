package com.khjxiaogu.aiwuxia.llm;

import java.util.concurrent.CompletableFuture;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext;

/**
 * 工具执行接口。
 * <p>
 * 所有工具统一返回 {@link CompletableFuture}：同步工具可以直接返回已完成的结果，
 * 长耗时工具可以返回真正异步的 future。是否在完成前就向模型返回“任务已创建”，
 * 由注册时声明的异步模式决定，工具本身不需要关心任务管理。
 * </p>
 * <p>
 * 工具不再绑定会话：同一份 {@link ToolData} 可以被任意会话、任意一次生成复用。
 * 执行时由调用方传入本次生成对应的 {@link ToolCallContext}：
 * </p>
 * <ul>
 *   <li>{@code context}：本次生成的工具调用上下文，工具可以从中取
 *       {@link ToolCallContext#getManager()}（调用规则、任务提交、附件发布）；
 *       输出对象仍是本次的 {@code output} 参数，工具产生的用量写进其中；
 *       工具结果的“结果内容”只提供给模型进一步处理，“附件”才返回给用户，
 *       两者由调用方（{@code ToolCallManager} / 会话）分别处理；</li>
 *   <li>需要按轮次提交或收集状态的工具，把本轮数据对象放进上下文
 *       （{@link ToolCallContext#computeRoundState(String, java.util.function.Supplier)}），
 *       由上下文在轮次结束时回调，不需要线程本地变量。</li>
 * </ul>
 * <p>
 * 服务端直调（例如 {@code tools/call}）没有生成过程，调用方可以传 {@code null}，
 * 也可以给一次独立调用新建一个上下文并在结束时调用 {@link ToolCallContext#endRound}。
 * </p>
 */
@FunctionalInterface
public interface Tool {

	/**
	 * 执行工具。
	 *
	 * @param params  模型给出的参数（JSON 文本）
	 * @param output  本次生成的输出对象，可为 null（离线调用时）
	 * @param context 本次生成的工具调用上下文，可为 null（没有生成过程的直调）
	 * @return 工具结果的 future，永不为 null
	 */
	CompletableFuture<ToolResult> run(String params, AIOutput output, ToolCallContext context);
}
