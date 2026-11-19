package com.khjxiaogu.aiwuxia.doclib;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.state.session.AISession;

/**
 * 一个库 id 对应的运行时：存储、锁、已读、轮次、通知、agent 注册。
 * <p>同 id 的 agent 共享同一个实例，因此共享同一份文档、锁和通知游标。</p>
 */
public final class DocLibraryRuntime implements AutoCloseable {

    public static final class StageResult {
        public final DocDraft draft;
        public final boolean noop;
        public final boolean canceled;

        private StageResult(DocDraft draft, boolean noop, boolean canceled) {
            this.draft = draft;
            this.noop = noop;
            this.canceled = canceled;
        }

        static StageResult staged(DocDraft draft) {
            return new StageResult(draft, false, false);
        }

        static StageResult noop(DocDraft draft) {
            return new StageResult(draft, true, false);
        }

        static StageResult canceled() {
            return new StageResult(null, false, true);
        }
    }

    public static final class ReadResult {
        public final DocRecord record;
        public final boolean draft;
        public final boolean complete;
        public final boolean truncated;

        ReadResult(DocRecord record, boolean draft, boolean complete, boolean truncated) {
            this.record = record;
            this.draft = draft;
            this.complete = complete;
            this.truncated = truncated;
        }
    }

    public static final class WaitInfo {
        public final DocWaitResult result;
        public final DocRecord current;
        public final boolean stale;
        public final boolean exists;

        WaitInfo(DocWaitResult result, DocRecord current, boolean stale) {
            this.result = result;
            this.current = current;
            this.exists = current != null;
            this.stale = stale;
        }
    }

    private final String id;
    private final DocLibraryConfig config;
    private final File root;
    private final DocStore store;
    private final DocLockManager locks;
    private final DocReadTracker reads;
    private final DocNotifier notifier;
    private final DocRoundManager rounds;
    private final Map<String, DocAgent> agents = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    public DocLibraryRuntime(File dataFolder, DocLibraryConfig config) throws Exception {
        this.id = config.id;
        this.config = config;
        this.root = config.root(dataFolder);
        this.store = new DocStore(root);
        this.locks = new DocLockManager(store);
        this.reads = new DocReadTracker(store);
        this.notifier = new DocNotifier(this);
        this.rounds = new DocRoundManager(this, store, locks);
        this.locks.setExpiryHandler(roundId ->
                rounds.forceFinalize(roundId, AISession.RoundEndReason.TIMEOUT));
        this.rounds.recover();
    }

    public String id() {
        return id;
    }

    public DocLibraryConfig config() {
        return config;
    }

    public File root() {
        return root;
    }

    public DocStore store() {
        return store;
    }

    public DocLockManager locks() {
        return locks;
    }

    public DocReadTracker reads() {
        return reads;
    }

    public DocNotifier notifier() {
        return notifier;
    }

    public DocRoundManager rounds() {
        return rounds;
    }

    public DocAgent agent(String agentId) {
        return agents.get(agentId);
    }

    public Collection<DocAgent> agents() {
        return new ArrayList<>(agents.values());
    }

