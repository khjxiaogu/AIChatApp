package com.khjxiaogu.aiwuxia.llm;

import java.util.concurrent.CompletableFuture;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;

/**
 * 工具执行接口。
 * <p>
 * 所有工具统一返回 {@link CompletableFuture}：同步工具可以直接返回已完成的结果，
 * 长耗时工具可以返回真正异步的 future。是否在完成前就向模型返回“任务已创建”，
 * 由注册时声明的异步模式决定，工具本身不需要关心任务管理。
 * </p>
 * <p>
 * 工具不再绑定会话：同一份 {@link ToolData} 可以被任意会话、任意一次生成复用。
 * 执行时由调用方传入本次生成对应的上下文：
 * </p>
 * <ul>
 *   <li>{@code output}：本次生成的输出对象，工具产生的用量通过 {@link AIOutput#addUsage}
 *       写入其中；工具结果中的附件由调用方（{@code ToolCallManager} / 会话）处理；</li>
 *   <li>{@code manager}：本次生成使用的工具调用管理器，工具可以读取会话上下文
 *       或调整自身的调用规则。</li>
 * </ul>
 */
@FunctionalInterface
public interface Tool {

	/**
	 * 执行工具。
	 *
	 * @param params  模型给出的参数（JSON 文本）
	 * @param output  本次生成的输出对象，可为 null（离线调用时）
	 * @param manager 本次生成的工具调用管理器，可为 null（离线调用时）
	 * @return 工具结果的 future，永不为 null
	 */
	CompletableFuture<ToolResult> run(String params, AIOutput output, ToolCallManager manager);
}
