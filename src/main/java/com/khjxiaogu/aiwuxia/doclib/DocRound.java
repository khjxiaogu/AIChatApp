package com.khjxiaogu.aiwuxia.doclib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 AI 生成回合的文档库上下文：本轮草稿 + 本轮持有的租约。
 * <p>工具批次可能并发，所以这里所有方法都同步；每个文档只有一份草稿。</p>
 */
public final class DocRound {

    public final String id;
    public final String agentId;
    public final long startedAt;
    public final long deadline;

    private final Map<String, DocDraft> drafts = new LinkedHashMap<>();
    private final Set<String> leased = new LinkedHashSet<>();
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private int writes;

    public DocRound(String id, String agentId, long startedAt, long deadline) {
        this.id = id;
        this.agentId = agentId;
        this.startedAt = startedAt;
        this.deadline = deadline;
    }

    public synchronized DocDraft draft(String docId) {
        return drafts.get(docId);
    }

    public synchronized void put(DocDraft draft) {
        drafts.put(draft.docId, draft);
    }

    public synchronized void removeDraft(String docId) {
        drafts.remove(docId);
    }

    public synchronized List<DocDraft> drafts() {
        return new ArrayList<>(drafts.values());
    }

    public synchronized boolean hasDrafts() {
        return !drafts.isEmpty();
    }

    public synchronized int writes() {
        return writes;
    }

    public synchronized void incrementWrites() {
        writes++;
    }

    public synchronized void addLease(String docId) {
        leased.add(docId);
    }

    public synchronized void removeLease(String docId) {
        leased.remove(docId);
    }

    public synchronized List<String> leasedDocs() {
        return new ArrayList<>(leased);
    }

    public long remainingMs() {
        return deadline - System.currentTimeMillis();
    }

    /** @return true 表示本次调用抢到了“收尾权”。 */
    public boolean markFinalized() {
        return finalized.compareAndSet(false, true);
    }

    public boolean isFinalized() {
        return finalized.get();
    }
}
