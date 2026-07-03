package com.khjxiaogu.aiwuxia.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.ToolCallCollector;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

/**
 * 模型无关的工具调用管理器。
 * <p>
 * 封装工具调用的收集、执行和流式输出逻辑。
 * 不涉及任何 JSON 或具体协议细节，所有结果通过 {@link ProcessResult} 抽象对象返回，
 * 由各模型提供者自行决定如何写入历史。
 * </p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * ToolCallManager toolManager = new ToolCallManager(request.tools, readable);
 *
 * // 在 SSE 回调中收集工具调用增量
 * if (choice.delta.tool_calls != null) {
 *     for (ToolCall tc : choice.delta.tool_calls) {
 *         toolManager.collect(tc);
 *     }
 * }
 *
 * // 当 finish_reason 为 "tool_calls" 时处理
 * if ("tool_calls".equals(choice.finish_reason)) {
 *     ToolCallManager.ProcessResult result = toolManager.handleToolCalls();
 *     if (result != null) {
 *         for (ToolContent tc : result.getToolResults()) {
 *             ja.add(HistoryRequestBuilder.createToolMessage(tc));
 *         }
 *         shouldContinueRequest.set(true);
 *     }
 * }
 *
 * // 下一轮迭代前重置
 * toolManager.reset();
 * }</pre>
 */
public class ToolCallManager {

    /**
     * 工具调用处理结果。
     * <p>
     * 纯数据对象，不依赖任何 JSON 或协议类型。
     * 提供者根据此结果自行写入历史和输出流。
     * </p>
     */
    public static class ProcessResult {
        private final ToolCallContent toolCallContent;
        private final List<ToolContent> toolResults;

        ProcessResult(ToolCallContent toolCallContent, List<ToolContent> toolResults) {
            this.toolCallContent = toolCallContent;
            this.toolResults = toolResults;
        }

        /**
         * 获取本次工具调用内容（包含所有工具调用请求）。
         */
        public ToolCallContent getToolCallContent() {
            return toolCallContent;
        }

        /**
         * 获取所有工具调用的原始列表。
         */
        public List<ToolCall> getToolCalls() {
            return toolCallContent.getToolCalls();
        }

        /**
         * 获取所有工具执行结果。
         */
        public List<ToolContent> getToolResults() {
            return toolResults;
        }
    }

    private ToolCallCollector collector = new ToolCallCollector();
    private final Map<String, ToolData> tools;
    private final StreamedAIOutput readable;

    /**
     * 构造工具调用管理器。
     *
     * @param tools    可用工具的映射（名称 → ToolData），不可为 null
     * @param readable 流式输出对象，会推送工具调用和结果到读取端
     */
    public ToolCallManager(Map<String, ToolData> tools, StreamedAIOutput readable) {
        this.tools = tools;
        this.readable = readable;
    }

    /**
     * 收集来自流式响应的工具调用增量。
     * <p>
     * 在 SSE 回调中，当 {@code choice.delta.tool_calls} 不为 null 时，
     * 逐个将增量传入此方法，由内部 {@link ToolCallCollector} 拼装完整的工具调用。
     * </p>
     *
     * @param delta 流式响应中的工具调用增量
     */
    public void collect(ToolCall delta) {
        collector.collect(delta);
    }

    /**
     * 处理收集到的工具调用。
     * <p>
     * 执行所有工具，推送结果到流式输出流，返回结构化的处理结果。
     * 调用方根据返回的 {@link ProcessResult} 自行决定如何写入历史。
     * </p>
     *
     * @return 处理结果，如果没有工具调用则返回 null
     */
    public ProcessResult handleToolCalls() {
        List<ToolCall> calls = collector.build();
        if (calls.isEmpty()) {
            return null;
        }

        ToolCallContent toolCallContent = new ToolCallContent(calls);

        // 推送工具调用到输出流
        readable.putReasoner(toolCallContent);

        List<ToolContent> toolResults = new ArrayList<>();

        // 执行每个工具调用
        for (ToolCall tc : toolCallContent.getToolCalls()) {
            ToolData data = tools.get(tc.function.name);
            ToolContent toolResult;
            if (data == null) {
                toolResult = new ToolContent(tc.id, "tool不存在或已禁用。");
            } else {
                try {
                    String result = data.tool.run(tc.function.arguments);
                    toolResult = new ToolContent(tc.id, result);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                    toolResult = new ToolContent(tc.id, "tool发生内部错误。");
                }
            }
            toolResults.add(toolResult);
            readable.putReasoner(toolResult);
        }

        return new ProcessResult(toolCallContent, Collections.unmodifiableList(toolResults));
    }

    /**
     * 重置管理器状态，为下一轮工具调用迭代做准备。
     * <p>
     * 在 while 循环的每次迭代开始时调用，清空已收集的工具调用增量。
     * </p>
     */
    public void reset() {
        collector = new ToolCallCollector();
    }

    /**
     * 获取当前收集器中是否有有效的工具调用。
     *
     * @return true 如果收集器中至少有一个工具调用
     */
    public boolean hasCollectedCalls() {
        return collector.isValid();
    }
}
