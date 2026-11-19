package com.khjxiaogu.aiwuxia.doclib;

/** 某个 agent 对某个文档的“已完整读过”记录。 */
public final class DocSeen {
    public final long revision;
    public final String hash;
    public final boolean complete;
    public final long seenAt;

    public DocSeen(long revision, String hash, boolean complete, long seenAt) {
        this.revision = revision;
        this.hash = hash == null ? "" : hash;
        this.complete = complete;
        this.seenAt = seenAt;
    }
}
