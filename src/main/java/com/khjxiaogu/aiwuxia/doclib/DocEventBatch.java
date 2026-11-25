package com.khjxiaogu.aiwuxia.doclib;

import java.util.Collections;
import java.util.List;

/**
 * 一批原始文档变更事件，交给 MCP/扩展决定是否通知以及通知文本。
 * <p>
 * doclib 不再把事件直接注入 agent：它只负责从 outbox 取出 {@link DocEvent} 并调用监听者。
 * 监听者处理完（包括决定“不通知”）后必须调用 {@link #ack()}，否则事件会在下一次 flush 时重试。
 * </p>
 */
public final class DocEventBatch {

    private final String libraryId;
    private final List<DocEvent> events;
    private final long lastSeq;
    private final Runnable ack;
    private final java.util.concurrent.atomic.AtomicBoolean acked = new java.util.concurrent.atomic.AtomicBoolean();

    public DocEventBatch(String libraryId, List<DocEvent> events, long lastSeq, Runnable ack) {
        this.libraryId = libraryId == null ? "" : libraryId;
        this.events = events == null ? Collections.<DocEvent>emptyList() : events;
        this.lastSeq = lastSeq;
        this.ack = ack;
    }

    public String getLibraryId() {
        return libraryId;
    }

    public List<DocEvent> getEvents() {
        return events;
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    public long getLastSeq() {
        return lastSeq;
    }

    /** 处理完毕（包括主动忽略）后调用；重复调用无副作用。 */
    public void ack() {
        if (acked.compareAndSet(false, true) && ack != null) {
            ack.run();
        }
    }

    public boolean isAcked() {
        return acked.get();
    }
}
