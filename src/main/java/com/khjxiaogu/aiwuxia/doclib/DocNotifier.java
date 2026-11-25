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
 * 文档变更事件：从 outbox 读取原始事件，按监听者过滤后交给 {@link DocEventListener}。
 * <p>
 * doclib 不再拼通知文本或直接注入 agent；是否通知、通知什么由 MCP 监听者决定。
 * 关键规则：作者本人永远收不到自己改动的通知；通知不更新 {@code seen}；
 * 游标在监听者调用 {@link DocEventBatch#ack()} 后才推进。
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
        // 会话关闭后不再向它投递：文档库不再注册会话关闭监听器，这里按执行器状态清理
        runtime.pruneClosedAgents();
        List<DocAgent> agents = new ArrayList<>(runtime.agents());
        for (DocAgent agent : agents) {
            if (agent.listener == null || agent.listen.isEmpty() || !agent.config.isListenerAllowed(agent.id)) {
                continue;
            }
            try {
                flushAgent(agent);
            } catch (Throwable t) {
                System.out.println("[DocLib] 投递事件失败：" + agent.id + " " + t.getMessage());
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
        List<DocEvent> events = new ArrayList<>();
        long lastSeq = cursor;
        int maxDocs = runtime.config().maxDocsPerNotice;
        for (DocEvent row : rows) {
            if (events.size() >= maxDocs) {
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
            // 纯正文事件：已经读过该 revision 就不必再提示。
            // 含 meta 的事件例外：doc_read 看不到 meta，不能用“已读 revision”推断它已经知道字段变化。
            if (!row.hasMeta() && seen != null && seen.revision >= row.toRevision) {
                continue;
            }
            events.add(row);
        }
        if (events.isEmpty()) {
            ack(agent.id, lastSeq);
            return;
        }
        inflight.put(agent.id, lastSeq);
        final long ackSeq = lastSeq;
        DocEventBatch batch = new DocEventBatch(runtime.id(), events, ackSeq,
                () -> ack(agent.id, ackSeq));
        try {
            // doclib 只交原始事件；是否通知、通知文本由 MCP 监听者决定
            agent.listener.onEvents(batch);
        } catch (Throwable t) {
            inflight.remove(agent.id);
            throw t;
        }
    }

    /** 监听者处理完事件（包括主动忽略）后调用。 */
    public void ack(String agentId, long seq) {
        runtime.store().setCursor(agentId, seq);
        Long current = inflight.get(agentId);
        if (current == null || current <= seq) {
            inflight.remove(agentId);
        }
    }

    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
