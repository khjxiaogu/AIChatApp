package com.khjxiaogu.aiwuxia.doclib;

import com.google.gson.JsonObject;

/**
 * 本轮暂存的文档修改。一个轮次里同一文档只有一份草稿，多次 patch/append/overwrite/delete 都合并到这里。
 */
public final class DocDraft {
    public final String roundId;
    public final String agentId;
    public final String docId;
    /** 草稿基于的已提交修订；0 表示本轮新建。 */
    public long baseRevision;
    public String baseHash;
    /** 可见正文（不含 meta）。 */
    public String body;
    /** 文件原文（meta + 可见正文）的 SHA-256。 */
    public String hash;
    /** 可见正文的 UTF-8 字节数。 */
    public int size;
    public boolean deleted;
    public String lastOp;
    public String message;
    public String title;
    /** 文件开头的 JSON meta；写入时原样保留，不会因为正文编辑而丢失。 */
    public JsonObject meta;

    public DocDraft(String roundId, String agentId, String docId, long baseRevision, String baseHash,
            String body, String hash, int size, boolean deleted, String lastOp, String message, String title) {
        this(roundId, agentId, docId, baseRevision, baseHash, body, hash, size, deleted, lastOp, message, title, null);
    }

    public DocDraft(String roundId, String agentId, String docId, long baseRevision, String baseHash,
            String body, String hash, int size, boolean deleted, String lastOp, String message, String title,
            JsonObject meta) {
        this.roundId = roundId;
        this.agentId = agentId;
        this.docId = docId;
        this.baseRevision = baseRevision;
        this.baseHash = baseHash;
        this.body = body == null ? "" : body;
        this.hash = hash == null ? "" : hash;
        this.size = size;
        this.deleted = deleted;
        this.lastOp = lastOp == null ? "" : lastOp;
        this.message = message == null ? "" : message;
        this.title = title == null ? "" : title;
        this.meta = meta;
    }

    /** 文件原文（meta + 可见正文）。 */
    public String raw() {
        return DocMeta.join(meta, body);
    }
}
