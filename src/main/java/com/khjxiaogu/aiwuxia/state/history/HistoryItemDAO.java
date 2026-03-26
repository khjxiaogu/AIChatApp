package com.khjxiaogu.aiwuxia.state.history;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * 历史条目数据访问对象，负责 HistoryItem 的持久化操作。
 * 使用 SQLite 数据库，支持懒加载、即时更新（特定字段）和批量写入（flush）。
 */
public class HistoryItemDAO {
    private final Connection connection;
    private final List<HistoryItem> pendingWrites = new ArrayList<>();
    private final Gson gson=new Gson();
    /**
     * 构造 DAO，需提供已打开的 SQLite 连接。
     */
    public HistoryItemDAO(Connection connection) {
        this.connection = connection;
        initTable();
    }

    /**
     * 初始化表结构，如果不存在则创建。
     */
    private void initTable() {
        String sql = "CREATE TABLE IF NOT EXISTS history_item ("
        		+ "identifier INTEGER PRIMARY KEY AUTOINCREMENT,"
        		+ "prev_identifier INTEGER NOT NULL DEFAULT -1,"
        		+ "role TEXT NOT NULL,"
        		+ "display_content TEXT,"
        		+ "context_content TEXT,"
        		+ "reason_content TEXT,"
        		+ "should_send INTEGER NOT NULL DEFAULT 1,"
        		+ "deleted INTEGER NOT NULL DEFAULT 0,"
        		+ "audio_id TEXT,"
        		+ "last_state TEXT)";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("初始化表失败", e);
        }
    }

    /**
     * 根据标识符读取历史条目。
     * 返回的条目支持懒加载（reasonContent 和 lastState），
     * 且对 shouldSend、deleted、audioId 的修改会立即写入数据库。
     */
    public HistoryItem getById(int identifier) {
        String sql = "SELECT identifier, prev_identifier, "
        		+ "role, display_content, context_content, "
        		+ "should_send, deleted, audio_id FROM history_item WHERE identifier = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, identifier);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt("identifier");
                int prevId = rs.getInt("prev_identifier");
                String roleStr = rs.getString("role");
                
                Role role = Role.valueOf(roleStr); // 假设 Role 是枚举
                String display = rs.getString("display_content");
                String context = rs.getString("context_content");
                boolean shouldSend = rs.getInt("should_send") == 1;
                boolean deleted = rs.getInt("deleted") == 1;
                String audioId = rs.getString("audio_id");

                // 构造一个内存对象，填充已加载字段
                HistoryMemoryItem delegate = new HistoryMemoryItem(
                        id, role, display, shouldSend);
                delegate.setContextContent(context);
                delegate.setPrevIdentifier(prevId);
                delegate.setAudioId(audioId);
                delegate.setDeleted(deleted);
                // 注意：reasonContent 和 lastState 未加载，使用懒加载
                return new PersistentHistoryItem(delegate, this);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("读取条目失败", e);
        }
    }

    /**
     * 创建空白新条目，直接在数据库中插入记录并返回 HistoryMemoryItem 实例。
     * 调用者填充完毕后需调用 addToPendingWrite() 和 flush() 完成持久化。
     */
    public HistoryMemoryItem createNewItem(Role role, String content, boolean shouldSend) {
        String sql = "INSERT INTO history_item "
        		+ "(role, display_content, should_send)"
        		+ "VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, role.name());
            stmt.setString(2, content);
            stmt.setInt(3, shouldSend ? 1 : 0);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            if (keys.next()) {
                int identifier = keys.getInt(1);
                // 使用现有构造函数创建 HistoryMemoryItem，并返回
                return new HistoryMemoryItem(identifier, role, content, shouldSend);
            } else {
                throw new SQLException("插入失败，无法获取自增主键");
            }
        } catch (SQLException e) {
            throw new RuntimeException("创建新条目失败", e);
        }
    }

    /**
     * 将条目加入待写入列表，稍后通过 flush() 批量更新。
     */
    public void addToPendingWrite(HistoryItem item) {
        if (item != null) {
            pendingWrites.add(item);
        }
    }

    /**
     * 批量写入待写入列表中的所有条目，更新其所有字段（除主键外）。
     */
    public void flush() {
        if (pendingWrites.isEmpty()) return;
        String sql = "UPDATE history_item SET prev_identifier = ?, role = ?,  display_content = ?, context_content = ?, reason_content = ?, should_send = ?, deleted = ?, audio_id = ?, last_state = ? WHERE identifier = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (HistoryItem item : pendingWrites) {
                int identifier = item.getIdentifier();
                String role = item.getRole().name();
                String display = item.getDisplayContent() != null ? item.getDisplayContent().toString() : null;
                String context = item.getContextContent() != null ? item.getContextContent().toString() : null;
                String reason = item.getReasoningContent(); // 可能为 null 或空
                boolean shouldSend = item.isValidContext();
                boolean deleted = item.isDeleted();
                String audioId = item.getAudioId();
                String lastStateJson = null;
                if (item.getLastState() != null) {
                    lastStateJson = gson.toJson(item.getLastState()); // 假设有工具类
                }

                stmt.setInt(1, item.getPrevIdentifier());
                stmt.setString(2, role);
                stmt.setString(3, display);
                stmt.setString(4, context);
                stmt.setString(5, reason);
                stmt.setInt(6, shouldSend ? 1 : 0);
                stmt.setInt(7, deleted ? 1 : 0);
                stmt.setString(8, audioId);
                stmt.setString(9, lastStateJson);
                stmt.setInt(10, identifier);
                stmt.addBatch();
            }
            stmt.executeBatch();
            connection.commit();
            pendingWrites.clear();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            throw new RuntimeException("批量写入失败", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 即时更新条目的单个字段（用于 shouldSend、deleted、audioId）。
     * 由 PersistentHistoryItem 在 setter 中调用。
     */
    void updateField(int identifier, String fieldName, Object value) {
        String sql = "UPDATE history_item SET " + fieldName + " = ? WHERE identifier = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            switch (fieldName) {
                case "should_send":
                    stmt.setInt(1, (Boolean) value ? 1 : 0);
                    break;
                case "deleted":
                    stmt.setInt(1, (Boolean) value ? 1 : 0);
                    break;
                case "audio_id":
                    stmt.setString(1, (String) value);
                    break;
                default:
                    throw new IllegalArgumentException("不支持的字段: " + fieldName);
            }
            stmt.setInt(2, identifier);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("即时更新字段失败", e);
        }
    }

    /**
     * 懒加载 reasonContent 字段。
     */
    String loadReasonContent(int identifier) {
        String sql = "SELECT reason_content FROM history_item WHERE identifier = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, identifier);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("reason_content");
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("加载 reasonContent 失败", e);
        }
    }

    /**
     * 懒加载 lastState 字段（JSON 反序列化）。
     */
    ApplicationState loadLastState(int identifier) {
        String sql = "SELECT last_state FROM history_item WHERE identifier = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, identifier);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String json = rs.getString("last_state");
                if (json != null && !json.isEmpty()) {
                    return gson.fromJson(json, ApplicationState.class);
                }
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("加载 lastState 失败", e);
        }
    }

    /**
     * 内部实现类，用于包装从数据库读取的条目。
     * 支持懒加载 reasonContent 和 lastState，且对 shouldSend、deleted、audioId 的修改立即入库。
     */
    private static class PersistentHistoryItem implements HistoryItem {
        private final HistoryMemoryItem delegate;
        private final HistoryItemDAO dao;
        private boolean reasonContentLoaded = false;
        private boolean lastStateLoaded = false;
        private String reasonContentCache;
        private ApplicationState lastStateCache;

        PersistentHistoryItem(HistoryMemoryItem delegate, HistoryItemDAO dao) {
            this.delegate = delegate;
            this.dao = dao;
        }

        // 所有 getter/setter 委托给 delegate，部分 setter 需额外处理即时更新和懒加载

        @Override
        public CharSequence getContextContent() {
            return delegate.getContextContent();
        }

        @Override
        public CharSequence getDisplayContent() {
            return delegate.getDisplayContent();
        }

        @Override
        public void appendLine(String content, boolean addToContext) {
            delegate.appendLine(content, addToContext);
        }

        @Override
        public void append(String content, boolean addToContext) {
            delegate.append(content, addToContext);
        }

        @Override
        public void appendContext(String content) {
            delegate.appendContext(content);
        }

        @Override
        public void setContextContent(String fullContent) {
            delegate.setContextContent(fullContent);
        }

        @Override
        public void appendReasoner(String fullContent) {
            delegate.appendReasoner(fullContent);
            // 如果已经加载过 reasonContent，需要同步更新缓存
            if (reasonContentLoaded) {
                reasonContentCache = delegate.getReasoningContent();
            }
        }

        @Override
        public Role getRole() {
            return delegate.getRole();
        }

        @Override
        public int getIdentifier() {
            return delegate.getIdentifier();
        }

        @Override
        public String getReasoningContent() {
            if (!reasonContentLoaded) {
                // 懒加载
                reasonContentCache = dao.loadReasonContent(getIdentifier());
                // 同步到 delegate，以便后续修改
                if (reasonContentCache != null) {
                    delegate.setReasonContent(reasonContentCache);
                }
                reasonContentLoaded = true;
            }
            return reasonContentCache != null ? reasonContentCache : "";
        }

        @Override
        public boolean isValidContext() {
            return delegate.isValidContext();
        }

        @Override
        public void setValidContext(boolean sendable) {
            delegate.setValidContext(sendable);
            // 即时更新数据库
            dao.updateField(getIdentifier(), "should_send", sendable);
        }

        @Override
        public boolean isDeleted() {
            return delegate.isDeleted();
        }

        @Override
        public void setDeleted(boolean deleted) {
            delegate.setDeleted(deleted);
            dao.updateField(getIdentifier(), "deleted", deleted);
        }

        @Override
        public String getAudioId() {
            return delegate.getAudioId();
        }

        @Override
        public void setAudioId(String audioId) {
            delegate.setAudioId(audioId);
            dao.updateField(getIdentifier(), "audio_id", audioId);
        }

        @Override
        public ApplicationState getLastState() {
            if (!lastStateLoaded) {
                lastStateCache = dao.loadLastState(getIdentifier());
                if (lastStateCache != null) {
                    delegate.setLastState(lastStateCache);
                }
                lastStateLoaded = true;
            }
            return lastStateCache;
        }

        @Override
        public void setLastState(ApplicationState lastState) {
            delegate.setLastState(lastState);
            lastStateCache = lastState;
            lastStateLoaded = true; // 已加载且已更新
            // 注意：lastState 不立即入库，等待 flush
        }

        @Override
        public int getPrevIdentifier() {
            return delegate.getPrevIdentifier();
        }

        @Override
        public void setPrevIdentifier(int prevIdentifier) {
            delegate.setPrevIdentifier(prevIdentifier);
            // prevIdentifier 不立即入库，等待 flush
        }
    }
}