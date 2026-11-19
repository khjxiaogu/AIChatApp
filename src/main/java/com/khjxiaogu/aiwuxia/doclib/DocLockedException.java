package com.khjxiaogu.aiwuxia.doclib;

/** 文档已被其他轮次锁定。 */
public final class DocLockedException extends DocException {

    private static final long serialVersionUID = 1L;

    public final DocLease lease;

    public DocLockedException(DocLease lease) {
        super("DOC_LOCKED", "文档 " + lease.docId + " 正被 " + lease.agentId + " 写入，锁定至其本轮结束");
        this.lease = lease;
    }
}
