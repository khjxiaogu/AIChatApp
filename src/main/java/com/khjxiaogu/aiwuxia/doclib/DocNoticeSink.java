package com.khjxiaogu.aiwuxia.doclib;

/**
 * 通知投递通道：notifier 只负责入队，真正的会话消息在轮次开始时由 {@link DocRoundManager#begin} 注入。
 */
public interface DocNoticeSink {

    /** 会话是否正在生成；true 时只入队，不唤醒。 */
    boolean isBusy();

    /** 投递一批通知：只入队 / 必要时唤醒，不推进游标。 */
    void deliver(DocNoticeBatch batch);

    /** 下一轮开始时取走全部待注入通知；没有返回 null。 */
    DocNoticeBatch drain();
}
