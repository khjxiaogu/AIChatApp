package com.khjxiaogu.aiwuxia.doclib;

/**
 * 文档通知的投递出口。
 * <p>
 * 文档库的工具与事件监听者不直接操作会话：需要把文档变更通知送进对话时，由宿主
 * （例如 QQ 连接器）实现本接口，在实现里完成入队、唤醒等会话操作。
 * </p>
 */
@FunctionalInterface
public interface DocNotifySink {

    /**
     * 投递一段通知文本（通常是 {@code <doc-update>} 片段）。
     * <p>实现应立即返回，把入队/唤醒放到自己的线程里；异常由实现自行处理，不要抛回调用方。</p>
     *
     * @param text 通知文本
     */
    void notify(String text);
}
