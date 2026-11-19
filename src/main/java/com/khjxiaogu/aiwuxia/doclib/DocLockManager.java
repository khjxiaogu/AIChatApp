package com.khjxiaogu.aiwuxia.doclib;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 文档级写锁（轮次租约）。
 * <p>
 * 写/删第一次碰到某文档时获取，轮次结束统一释放；同一轮重入；其他轮次写同一文档直接失败。
 * 等待解锁通过 {@link #awaitUnlock} 在内存条件变量上等待，最多 10 秒。
 * </p>
 */
public final class DocLockManager {

    private static final long MAX_WAIT_MS = 10000L;

    private final DocStore store;
    private final Map<String, DocLease> leases = new ConcurrentHashMap<>();
    private final Map<String, Object> monitors = new ConcurrentHashMap<>();
    /** 租约过期时的收尾回调：roundId -> forceFinalize。 */
    private volatile Consumer<String> expiryHandler;

    public DocLockManager(DocStore store) {
        this.store = store;
    }

    public void setExpiryHandler(Consumer<String> handler) {
        this.expiryHandler = handler;
    }

    private Object monitor(String docId) {
        return monitors.computeIfAbsent(docId, k -> new Object());
    }

    public DocLease acquire(DocRound round, String docId) {
        Object monitor = monitor(docId);
        synchronized (monitor) {
            for (;;) {
                DocLease cur = leases.get(docId);
                if (cur == null) {
                    DocLease lease = new DocLease(docId, round.agentId, round.id,
                            System.currentTimeMillis(), round.deadline);
                    leases.put(docId, lease);
                    store.upsertLease(lease);
                    round.addLease(docId);
                    return lease;
                }
                if (cur.roundId.equals(round.id)) {
                    round.addLease(docId);
                    return cur;
                }
                if (cur.expiresAt <= System.currentTimeMillis()) {
                    forceExpire(cur);
                    continue;
                }
                throw new DocLockedException(cur);
            }
        }
    }

    private void forceExpire(DocLease lease) {
        Consumer<String> handler = expiryHandler;
        if (handler != null) {
            try {
                handler.accept(lease.roundId);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        synchronized (monitor(lease.docId)) {
            DocLease cur = leases.get(lease.docId);
            if (cur != null && cur.roundId.equals(lease.roundId)) {
                leases.remove(lease.docId);
                store.deleteLease(lease.docId, lease.roundId);
                monitor(lease.docId).notifyAll();
            }
        }
    }

    public void release(DocRound round, String docId) {
        Object monitor = monitor(docId);
        synchronized (monitor) {
            DocLease cur = leases.get(docId);
            if (cur != null && cur.roundId.equals(round.id)) {
                leases.remove(docId);
                store.deleteLease(docId, round.id);
                round.removeLease(docId);
                monitor.notifyAll();
            }
        }
    }

    public void releaseAll(DocRound round) {
        for (String docId : round.leasedDocs()) {
            release(round, docId);
        }
        // 防御：即使 round.leasedDocs 与 leases 表不同步，也按 roundId 清一次
        for (Map.Entry<String, DocLease> e : leases.entrySet()) {
            if (e.getValue().roundId.equals(round.id)) {
                release(round, e.getKey());
            }
        }
    }

    /**
     * 等待文档解锁。
     *
     * @param round     调用方轮次
     * @param docId     文档
     * @param timeoutMs 期望等待毫秒数；上限 10 秒
     * @return 结果；超时返回 unlocked=false
     */
    public DocWaitResult awaitUnlock(DocRound round, String docId, long timeoutMs) {
        long cap = timeoutMs <= 0 ? MAX_WAIT_MS : Math.min(timeoutMs, MAX_WAIT_MS);
        long deadline = System.currentTimeMillis() + Math.min(cap, Math.max(0, round.remainingMs()));
        Object monitor = monitor(docId);
        synchronized (monitor) {
            long waited = 0;
            for (;;) {
                DocLease cur = leases.get(docId);
                if (cur == null || cur.roundId.equals(round.id)) {
                    return new DocWaitResult(true, waited, null);
                }
                if (cur.expiresAt <= System.currentTimeMillis()) {
                    forceExpire(cur);
                    continue;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return new DocWaitResult(false, waited, cur);
                }
                long start = System.currentTimeMillis();
                try {
                    monitor.wait(left);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return new DocWaitResult(false, waited, cur);
                }
                waited += System.currentTimeMillis() - start;
            }
        }
    }

    public DocLease owner(String docId) {
        return leases.get(docId);
    }

    public boolean isLocked(String docId) {
        return leases.containsKey(docId);
    }
}
