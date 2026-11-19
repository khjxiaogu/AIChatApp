package com.khjxiaogu.aiwuxia.doclib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档变更通知：从 outbox 读取事件，按监听者过滤、合并，再交给 {@link DocNoticeSink}。
 * <p>
 * 关键规则：作者本人永远收不到自己改动的通知；通知不更新 {@code seen}；
 * 游标在 {@link DocRoundManager#begin} 真正把通知注入上下文后才推进。
 * </p>
 */
public final class DocNotifier {

    private final DocLibraryRuntime runtime;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final Map<String, Long> inflight = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public DocNotifier(DocLibraryRuntime runtime) {
        this.runtime = runtime;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "doclib-notifier-" + runtime.id());
            t.setDaemon(true);
            return t;
        });
    }

    /** 提交后调用：batchMs 后统一 flush。 */
    public void publish() {
        if (closed || !scheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(0, runtime.config().notifyBatchMs);
        try {
            scheduler.schedule(() -> {
                scheduled.set(false);
                flush();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            scheduled.set(false);
        }
    }

    /** 新 agent 注册后补发积压。 */
    public void flushSoon() {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(this::flush);
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized void flush() {
        if (closed) {
            return;
        }
        List<DocAgent> agents = new ArrayList<>(runtime.agents());
        for (DocAgent agent : agents) {
            if (agent.sink == null || agent.listen.isEmpty() || !agent.config.isListenerAllowed(agent.id)) {
                continue;
            }
            try {
                flushAgent(agent);
            } catch (Throwable t) {
                System.out.println("[DocLib] 投递通知失败：" + agent.id + " " + t.getMessage());
            }
        }
    }

    private void flushAgent(DocAgent agent) {
        long cursor = Math.max(runtime.store().getCursor(agent.id),
                inflight.containsKey(agent.id) ? inflight.get(agent.id) : 0L);
        List<DocEvent> rows = runtime.store().outboxAfter(cursor, 200);
        if (rows.isEmpty()) {
            return;
        }
        DocNoticeBatch batch = new DocNoticeBatch();
        batch.setLibraryId(runtime.id());
        batch.setWake(agent.wakeOnNotify && !agent.sink.isBusy());
        long lastSeq = cursor;
        int maxDocs = runtime.config().maxDocsPerNotice;
        for (DocEvent row : rows) {
            if (batch.size() >= maxDocs) {
                // 剩余事件留到下一次 flush，不能把 lastSeq 推过未处理的记录
                break;
            }
            // 自己改的永远不通知自己
            if (row.author.equals(agent.id)) {
                lastSeq = Math.max(lastSeq, row.seq);
                continue;
            }
            if (!agent.listens(row.docId)) {
                lastSeq = Math.max(lastSeq, row.seq);
                continue;
            }
            // “解锁之后统一发”：文档还锁着（通常是另一个轮次刚提交、还没来得及 releaseAll）就
            // 先不发，也不推进游标；等释放后的 publish 再发。
            if (runtime.locks().isLocked(row.docId)) {
                break;
            }
            lastSeq = Math.max(lastSeq, row.seq);
            DocSeen seen = runtime.reads().get(agent.id, row.docId);
            if (seen != null && seen.revision >= row.toRevision) {
                continue;
            }
            batch.add(new DocNoticeBatch.Item(row.docId, row.action, row.toRevision,
                    row.fromRevision, displayName(row.author), row.summary), row.seq);
        }
        if (batch.isEmpty()) {
            ack(agent.id, lastSeq);
            return;
        }
        inflight.put(agent.id, lastSeq);
        try {
            agent.sink.deliver(batch);
        } catch (Throwable t) {
            inflight.remove(agent.id);
            throw t;
        }
    }

    /** 通知真正注入会话上下文后调用。 */
    public void ack(String agentId, long seq) {
        runtime.store().setCursor(agentId, seq);
        Long current = inflight.get(agentId);
        if (current == null || current <= seq) {
            inflight.remove(agentId);
        }
    }

    private String displayName(String agentId) {
        DocAgent agent = runtime.agent(agentId);
        return agent == null ? agentId : agent.displayName;
    }

    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
