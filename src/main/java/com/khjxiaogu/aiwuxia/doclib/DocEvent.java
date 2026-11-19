package com.khjxiaogu.aiwuxia.doclib;

/** outbox 里的一条文档变更事件。 */
public final class DocEvent {
    public final long seq;
    public final String roundId;
    public final String docId;
    public final long fromRevision;
    public final long toRevision;
    public final String action;
    public final String author;
    public final String summary;
    public final boolean recovered;
    public final long createdAt;

    public DocEvent(long seq, String roundId, String docId, long fromRevision, long toRevision,
            String action, String author, String summary, boolean recovered, long createdAt) {
        this.seq = seq;
        this.roundId = roundId;
        this.docId = docId;
        this.fromRevision = fromRevision;
        this.toRevision = toRevision;
        this.action = action;
        this.author = author;
        this.summary = summary == null ? "" : summary;
        this.recovered = recovered;
        this.createdAt = createdAt;
    }
}