    /**
     * 注册一个会话作为文档库 agent。
     *
     * @param session 会话
     * @param agentConfig 该会话的 doclib 配置
     * @param wake 通知唤醒回调（通常调用连接器的 submitMessage）
     * @return agent
     */
    public DocAgent register(final AIGroupSession session, DocLibraryConfig agentConfig, Runnable wake) {
        final String agentId = "qq:" + session.botId + ":" + session.groupId;
        DocAgent existing = agents.get(agentId);
        if (existing != null && existing.session == session) {
            return existing;
        }
        final String displayName = session.getRoleName(Role.ASSISTANT);
        final DocNoticeSink sink = new Sink(session, wake, agentId);
        final DocAgent agent = new DocAgent(agentId, displayName, agentConfig.listen,
                agentConfig.wakeOnNotify, session, sink, agentConfig);
        agents.put(agentId, agent);
        session.addLifecycleListener(new AISession.SessionLifecycleListener() {
            @Override
            public void onBeforePrompt(AISession s) {
                DocNoticeBatch batch = sink.drain();
                if (batch != null && !batch.isEmpty()) {
                    injectNotice(agent, batch);
                }
            }

            @Override
            public void onRoundStart(AISession s) {
                rounds.begin(agent);
            }

            @Override
            public void onRoundEnd(AISession s, AISession.RoundEndReason reason) {
                rounds.end(agent, reason);
            }

            @Override
            public void onSessionClosed(AISession s) {
                unregister(agent);
            }
        });
        notifier.flushSoon();
        System.out.println("[DocLib] agent 已注册：" + agentId + "（" + displayName + "）库=" + id
                + " listen=" + agentConfig.listen + " wake=" + agentConfig.wakeOnNotify
                + " write=" + agentConfig.write);
        return agent;
    }

    private void unregister(DocAgent agent) {
        agents.remove(agent.id, agent);
    }

    /** 把通知注入 agent 的下一轮上下文，并在注入后才推进 outbox 游标。 */
    void injectNotice(DocAgent agent, DocNoticeBatch batch) {
        if (agent == null || batch == null || batch.isEmpty() || agent.session == null) {
            return;
        }
        final String text = batch.toPromptText();
        java.util.List<java.util.function.Supplier<com.khjxiaogu.aiwuxia.state.history.message.MessageContent>> content =
                new ArrayList<>();
        content.add(() -> new com.khjxiaogu.aiwuxia.state.history.message.PlainText(text));
        agent.session.addMessage(content, true);
        notifier.ack(agent.id, batch.lastSeq());
    }

    /**
     * 暂存写入。返回的草稿在本轮结束时提交；期间其他 agent 不能写同一文档。
     */
    public StageResult stageWrite(String agentId, String docId, String mode, String content,
            String title, Long baseRevision, String message) {
        validateDocId(docId);
        DocRound round = requireRound(agentId);
        if (round.writes() >= config.maxWritesPerRound) {
            throw new DocException("DOC_RATE_LIMITED",
                    "本轮写操作已达上限 " + config.maxWritesPerRound + "，请下一轮继续");
        }
        String op = mode == null ? "overwrite" : mode.trim().toLowerCase();
        if (!"overwrite".equals(op) && !"append".equals(op) && !"patch".equals(op)) {
            throw new DocException("DOC_BAD_REQUEST", "mode 只能是 overwrite / append / patch");
        }
        String value = content == null ? "" : content;
        locks.acquire(round, docId);
        if (round.isFinalized()) {
            locks.release(round, docId);
            throw new DocException("DOC_ROUND_CLOSED", "本轮已结束，写入未生效；请下一轮重试");
        }
        DocRecord cur;
        try {
            cur = store.current(docId);
        } catch (RuntimeException ex) {
            locks.release(round, docId);
            throw ex;
        }
        DocDraft draft = round.draft(docId);
        try {
            checkSeen(agentId, docId, cur, draft, baseRevision);
        } catch (RuntimeException ex) {
            if (draft == null) {
                locks.release(round, docId);
            }
            throw ex;
        }
        if (cur != null && cur.deleted && ("append".equals(op) || "patch".equals(op))) {
            if (draft == null) {
                locks.release(round, docId);
            }
            throw new DocException("DOC_NOT_FOUND",
                    "文档 " + docId + " 已删除；只能用 overwrite 重建");
        }
        if ("patch".equals(op) && cur == null && draft == null) {
            locks.release(round, docId);
            throw new DocException("DOC_NOT_FOUND", "文档不存在，patch 无法应用：" + docId);
        }
        String oldText;
        if (draft != null) {
            oldText = draft.body;
        } else if (cur == null || cur.deleted) {
            oldText = "";
        } else {
            oldText = cur.body;
        }
        String newText;
        try {
            if ("overwrite".equals(op)) {
                newText = value;
            } else if ("append".equals(op)) {
                newText = appendText(oldText, value);
            } else {
                newText = PatchApplier.apply(oldText, value);
            }
        } catch (IllegalArgumentException ex) {
            if (draft == null) {
                locks.release(round, docId);
            }
            throw new DocException("DOC_PATCH_FAILED", ex.getMessage());
        }
        if (newText.length() > config.maxDocChars) {
            if (draft == null) {
                locks.release(round, docId);
            }
            throw new DocException("DOC_TOO_LARGE",
                    "文档 " + docId + " 超过 " + config.maxDocChars + " 字符上限");
        }
        if (newText.equals(oldText) && (draft == null || !draft.deleted)) {
            if (draft == null) {
                locks.release(round, docId);
            }
            return StageResult.noop(draft);
        }
        String newTitle = resolveTitle(title, draft, cur, newText, op);
        String hash = DocStore.sha256(newText);
        int size = DocStore.utf8Size(newText);
        if (draft == null) {
            draft = new DocDraft(round.id, agentId, docId,
                    cur == null ? 0 : cur.revision,
                    cur == null ? null : cur.hash,
                    newText, hash, size, false, op, message, newTitle);
            round.put(draft);
        } else {
            draft.body = newText;
            draft.hash = hash;
            draft.size = size;
            draft.deleted = false;
            draft.lastOp = op;
            draft.message = message == null ? "" : message;
            draft.title = newTitle;
        }
        store.upsertDraft(draft);
        round.incrementWrites();
        System.out.println("[DocLib] stage " + docId + " mode=" + op + " base=" + draft.baseRevision
                + " chars=" + newText.length() + " agent=" + agentId);
        return StageResult.staged(draft);
    }

