package com.khjxiaogu.aiwuxia.doclib;

/** doc_wait_unlock 的结果。 */
public final class DocWaitResult {
    public final boolean unlocked;
    public final long waitedMs;
    /** 超时时仍持有的租约；已解锁时为 null。 */
    public final DocLease lease;

    public DocWaitResult(boolean unlocked, long waitedMs, DocLease lease) {
        this.unlocked = unlocked;
        this.waitedMs = waitedMs;
        this.lease = lease;
    }
}
