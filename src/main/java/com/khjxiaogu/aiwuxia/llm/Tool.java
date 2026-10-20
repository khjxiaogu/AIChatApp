package com.khjxiaogu.aiwuxia.llm;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;

/**
 * 工具执行接口。
 * <p>
 * 工具不再在创建时绑定会话：同一份 {@link ToolData} 可以被任意会话、任意一次生成复用。
 * 执行时由调用方传入本次生成对应的上下文：
 * </p>
 * <ul>
 *   <li>{@code output}：本次生成的输出对象，工具产生的用量（如图片上传、搜索、额外模型调用）
 *       通过 {@link AIOutput#addUsage} 写入其中，发送的图片等内容通过 {@link AIOutput#attachContent}
 *       附加，由会话统一处理；</li>
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
     * @return 工具结果文本
     */
    public String run(String params, AIOutput output, ToolCallManager manager);
}