    /** 暂存删除。 */
    public StageResult stageDelete(String agentId, String docId, String message) {
        validateDocId(docId);
        DocRound round = requireRound(agentId);
        if (round.writes() >= config.maxWritesPerRound) {
            throw new DocException("DOC_RATE_LIMITED",
                    "本轮写操作已达上限 " + config.maxWritesPerRound + "，请下一轮继续");
        }
        locks.acquire(round, docId);
        if (round.isFinalized()) {
            locks.release(round, docId);
            throw new DocException("DOC_ROUND_CLOSED", "本轮已结束，删除未生效；请下一轮重试");
        }
        DocRecord cur;
        try {
            cur = store.current(docId);
        } catch (RuntimeException ex) {
            locks.release(round, docId);
            throw ex;
        }
        DocDraft draft = round.draft(docId);
        try {
            checkSeen(agentId, docId, cur, draft, null);
        } catch (RuntimeException ex) {
            if (draft == null) {
                locks.release(round, docId);
            }
            throw ex;
        }
        if (cur == null && draft == null) {
            locks.release(round, docId);
            throw new DocException("DOC_NOT_FOUND", "文档不存在：" + docId);
        }
        if (cur != null && cur.deleted && draft == null) {
            locks.release(round, docId);
            return StageResult.noop(null);
        }
        // 本轮刚创建、还没提交就删除：撤销草稿，不产生 tombstone
        if (draft != null && draft.baseRevision == 0) {
            round.removeDraft(docId);
            store.deleteDraft(round.id, docId);
            locks.release(round, docId);
            round.incrementWrites();
            return StageResult.canceled();
        }
        if (draft != null && draft.deleted) {
            return StageResult.noop(draft);
        }
        String title = draft != null ? draft.title : (cur == null ? "" : cur.title);
        long baseRevision = draft != null ? draft.baseRevision : (cur == null ? 0 : cur.revision);
        String baseHash = draft != null ? draft.baseHash : (cur == null ? null : cur.hash);
        if (draft == null) {
            draft = new DocDraft(round.id, agentId, docId, baseRevision, baseHash,
                    "", DocStore.sha256(""), 0, true, "delete", message, title);
            round.put(draft);
        } else {
            draft.body = "";
            draft.hash = DocStore.sha256("");
            draft.size = 0;
            draft.deleted = true;
            draft.lastOp = "delete";
            draft.message = message == null ? "" : message;
        }
        store.upsertDraft(draft);
        round.incrementWrites();
        System.out.println("[DocLib] stage delete " + docId + " base=" + draft.baseRevision
                + " agent=" + agentId);
        return StageResult.staged(draft);
    }

