package com.khjxiaogu.aiwuxia.doclib;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext;
import com.khjxiaogu.aiwuxia.state.session.AISession;

/**
 * 文档库挂在工具调用上下文上的本轮数据对象。
 * <p>
 * 它既是数据对象（工具可以随时读 {@link #round()}、{@link #ended()} 与本轮草稿），
 * 也是本轮状态监听者：首次放进上下文时开始轮次，上下文结束时提交草稿、解锁并发通知。
 * 文档库因此不再维护自己的监听器表，轮次生命周期完全由工具调用上下文驱动。
 * </p>
 */
public final class DocRoundBinding implements ToolCallContext.RoundState {

    public final DocLibraryRuntime runtime;
    public final DocAgent agent;
    /** 本轮的文档库上下文（草稿 + 租约）；本轮未开始或已结束时为 null。 */
    private volatile DocRound round;
    private volatile boolean ended;

    DocRoundBinding(DocLibraryRuntime runtime, DocAgent agent) {
        this.runtime = runtime;
        this.agent = agent;
    }

    /** 本轮文档库上下文；本轮还没开始或已经结束时返回 null。 */
    public DocRound round() {
        return round;
    }

    /** 本轮是否已经结束。 */
    public boolean ended() {
        return ended;
    }

    /** 本轮持有写锁的文档数量（诊断用）。 */
    public int leasedDocCount() {
        DocRound current = round;
        return current == null ? 0 : current.leasedDocs().size();
    }

    @Override
    public void onRoundStart() {
        round = runtime.rounds().begin(agent);
    }

    @Override
    public void onRoundEnd(ToolCallContext.RoundEndReason reason) {
        ended = true;
        try {
            runtime.rounds().end(agent, toRoundEndReason(reason));
        } finally {
            round = null;
        }
    }

    /** 工具调用上下文的结束原因 → 文档库轮次结束原因（只影响日志/恢复标记）。 */
    static AISession.RoundEndReason toRoundEndReason(ToolCallContext.RoundEndReason reason) {
        if (reason == null) {
            return AISession.RoundEndReason.NORMAL;
        }
        switch (reason) {
        case ERROR:
            return AISession.RoundEndReason.ERROR;
        case CLOSED:
            return AISession.RoundEndReason.CLOSED;
        case INTERRUPTED:
        case NORMAL:
        default:
            // 用户主动中断时已产生的写入照常提交，按正常结束处理
            return AISession.RoundEndReason.NORMAL;
        }
    }
}
