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
    /** {@code content} 表示正文变更；{@code meta} 表示扩展字段（meta）变更。 */
    public final String kind;
    /** meta 变更的结构化 JSON diff，例如 {@code {"status":{"from":"待创建","to":"已修复"}}}；正文事件为 null。 */
    public final String detail;
    public final boolean recovered;
    public final long createdAt;

    public DocEvent(long seq, String roundId, String docId, long fromRevision, long toRevision,
            String action, String author, String summary, boolean recovered, long createdAt) {
        this(seq, roundId, docId, fromRevision, toRevision, action, author, summary,
                "content", null, recovered, createdAt);
    }

    public DocEvent(long seq, String roundId, String docId, long fromRevision, long toRevision,
            String action, String author, String summary, String kind, String detail,
            boolean recovered, long createdAt) {
        this.seq = seq;
        this.roundId = roundId;
        this.docId = docId;
        this.fromRevision = fromRevision;
        this.toRevision = toRevision;
        this.action = action;
        this.author = author;
        this.summary = summary == null ? "" : summary;
        this.kind = kind == null || kind.isEmpty() ? "content" : kind;
        this.detail = detail == null ? "" : detail;
        this.recovered = recovered;
        this.createdAt = createdAt;
    }

    public boolean isMeta() {
        return "meta".equals(kind);
    }

    /** 是否包含正文变化（content 或 both）。 */
    public boolean hasContent() {
        return "content".equals(kind) || "both".equals(kind);
    }

    /** 是否包含 meta 变化（meta 或 both）。 */
    public boolean hasMeta() {
        return "meta".equals(kind) || "both".equals(kind);
    }
}
