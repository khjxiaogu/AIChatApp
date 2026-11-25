package com.khjxiaogu.aiwuxia.doclib;

/**
 * 文档变更事件监听者（通常由 MCP 实现）。
 * <p>
 * doclib 只把原始 {@link DocEventBatch} 交给监听者，不拼通知文本、不注入会话；
 * 是否通知、通知什么、什么时候通知都由监听者决定。处理完后监听者必须调用
 * {@link DocEventBatch#ack()}。
 * </p>
 */
@FunctionalInterface
public interface DocEventListener {

    /**
     * 处理一批文档变更事件。
     *
     * @param batch 原始事件
     */
    void onEvents(DocEventBatch batch);
}
