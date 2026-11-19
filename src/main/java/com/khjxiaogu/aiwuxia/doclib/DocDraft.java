package com.khjxiaogu.aiwuxia.doclib;

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
    public String body;
    public String hash;
    public int size;
    public boolean deleted;
    public String lastOp;
    public String message;
    public String title;

    public DocDraft(String roundId, String agentId, String docId, long baseRevision, String baseHash,
            String body, String hash, int size, boolean deleted, String lastOp, String message, String title) {
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
    }
}
