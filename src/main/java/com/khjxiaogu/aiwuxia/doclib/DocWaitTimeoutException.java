package com.khjxiaogu.aiwuxia.doclib;

/** 等待文档解锁超时。 */
public final class DocWaitTimeoutException extends DocException {

    private static final long serialVersionUID = 1L;

    public final DocLease lease;
    public final long waitedMs;

    public DocWaitTimeoutException(DocLease lease, long waitedMs) {
        super("DOC_WAIT_TIMEOUT", "等待文档 " + lease.docId + " 解锁超时（已等待 " + waitedMs + "ms）");
        this.lease = lease;
        this.waitedMs = waitedMs;
    }
}
