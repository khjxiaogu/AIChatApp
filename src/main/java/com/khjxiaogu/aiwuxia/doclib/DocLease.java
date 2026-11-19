package com.khjxiaogu.aiwuxia.doclib;

/** 一个文档在某轮次里的写锁。 */
public final class DocLease {
    public final String docId;
    public final String agentId;
    public final String roundId;
    public final long acquiredAt;
    public final long expiresAt;

    public DocLease(String docId, String agentId, String roundId, long acquiredAt, long expiresAt) {
        this.docId = docId;
        this.agentId = agentId;
        this.roundId = roundId;
        this.acquiredAt = acquiredAt;
        this.expiresAt = expiresAt;
    }

    public long heldMs() {
        return Math.max(0, System.currentTimeMillis() - acquiredAt);
    }
}
