package com.khjxiaogu.aiwuxia.doclib;

/** 已读版本记录。 */
public final class DocReadTracker {

    private final DocStore store;

    public DocReadTracker(DocStore store) {
        this.store = store;
    }

    public DocSeen get(String agentId, String docId) {
        return store.getSeen(agentId, docId);
    }

    public void markRead(String agentId, String docId, long revision, String hash, boolean complete) {
        store.putSeen(agentId, docId, revision, hash, complete, System.currentTimeMillis());
    }

    public void advance(String agentId, String docId, long revision, String hash) {
        store.putSeen(agentId, docId, revision, hash, true, System.currentTimeMillis());
    }
}
