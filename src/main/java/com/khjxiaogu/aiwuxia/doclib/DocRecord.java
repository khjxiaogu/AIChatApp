package com.khjxiaogu.aiwuxia.doclib;

/** 文档当前状态（或某个历史修订）的只读快照。 */
public final class DocRecord {
    public final String docId;
    public final String title;
    public final String body;
    public final String hash;
    public final String updatedBy;
    public final String updatedRound;
    public final long revision;
    public final long createdAt;
    public final long updatedAt;
    public final int size;
    public final boolean deleted;

    public DocRecord(String docId, String title, String body, String hash, String updatedBy,
            String updatedRound, long revision, long createdAt, long updatedAt, int size, boolean deleted) {
        this.docId = docId;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.hash = hash == null ? "" : hash;
        this.updatedBy = updatedBy == null ? "" : updatedBy;
        this.updatedRound = updatedRound == null ? "" : updatedRound;
        this.revision = revision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.size = size;
        this.deleted = deleted;
    }
}