    public ReadResult read(String agentId, String docId, Long revision, int maxChars) {
        validateDocId(docId);
        int cap = maxChars <= 0 ? config.maxDocChars : Math.min(maxChars, config.maxDocChars);
        DocRound round = rounds.current(agentId);
        DocDraft draft = round == null ? null : round.draft(docId);
        if (revision == null && draft != null) {
            DocRecord rec = new DocRecord(docId, draft.title, draft.body, draft.hash,
                    agentId, draft.roundId, draft.baseRevision, System.currentTimeMillis(),
                    System.currentTimeMillis(), draft.size, draft.deleted);
            return new ReadResult(rec, true, true, false);
        }
        DocRecord rec = revision == null ? store.current(docId) : store.revision(docId, revision);
        if (rec == null) {
            throw new DocException("DOC_NOT_FOUND", "文档不存在：" + docId);
        }
        boolean truncated = rec.body.length() > cap;
        String body = truncated ? rec.body.substring(0, cap) : rec.body;
        DocRecord view = new DocRecord(rec.docId, rec.title, body, rec.hash, rec.updatedBy,
                rec.updatedRound, rec.revision, rec.createdAt, rec.updatedAt, rec.size, rec.deleted);
        boolean complete = !truncated;
        if (revision == null) {
            reads.markRead(agentId, docId, rec.revision, rec.hash, complete);
        }
        return new ReadResult(view, false, complete, truncated);
    }

    public List<DocRecord> list(String prefix, boolean includeDeleted, int limit, int offset) {
        return store.list(prefix, includeDeleted, limit, offset);
    }

    public List<DocStore.SearchHit> search(String keyword, int limit, int offset) {
        return store.search(keyword, limit, offset);
    }

    public WaitInfo waitUnlock(String agentId, String docId, long timeoutMs) {
        validateDocId(docId);
        DocRound round = requireRound(agentId);
        DocWaitResult result = locks.awaitUnlock(round, docId, timeoutMs);
        DocRecord cur = store.current(docId);
        DocSeen seen = reads.get(agentId, docId);
        boolean stale = cur != null && (seen == null || seen.revision != cur.revision
                || !seen.hash.equals(cur.hash));
        return new WaitInfo(result, cur, stale);
    }

    public boolean isStale(String agentId, DocRecord record) {
        if (record == null) {
            return false;
        }
        DocSeen seen = reads.get(agentId, record.docId);
        return seen == null || seen.revision != record.revision || !seen.hash.equals(record.hash);
    }

    private DocRound requireRound(String agentId) {
        DocRound round = rounds.current(agentId);
        if (round == null) {
            throw new DocException("DOC_ROUND_CLOSED",
                    "当前轮次已结束；本轮写入已提交并解锁，请下一轮继续");
        }
        return round;
    }

    private void checkSeen(String agentId, String docId, DocRecord cur, DocDraft draft, Long baseRevision) {
        if (draft != null || cur == null) {
            return;
        }
        DocSeen seen = reads.get(agentId, docId);
        if (seen == null || seen.revision != cur.revision || !seen.hash.equals(cur.hash)) {
            throw stale(agentId, docId, cur, seen);
        }
        if (!seen.complete) {
            throw new DocException("DOC_READ_INCOMPLETE",
                    "你上次只读取了 " + docId + " 的一部分；请完整 doc_read 后再写");
        }
        if (baseRevision != null && baseRevision.longValue() != cur.revision) {
            throw stale(agentId, docId, cur, seen);
        }
    }

