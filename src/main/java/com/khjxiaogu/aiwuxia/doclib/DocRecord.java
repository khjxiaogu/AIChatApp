package com.khjxiaogu.aiwuxia.doclib;

import com.google.gson.JsonObject;

/** 文档当前状态（或某个历史修订）的只读快照。 */
public final class DocRecord {
    public final String docId;
    public final String title;
    /** 可见正文；文件开头的 JSON meta 已被剥离。 */
    public final String body;
    /** 文件原文（meta + 可见正文）的 SHA-256；用于已读/冲突判断。 */
    public final String hash;
    public final String updatedBy;
    public final String updatedRound;
    public final long revision;
    public final long createdAt;
    public final long updatedAt;
    /** 可见正文的 UTF-8 字节数（不含 meta）。 */
    public final int size;
    public final boolean deleted;
    /**
     * 文件开头的 JSON meta；没有 meta 时为 null。
     * <p>文档查询工具不会暴露本字段，仅供基于文档系统的扩展（如工单系统）使用。</p>
     */
    public final JsonObject meta;

    public DocRecord(String docId, String title, String body, String hash, String updatedBy,
            String updatedRound, long revision, long createdAt, long updatedAt, int size, boolean deleted) {
        this(docId, title, body, hash, updatedBy, updatedRound, revision, createdAt, updatedAt, size, deleted, null);
    }

    public DocRecord(String docId, String title, String body, String hash, String updatedBy,
            String updatedRound, long revision, long createdAt, long updatedAt, int size, boolean deleted,
            JsonObject meta) {
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
        this.meta = meta;
    }
}
