package com.khjxiaogu.aiwuxia.doclib;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文档库持久层：SQLite 存元数据/修订索引/已读/租约/outbox，正文以 UTF-8 {@code .txt} 文件存储在
 * {@code documents/} 和 {@code history/<doc>/} 目录里。
 * <p>
 * 当前正文：{@code documents/<safeDocId>.txt}；每个修订另存一份
 * {@code history/<safeDocId>/<revision>.txt}。SQLite 只存文件相对路径、hash、size 等索引信息。
 * </p>
 */
public final class DocStore implements AutoCloseable {

    public static final class SearchHit {
        public final String docId;
        public final String title;
        public final long revision;
        public final String snippet;

        public SearchHit(String docId, String title, long revision, String snippet) {
            this.docId = docId;
            this.title = title == null ? "" : title;
            this.revision = revision;
            this.snippet = snippet == null ? "" : snippet;
        }
    }

    private final File root;
    private final File documentsDir;
    private final File historyDir;
    private final Connection conn;
    private boolean ftsEnabled;

    public DocStore(File root) throws Exception {
        this.root = root;
        this.documentsDir = new File(root, "documents");
        this.historyDir = new File(root, "history");
        if (!documentsDir.exists() && !documentsDir.mkdirs()) {
            throw new IOException("无法创建文档目录：" + documentsDir);
        }
        if (!historyDir.exists() && !historyDir.mkdirs()) {
            throw new IOException("无法创建历史目录：" + historyDir);
        }
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + new File(root, "doclib.db").getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        initSchema();
        repairFiles();
        rebuildFtsIfNeeded();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS documents ("
                    + "doc_id TEXT PRIMARY KEY,"
                    + "title TEXT NOT NULL DEFAULT '',"
                    + "file TEXT NOT NULL,"
                    + "revision INTEGER NOT NULL,"
                    + "hash TEXT NOT NULL,"
                    + "size INTEGER NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL,"
                    + "updated_by TEXT NOT NULL,"
                    + "updated_round TEXT NOT NULL,"
                    + "deleted INTEGER NOT NULL DEFAULT 0"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS revisions ("
                    + "doc_id TEXT NOT NULL,"
                    + "revision INTEGER NOT NULL,"
                    + "file TEXT NOT NULL,"
                    + "hash TEXT NOT NULL,"
                    + "size INTEGER NOT NULL,"
                    + "deleted INTEGER NOT NULL DEFAULT 0,"
                    + "author TEXT NOT NULL,"
                    + "round_id TEXT NOT NULL,"
                    + "message TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "PRIMARY KEY (doc_id, revision)"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS revisions_author ON revisions(author, created_at)");
            st.execute("CREATE TABLE IF NOT EXISTS seen ("
                    + "agent_id TEXT NOT NULL,"
                    + "doc_id TEXT NOT NULL,"
                    + "revision INTEGER NOT NULL,"
                    + "hash TEXT NOT NULL,"
                    + "complete INTEGER NOT NULL DEFAULT 1,"
                    + "seen_at INTEGER NOT NULL,"
                    + "PRIMARY KEY (agent_id, doc_id)"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS leases ("
                    + "doc_id TEXT PRIMARY KEY,"
                    + "agent_id TEXT NOT NULL,"
                    + "round_id TEXT NOT NULL,"
                    + "acquired_at INTEGER NOT NULL,"
                    + "expires_at INTEGER NOT NULL"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS rounds ("
                    + "round_id TEXT PRIMARY KEY,"
                    + "agent_id TEXT NOT NULL,"
                    + "state TEXT NOT NULL,"
                    + "started_at INTEGER NOT NULL,"
                    + "deadline INTEGER NOT NULL,"
                    + "closed_at INTEGER"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS drafts ("
                    + "round_id TEXT NOT NULL,"
                    + "doc_id TEXT NOT NULL,"
                    + "base_revision INTEGER NOT NULL,"
                    + "base_hash TEXT,"
                    + "hash TEXT NOT NULL,"
                    + "size INTEGER NOT NULL,"
                    + "deleted INTEGER NOT NULL DEFAULT 0,"
                    + "last_op TEXT NOT NULL,"
                    + "message TEXT,"
                    + "title TEXT NOT NULL DEFAULT '',"
                    + "body TEXT NOT NULL,"
                    + "agent_id TEXT NOT NULL,"
                    + "PRIMARY KEY (round_id, doc_id)"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS outbox ("
                    + "seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "round_id TEXT NOT NULL,"
                    + "doc_id TEXT NOT NULL,"
                    + "from_revision INTEGER NOT NULL,"
                    + "to_revision INTEGER NOT NULL,"
                    + "action TEXT NOT NULL,"
                    + "author TEXT NOT NULL,"
                    + "summary TEXT,"
                    + "recovered INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL,"
                    + "UNIQUE (round_id, doc_id)"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS listener_cursor ("
                    + "agent_id TEXT PRIMARY KEY,"
                    + "last_seq INTEGER NOT NULL DEFAULT 0"
                    + ")");
            st.execute("DELETE FROM leases");
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5("
                    + "doc_id UNINDEXED, title, body, tokenize='trigram')");
            ftsEnabled = true;
        } catch (SQLException ex) {
            ftsEnabled = false;
            System.out.println("[DocLib] FTS5/trigram 不可用，搜索回退为文件扫描：" + ex.getMessage());
        }
    }

    /**
     * 启动时修复“SQLite 说有修订，但当前 txt 缺失/内容不一致”的情况：从最新历史修订恢复。
     */
    private void repairFiles() {
        String sql = "SELECT doc_id, file, revision, hash, deleted FROM documents";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String docId = rs.getString(1);
                String file = rs.getString(2);
                long revision = rs.getLong(3);
                String hash = rs.getString(4);
                boolean deleted = rs.getInt(5) != 0;
                if (deleted) {
                    deleteQuietly(file);
                    continue;
                }
                Path current = resolve(file);
                String body = readQuietly(current);
                if (body == null || !sha256(body).equals(hash)) {
                    String revisionFile = revisionFile(docId, revision);
                    String restored = readQuietly(resolve(revisionFile));
                    if (restored == null) {
                        // 历史文件也没有：退而求其次，保留现状，日志提示
                        System.out.println("[DocLib] 文档 " + docId + " 的正文文件缺失，且历史修订也不可读");
                        continue;
                    }
                    try {
                        writeAtomic(file, restored);
                    } catch (IOException e) {
                        System.out.println("[DocLib] 修复文档 " + docId + " 失败：" + e.getMessage());
                    }
                }
            }
        } catch (SQLException ex) {
            System.out.println("[DocLib] 检查正文文件失败：" + ex.getMessage());
        }
    }

    /** FTS 行数与文档数不一致时全量重建（例如旧库升级、上次异常退出）。 */
    private void rebuildFtsIfNeeded() {
        if (!ftsEnabled) {
            return;
        }
        try {
            long ftsCount;
            long docCount;
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM documents_fts")) {
                ftsCount = rs.next() ? rs.getLong(1) : 0;
            }
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM documents WHERE deleted=0")) {
                docCount = rs.next() ? rs.getLong(1) : 0;
            }
            if (ftsCount == docCount) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM documents_fts");
            }
            String sql = "SELECT doc_id,title,file FROM documents WHERE deleted=0";
            List<String[]> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
                }
            }
            for (String[] row : rows) {
                String body = readQuietly(resolve(row[2]));
                if (body == null) {
                    continue;
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO documents_fts(doc_id,title,body) VALUES(?,?,?)")) {
                    ins.setString(1, row[0]);
                    ins.setString(2, row[1]);
                    ins.setString(3, body);
                    ins.executeUpdate();
                }
            }
            System.out.println("[DocLib] 已重建 FTS 索引：" + docCount + " 篇");
        } catch (SQLException ex) {
            System.out.println("[DocLib] 重建 FTS 失败：" + ex.getMessage());
        }
    }

    public synchronized DocRecord current(String docId) {
        String sql = "SELECT doc_id, title, file, revision, hash, size, created_at, updated_at,"
                + " updated_by, updated_round, deleted FROM documents WHERE doc_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean deleted = rs.getInt(11) != 0;
                String body = deleted ? "" : readQuietly(resolve(rs.getString(3)));
                if (!deleted && body == null) {
                    // 当前 txt 丢了：先尝试从该修订的历史快照恢复
                    body = readQuietly(resolve(revisionFile(rs.getString(1), rs.getLong(4))));
                    if (body != null) {
                        try {
                            writeAtomic(rs.getString(3), body);
                        } catch (IOException ignored) {
                        }
                    }
                }
                if (!deleted && body == null) {
                    throw new DocException("DOC_INTERNAL",
                            "文档 " + rs.getString(1) + " 的正文 txt 缺失，且历史快照不可读");
                }
                return new DocRecord(rs.getString(1), rs.getString(2), body, rs.getString(5),
                        rs.getString(9), rs.getString(10), rs.getLong(4), rs.getLong(7), rs.getLong(8),
                        rs.getInt(6), deleted);
            }
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "读取文档失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized DocRecord revision(String docId, long revision) {
        String sql = "SELECT r.doc_id, r.revision, r.file, r.hash, r.size, r.deleted, r.author,"
                + " r.round_id, r.message, r.created_at, d.title"
                + " FROM revisions r LEFT JOIN documents d ON d.doc_id=r.doc_id"
                + " WHERE r.doc_id=? AND r.revision=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setLong(2, revision);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean deleted = rs.getInt(6) != 0;
                String body = deleted ? "" : readQuietly(resolve(rs.getString(3)));
                if (body == null) {
                    body = "";
                }
                String title = rs.getString(11) == null ? "" : rs.getString(11);
                return new DocRecord(rs.getString(1), title, body, rs.getString(4), rs.getString(7),
                        rs.getString(8), rs.getLong(2), rs.getLong(10), rs.getLong(10), rs.getInt(5), deleted);
            }
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "读取历史修订失败：" + ex.getMessage(), ex);
        }
    }

    /** 只查元数据，不读正文。 */
    public synchronized List<DocRecord> list(String prefix, boolean includeDeleted, int limit, int offset) {
        String like = escapeLike(prefix == null ? "" : prefix) + "%";
        String sql = "SELECT doc_id, title, file, revision, hash, size, created_at, updated_at,"
                + " updated_by, updated_round, deleted FROM documents"
                + " WHERE (deleted=0 OR ?) AND doc_id LIKE ? ESCAPE '\\'"
                + " ORDER BY updated_at DESC, doc_id ASC LIMIT ? OFFSET ?";
        List<DocRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, includeDeleted ? 1 : 0);
            ps.setString(2, like);
            ps.setInt(3, Math.max(1, limit));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DocRecord(rs.getString(1), rs.getString(2), "", rs.getString(5),
                            rs.getString(9), rs.getString(10), rs.getLong(4), rs.getLong(7), rs.getLong(8),
                            rs.getInt(6), rs.getInt(11) != 0));
                }
            }
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "枚举文档失败：" + ex.getMessage(), ex);
        }
        return out;
    }

    public synchronized List<SearchHit> search(String keyword, int limit, int offset) {
        List<String> terms = splitTerms(keyword);
        if (terms.isEmpty()) {
            return Collections.emptyList();
        }
        boolean needLike = false;
        for (String term : terms) {
            if (term.length() < 3) {
                needLike = true;
                break;
            }
        }
        if (ftsEnabled && !needLike) {
            try {
                return searchFts(terms, limit, offset);
            } catch (SQLException ex) {
                System.out.println("[DocLib] FTS 查询失败，回退文件扫描：" + ex.getMessage());
            }
        }
        return searchFiles(terms, limit, offset);
    }

    private List<SearchHit> searchFts(List<String> terms, int limit, int offset) throws SQLException {
        StringBuilder query = new StringBuilder();
        for (String term : terms) {
            if (query.length() > 0) {
                query.append(" OR ");
            }
            query.append('"').append(term.replace("\"", "\"\"")).append('"');
        }
        String sql = "SELECT d.doc_id, d.title, d.revision,"
                + " snippet(documents_fts, 2, '[', ']', '…', 16) AS snip"
                + " FROM documents_fts JOIN documents d ON d.doc_id=documents_fts.doc_id"
                + " WHERE documents_fts MATCH ? AND d.deleted=0"
                + " ORDER BY bm25(documents_fts) LIMIT ? OFFSET ?";
        List<SearchHit> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query.toString());
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SearchHit(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    /** 中文短词 / FTS 不可用时的回退：扫描当前正文文件。 */
    private List<SearchHit> searchFiles(List<String> terms, int limit, int offset) {
        List<SearchHit> out = new ArrayList<>();
        List<DocRecord> all = list("", false, 10000, 0);
        for (DocRecord doc : all) {
            String body = readQuietly(resolve(fileName(doc.docId)));
            if (body == null) {
                continue;
            }
            String lowerBody = body.toLowerCase();
            String lowerTitle = doc.title.toLowerCase();
            String matched = null;
            for (String term : terms) {
                String t = term.toLowerCase();
                if (lowerTitle.contains(t) || lowerBody.contains(t)) {
                    matched = term;
                    break;
                }
            }
            if (matched != null) {
                int at = lowerBody.indexOf(matched.toLowerCase());
                String snippet = snippet(body, Math.max(0, at), matched.length());
                out.add(new SearchHit(doc.docId, doc.title, doc.revision, snippet));
            }
        }
        int from = Math.min(Math.max(0, offset), out.size());
        int to = Math.min(out.size(), from + Math.max(1, limit));
        return new ArrayList<>(out.subList(from, to));
    }

    /**
     * 提交一个草稿：先写历史/当前 txt，再用一个 SQLite 事务更新索引、outbox 和 seen。
     * 每个文档一个事务；调用方保证租约仍然有效。
     */
    public synchronized long commitDraft(DocDraft d) {
        boolean oldAuto;
        try {
            oldAuto = conn.getAutoCommit();
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "读取事务状态失败：" + ex.getMessage(), ex);
        }
        DocRecord cur = current(d.docId);
        // 本轮新建后又删除：撤销草稿，不产生文档/修订
        if (d.baseRevision == 0 && d.deleted) {
            try {
                conn.setAutoCommit(true);
                deleteDraftRow(d.roundId, d.docId);
            } catch (SQLException ex) {
                throw new DocException("DOC_INTERNAL", "撤销草稿失败：" + ex.getMessage(), ex);
            }
            return 0;
        }
        long to;
        String action;
        String oldBody = cur == null ? "" : cur.body;
        if (d.baseRevision == 0) {
            if (cur != null) {
                if (cur.revision == 1 && cur.hash.equals(d.hash) && cur.deleted == d.deleted) {
                    to = 1;
                    action = d.deleted ? "deleted" : "created";
                } else {
                    throw new DocException("DOC_CONFLICT", "文档已被其他轮次创建：" + d.docId);
                }
            } else {
                to = 1;
                action = d.deleted ? "deleted" : "created";
            }
        } else {
            if (cur == null) {
                throw new DocException("DOC_CONFLICT", "文档已被删除：" + d.docId);
            }
            if (cur.revision == d.baseRevision + 1 && cur.hash.equals(d.hash) && cur.deleted == d.deleted) {
                to = cur.revision; // 幂等：恢复时可能已经提交过
                action = d.deleted ? "deleted" : "updated";
            } else if (cur.revision != d.baseRevision) {
                throw new DocException("DOC_CONFLICT",
                        "文档 " + d.docId + " 的当前修订是 " + cur.revision + "，草稿基于 " + d.baseRevision);
            } else {
                to = d.baseRevision + 1;
                action = d.deleted ? "deleted" : "updated";
            }
        }
        boolean already = cur != null && cur.revision == to && cur.hash.equals(d.hash) && cur.deleted == d.deleted;
        String currentFile = fileName(d.docId);
        String historyFile = revisionFile(d.docId, to);
        try {
            if (!already) {
                // 先写历史快照（不可变），再写当前正文；DB 失败时下面会回滚并恢复当前文件
                writeAtomic(historyFile, d.body);
                if (d.deleted) {
                    // 当前文件在 DB 成功后再删
                } else {
                    writeAtomic(currentFile, d.body);
                }
            } else if (!d.deleted && !Files.isRegularFile(resolve(currentFile))) {
                // 幂等恢复：DB 已经提交但当前 txt 丢了，补写一份
                writeAtomic(currentFile, d.body);
            }
        } catch (IOException ex) {
            throw new DocException("DOC_INTERNAL", "写入正文文件失败：" + ex.getMessage(), ex);
        }
        try {
            conn.setAutoCommit(false);
            long now = System.currentTimeMillis();
            if (d.baseRevision == 0) {
                if (!already) {
                    insertDocument(d, currentFile, to, now);
                }
            } else if (!already) {
                updateDocument(d, currentFile, to, now);
            }
            if (!already) {
                insertRevision(d, historyFile, to, now);
                updateFts(d, to);
                insertOutbox(d, action, oldBody, d.body, to, false);
            } else {
                // 幂等恢复：确保 outbox 和 seen 也在
                insertOutbox(d, action, oldBody, d.body, to, true);
            }
            putSeen(d.agentId, d.docId, to, d.hash, true, now);
            deleteDraftRow(d.roundId, d.docId);
            conn.commit();
            if (d.deleted) {
                deleteQuietly(currentFile);
            }
            return to;
        } catch (SQLException ex) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            restoreCurrent(d.docId, cur);
            throw new DocException("DOC_INTERNAL", "提交文档失败：" + ex.getMessage(), ex);
        } finally {
            try {
                conn.setAutoCommit(oldAuto);
            } catch (SQLException ignored) {
            }
        }
    }

    private void insertDocument(DocDraft d, String file, long revision, long now) throws SQLException {
        String sql = "INSERT INTO documents(doc_id,title,file,revision,hash,size,created_at,updated_at,"
                + "updated_by,updated_round,deleted) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.docId);
            ps.setString(2, d.title);
            ps.setString(3, file);
            ps.setLong(4, revision);
            ps.setString(5, d.hash);
            ps.setInt(6, d.size);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.setString(9, d.agentId);
            ps.setString(10, d.roundId);
            ps.setInt(11, d.deleted ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void updateDocument(DocDraft d, String file, long revision, long now) throws SQLException {
        String sql = "UPDATE documents SET title=?, file=?, revision=?, hash=?, size=?, updated_at=?,"
                + " updated_by=?, updated_round=?, deleted=? WHERE doc_id=? AND revision=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.title);
            ps.setString(2, file);
            ps.setLong(3, revision);
            ps.setString(4, d.hash);
            ps.setInt(5, d.size);
            ps.setLong(6, now);
            ps.setString(7, d.agentId);
            ps.setString(8, d.roundId);
            ps.setInt(9, d.deleted ? 1 : 0);
            ps.setString(10, d.docId);
            ps.setLong(11, d.baseRevision);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("CAS 失败，文档修订已变化");
            }
        }
    }

    private void insertRevision(DocDraft d, String file, long revision, long now) throws SQLException {
        String sql = "INSERT INTO revisions(doc_id,revision,file,hash,size,deleted,author,round_id,message,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.docId);
            ps.setLong(2, revision);
            ps.setString(3, file);
            ps.setString(4, d.hash);
            ps.setInt(5, d.size);
            ps.setInt(6, d.deleted ? 1 : 0);
            ps.setString(7, d.agentId);
            ps.setString(8, d.roundId);
            ps.setString(9, d.message);
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }

    private void updateFts(DocDraft d, long revision) {
        if (!ftsEnabled) {
            return;
        }
        try {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM documents_fts WHERE doc_id=?")) {
                del.setString(1, d.docId);
                del.executeUpdate();
            }
            if (!d.deleted) {
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO documents_fts(doc_id,title,body) VALUES(?,?,?)")) {
                    ins.setString(1, d.docId);
                    ins.setString(2, d.title);
                    ins.setString(3, d.body);
                    ins.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            System.out.println("[DocLib] 更新 FTS 失败：" + ex.getMessage());
        }
    }

    private void insertOutbox(DocDraft d, String action, String oldBody, String newBody,
            long toRevision, boolean recovered) throws SQLException {
        String sql = "INSERT OR IGNORE INTO outbox(round_id,doc_id,from_revision,to_revision,action,author,"
                + "summary,recovered,created_at) VALUES(?,?,?,?,?,?,?,?,?)";
        String summary;
        if (d.deleted) {
            summary = "deleted";
        } else if (d.baseRevision == 0) {
            summary = "+" + countLines(newBody);
        } else {
            summary = diffSummary(oldBody, newBody);
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.roundId);
            ps.setString(2, d.docId);
            ps.setLong(3, d.baseRevision);
            ps.setLong(4, toRevision);
            ps.setString(5, action);
            ps.setString(6, d.agentId);
            ps.setString(7, summary);
            ps.setInt(8, recovered ? 1 : 0);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized void trimRevisions(String docId, int retain) {
        if (retain <= 0) {
            return;
        }
        String sql = "SELECT revision, file FROM revisions WHERE doc_id=? ORDER BY revision DESC";
        List<long[]> revs = new ArrayList<>();
        List<String> files = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    revs.add(new long[] { rs.getLong(1) });
                    files.add(rs.getString(2));
                }
            }
        } catch (SQLException ex) {
            return;
        }
        if (revs.size() <= retain) {
            return;
        }
        for (int i = retain; i < revs.size(); i++) {
            long revision = revs.get(i)[0];
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM revisions WHERE doc_id=? AND revision=?")) {
                del.setString(1, docId);
                del.setLong(2, revision);
                del.executeUpdate();
            } catch (SQLException ex) {
                return;
            }
            // 历史文件保留（doc_read(revision=N) 仍可读）；如果希望节省磁盘，可在这里删除。
        }
    }

    public synchronized DocSeen getSeen(String agentId, String docId) {
        String sql = "SELECT revision,hash,complete,seen_at FROM seen WHERE agent_id=? AND doc_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new DocSeen(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0, rs.getLong(4));
            }
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "读取已读记录失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized void putSeen(String agentId, String docId, long revision, String hash,
            boolean complete, long seenAt) {
        String sql = "INSERT OR REPLACE INTO seen(agent_id,doc_id,revision,hash,complete,seen_at)"
                + " VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, docId);
            ps.setLong(3, revision);
            ps.setString(4, hash);
            ps.setInt(5, complete ? 1 : 0);
            ps.setLong(6, seenAt);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "写入已读记录失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized void upsertDraft(DocDraft d) {
        String sql = "INSERT OR REPLACE INTO drafts(round_id,doc_id,base_revision,base_hash,hash,size,deleted,"
                + "last_op,message,title,body,agent_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.roundId);
            ps.setString(2, d.docId);
            ps.setLong(3, d.baseRevision);
            ps.setString(4, d.baseHash);
            ps.setString(5, d.hash);
            ps.setInt(6, d.size);
            ps.setInt(7, d.deleted ? 1 : 0);
            ps.setString(8, d.lastOp);
            ps.setString(9, d.message);
            ps.setString(10, d.title);
            ps.setString(11, d.body);
            ps.setString(12, d.agentId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "保存草稿失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized void deleteDraft(String roundId, String docId) {
        deleteDraftRow(roundId, docId);
    }

    private void deleteDraftRow(String roundId, String docId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM drafts WHERE round_id=? AND doc_id=?")) {
            ps.setString(1, roundId);
            ps.setString(2, docId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.out.println("[DocLib] 删除草稿失败：" + ex.getMessage());
        }
    }

    public synchronized List<DocDraft> draftsForRound(String roundId) {
        String sql = "SELECT doc_id,base_revision,base_hash,hash,size,deleted,last_op,message,title,body,agent_id"
                + " FROM drafts WHERE round_id=? ORDER BY doc_id";
        List<DocDraft> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DocDraft(roundId, rs.getString(11), rs.getString(1), rs.getLong(2),
                            rs.getString(3), rs.getString(10), rs.getString(4), rs.getInt(5),
                            rs.getInt(6) != 0, rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "读取草稿失败：" + ex.getMessage(), ex);
        }
        return out;
    }

    public synchronized void createRound(String roundId, String agentId, long startedAt, long deadline) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO rounds(round_id,agent_id,state,started_at,deadline,closed_at)"
                        + " VALUES(?,?,'OPEN',?,?,NULL)")) {
            ps.setString(1, roundId);
            ps.setString(2, agentId);
            ps.setLong(3, startedAt);
            ps.setLong(4, deadline);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DocException("DOC_INTERNAL", "创建轮次失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized void updateRoundState(String roundId, String state) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE rounds SET state=?, closed_at=? WHERE round_id=?")) {
            ps.setString(1, state);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, roundId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.out.println("[DocLib] 更新轮次状态失败：" + ex.getMessage());
        }
    }

    /** 恢复用：返回 [roundId, agentId, startedAt]。 */
    public synchronized List<String[]> openRounds() {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT round_id,agent_id,started_at FROM rounds WHERE state IN ('OPEN','COMMITTING')")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] { rs.getString(1), rs.getString(2), Long.toString(rs.getLong(3)) });
                }
            }
        } catch (SQLException ex) {
            System.out.println("[DocLib] 读取待恢复轮次失败：" + ex.getMessage());
        }
        return out;
    }

    public synchronized void upsertLease(DocLease lease) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO leases(doc_id,agent_id,round_id,acquired_at,expires_at)"
                        + " VALUES(?,?,?,?,?)")) {
            ps.setString(1, lease.docId);
            ps.setString(2, lease.agentId);
            ps.setString(3, lease.roundId);
            ps.setLong(4, lease.acquiredAt);
            ps.setLong(5, lease.expiresAt);
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.out.println("[DocLib] 记录租约失败：" + ex.getMessage());
        }
    }

    public synchronized void deleteLease(String docId, String roundId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM leases WHERE doc_id=? AND round_id=?")) {
            ps.setString(1, docId);
            ps.setString(2, roundId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.out.println("[DocLib] 删除租约失败：" + ex.getMessage());
        }
    }

    public synchronized List<DocEvent> outboxAfter(long seq, int limit) {
        String sql = "SELECT seq,round_id,doc_id,from_revision,to_revision,action,author,summary,recovered,created_at"
                + " FROM outbox WHERE seq>? ORDER BY seq LIMIT ?";
        List<DocEvent> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, seq);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DocEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getInt(9) != 0, rs.getLong(10)));
                }
            }
        } catch (SQLException ex) {
            System.out.println("[DocLib] 读取 outbox 失败：" + ex.getMessage());
        }
        return out;
    }

    public synchronized long getCursor(String agentId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_seq FROM listener_cursor WHERE agent_id=?")) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            return 0L;
        }
    }

    public synchronized void setCursor(String agentId, long seq) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO listener_cursor(agent_id,last_seq) VALUES(?,?)")) {
            ps.setString(1, agentId);
            ps.setLong(2, seq);
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.out.println("[DocLib] 保存通知游标失败：" + ex.getMessage());
        }
    }

    /** 文档 id -> 当前 txt 相对路径（扁平目录，安全化 + 短 hash 防撞）。 */
    public static String fileName(String docId) {
        return "documents/" + baseName(docId) + ".txt";
    }

    /** 文档 id + 修订 -> 历史 txt 相对路径。 */
    public static String revisionFile(String docId, long revision) {
        return "history/" + baseName(docId) + "/" + revision + ".txt";
    }

    private static String baseName(String docId) {
        String base = docId == null ? "doc" : docId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        if (base.isEmpty()) {
            base = "doc";
        }
        String key = docId == null ? "" : docId;
        return base + "-" + sha256(key).substring(0, 8);
    }

    private Path resolve(String relative) {
        return root.toPath().resolve(relative).normalize();
    }

    private void writeAtomic(String relative, String body) throws IOException {
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".txt");
        Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readQuietly(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    private void deleteQuietly(String relative) {
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (IOException ignored) {
        }
    }

    private void restoreCurrent(String docId, DocRecord old) {
        try {
            String currentFile = fileName(docId);
            if (old == null || old.deleted) {
                deleteQuietly(currentFile);
                return;
            }
            String history = revisionFile(docId, old.revision);
            String body = readQuietly(resolve(history));
            if (body != null) {
                writeAtomic(currentFile, body);
            }
        } catch (IOException ex) {
            System.out.println("[DocLib] 恢复当前正文失败：" + ex.getMessage());
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new DocException("DOC_INTERNAL", "计算 hash 失败：" + ex.getMessage(), ex);
        }
    }

    public static int utf8Size(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String deriveTitle(String body) {
        if (body == null) {
            return "";
        }
        String[] lines = body.split("\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                trimmed = trimmed.replaceFirst("^#+\\s*", "");
            }
            return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
        }
        return "";
    }

    private static List<String> splitTerms(String keyword) {
        List<String> out = new ArrayList<>();
        if (keyword == null) {
            return out;
        }
        for (String part : keyword.split("\\|")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String snippet(String body, int index, int length) {
        if (body.isEmpty()) {
            return "";
        }
        int start = Math.max(0, index - 40);
        int end = Math.min(body.length(), index + Math.max(1, length) + 60);
        String s = body.substring(start, end).replace('\n', ' ').replace('\r', ' ');
        return (start > 0 ? "…" : "") + s + (end < body.length() ? "…" : "");
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 简单的行级 LCS 统计；对超大文本只给粗略值。 */
    private static String diffSummary(String oldText, String newText) {
        String[] a = oldText == null ? new String[0] : oldText.split("\\n", -1);
        String[] b = newText == null ? new String[0] : newText.split("\\n", -1);
        if (a.length > 1200 || b.length > 1200) {
            return "+" + b.length + "/-" + a.length;
        }
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        int common = dp[a.length][b.length];
        return "+" + (b.length - common) + "/-" + (a.length - common);
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
