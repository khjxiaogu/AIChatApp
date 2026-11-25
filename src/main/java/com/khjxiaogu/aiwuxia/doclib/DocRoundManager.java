package com.khjxiaogu.aiwuxia.doclib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.khjxiaogu.aiwuxia.state.session.AISession;

/**
 * 轮次生命周期：开始、暂存、提交、解锁、通知、崩溃恢复。
 * <p>在任何结束路径上都会提交草稿并释放租约；没有 discard 模式。</p>
 */
public final class DocRoundManager {

    private final DocLibraryRuntime runtime;
    private final DocStore store;
    private final DocLockManager locks;
    private final Map<String, DocRound> active = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ScheduledExecutorService sweeper;

    public DocRoundManager(DocLibraryRuntime runtime, DocStore store, DocLockManager locks) {
        this.runtime = runtime;
        this.store = store;
        this.locks = locks;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "doclib-sweeper-" + runtime.id());
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, 15, 15, TimeUnit.SECONDS);
    }

    public DocRound begin(DocAgent agent) {
        return begin(agent.id);
    }

    public DocRound begin(String agentId) {
        DocRound previous = active.get(agentId);
        if (previous != null) {
            end(agentId, AISession.RoundEndReason.TIMEOUT);
        }
        long now = System.currentTimeMillis();
        String roundId = agentId + "#" + now + "-" + sequence.incrementAndGet();
        long deadline = now + runtime.config().maxRoundMs;
        DocRound round = new DocRound(roundId, agentId, now, deadline);
        store.createRound(roundId, agentId, now, deadline);
        active.put(agentId, round);
        return round;
    }

    public void end(DocAgent agent, AISession.RoundEndReason reason) {
        end(agent.id, reason);
    }

    public void end(String agentId, AISession.RoundEndReason reason) {
        DocRound round = active.remove(agentId);
        if (round != null) {
            finalize(round, reason);
        }
    }

    /** 租约过期/管理员强制收尾。 */
    public void forceFinalize(String roundId, AISession.RoundEndReason reason) {
        DocRound found = null;
        String agentId = null;
        for (Map.Entry<String, DocRound> entry : active.entrySet()) {
            if (entry.getValue().id.equals(roundId)) {
                found = entry.getValue();
                agentId = entry.getKey();
                break;
            }
        }
        if (found != null) {
            active.remove(agentId, found);
            finalize(found, reason);
        }
    }

    public DocRound current(String agentId) {
        return active.get(agentId);
    }

    private void finalize(DocRound round, AISession.RoundEndReason reason) {
        if (!round.markFinalized()) {
            return;
        }
        store.updateRoundState(round.id, "COMMITTING");
        boolean failed = false;
        List<DocDraft> drafts = round.drafts();
        Collections.sort(drafts, Comparator.comparing(d -> d.docId));
        for (DocDraft draft : drafts) {
            try {
                store.commitDraft(draft);
                store.trimRevisions(draft.docId, runtime.config().retainRevisions);
            } catch (Throwable t) {
                failed = true;
                System.out.println("[DocLib] 提交草稿失败 round=" + round.id + " doc=" + draft.docId
                        + "：" + t.getMessage());
            }
        }
        store.updateRoundState(round.id, failed ? "FAILED" : "CLOSED");
        try {
            locks.releaseAll(round);
        } finally {
            // 解锁之后才发通知；通知失败不影响解锁
            runtime.notifier().publish();
        }
        System.out.println("[DocLib] round closed id=" + round.id + " reason=" + reason
                + " docs=" + drafts.size() + " failed=" + failed);
    }

    /** 启动恢复：所有 OPEN/COMMITTING 都提交并解锁。 */
    public void recover() {
        for (String[] row : store.openRounds()) {
            String roundId = row[0];
            String agentId = row[1];
            long startedAt = parseLong(row[2]);
            DocRound round = new DocRound(roundId, agentId, startedAt,
                    startedAt + runtime.config().maxRoundMs);
            for (DocDraft draft : store.draftsForRound(roundId)) {
                round.put(draft);
            }
            finalize(round, AISession.RoundEndReason.CLOSED);
        }
    }

    private void sweep() {
        try {
            for (DocRound round : new ArrayList<>(active.values())) {
                if (System.currentTimeMillis() >= round.deadline) {
                    active.remove(round.agentId, round);
                    finalize(round, AISession.RoundEndReason.TIMEOUT);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public void close() {
        sweeper.shutdownNow();
        for (DocRound round : new ArrayList<>(active.values())) {
            active.remove(round.agentId, round);
            finalize(round, AISession.RoundEndReason.CLOSED);
        }
    }
}