    private DocException stale(String agentId, String docId, DocRecord cur, DocSeen seen) {
        String seenText = seen == null ? "从未完整读取" : ("revision " + seen.revision);
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(cur.updatedAt));
        return new DocException("DOC_STALE",
                "文档 " + docId + " 当前是 revision " + cur.revision + "（由 " + cur.updatedBy
                        + " 于 " + when + " 提交）；你上次完整读取的是 " + seenText
                        + "。请先 doc_read(doc_id=\"" + docId + "\") 再写。");
    }

    private static String appendText(String oldText, String add) {
        if (oldText.isEmpty()) {
            return add;
        }
        if (oldText.endsWith("\n") || add.startsWith("\n")) {
            return oldText + add;
        }
        return oldText + "\n" + add;
    }

    private static String resolveTitle(String requested, DocDraft draft, DocRecord cur,
            String newText, String mode) {
        if (requested != null && !requested.trim().isEmpty()) {
            return requested.trim();
        }
        if (draft != null && !draft.title.isEmpty()) {
            return draft.title;
        }
        if (cur != null && !cur.title.isEmpty() && !"overwrite".equals(mode)) {
            return cur.title;
        }
        return DocStore.deriveTitle(newText);
    }

    private static void validateDocId(String docId) {
        if (docId == null || docId.trim().isEmpty()) {
            throw new DocException("DOC_BAD_REQUEST", "doc_id 不能为空");
        }
        if (docId.length() > 128) {
            throw new DocException("DOC_BAD_REQUEST", "doc_id 过长（最多 128 字符）");
        }
        if (docId.startsWith("/") || docId.endsWith("/") || docId.contains("..")) {
            throw new DocException("DOC_BAD_REQUEST", "doc_id 不能以 / 开头/结尾，也不能包含 ..");
        }
        if (!docId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new DocException("DOC_BAD_REQUEST",
                    "doc_id 只能使用字母、数字、._:/-，且以字母/数字开头");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        rounds.close();
        notifier.close();
        store.close();
    }

    /** 每个 agent 一个的通知队列；真正注入在轮次开始时。 */
    private final class Sink implements DocNoticeSink {
        private final AIGroupSession session;
        private final Runnable wake;
        private final String agentId;
        private final Queue<DocNoticeBatch> queue = new ConcurrentLinkedQueue<>();

        Sink(AIGroupSession session, Runnable wake, String agentId) {
            this.session = session;
            this.wake = wake;
            this.agentId = agentId;
        }

        @Override
        public boolean isBusy() {
            return session != null && session.isGenerating();
        }

        @Override
        public void deliver(DocNoticeBatch batch) {
            if (batch == null || batch.isEmpty()) {
                return;
            }
            queue.add(batch);
            if (batch.isWake() && !isBusy() && wake != null && session != null) {
                try {
                    // 必须在 getPrompt() 之前把通知放进消息队列，否则 submitMessage 拿到的 prompt 不含它。
                    session.getCommandExec().submit(() -> {
                        if (session.isGenerating()) {
                            return; // 已有轮次会通过 begin() 自己 drain
                        }
                        DocNoticeBatch pending = drain();
                        if (pending == null || pending.isEmpty()) {
                            return;
                        }
                        DocAgent agent = agents.get(agentId);
                        if (agent == null) {
                            return;
                        }
                        injectNotice(agent, pending);
                        try {
                            wake.run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        @Override
        public DocNoticeBatch drain() {
            List<DocNoticeBatch> all = new ArrayList<>();
            for (DocNoticeBatch batch; (batch = queue.poll()) != null; ) {
                all.add(batch);
            }
            if (all.isEmpty()) {
                return null;
            }
            DocNoticeBatch merged = DocNoticeBatch.merge(all);
            merged.setLibraryId(id);
            return merged;
        }
    }
}
