package com.khjxiaogu.aiwuxia.mcp.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;

/**
 * MCP 访问令牌与消费记录存储（SQLite）。
 * <p>
 * 职责：
 * </p>
 * <ul>
 *   <li><b>令牌</b>：用户可以为自己创建多个访问令牌（明文只在创建时返回一次，库中只保存 SHA-256 摘要），
 *       支持备注名、有效期、单令牌额度上限、吊销；</li>
 *   <li><b>计费</b>：每次工具调用前从令牌所属用户的“额外配额”（{@code user_paid_balance}，即补充配额）
 *       中预扣固定调用费 {@link McpServer#CALL_COST}；工具执行完成后，再把工具内部产生的用量
 *       （{@code AIOutput#addUsage} 写入的每一笔）按 {@code ceil(getEquivantTokens())} 合计补扣；
 *       固定费用不足时拒绝调用，调用失败时退回固定费用；</li>
 *   <li><b>消费记录</b>：每次调用都写入 {@code mcp_tool_call_log}（含工具、固定调用费、内部费用、
 *       实际费用、耗时、结果摘要、客户端 IP），成功调用另外写入现有的 {@code paid_usage_record}
 *       与 {@code daily_usage}，因此可以在应用原有的用量统计里看到 MCP 消耗。</li>
 * </ul>
 * <p>
 * 本类只依赖一个已打开的 SQLite {@link Connection}（与 {@code AIChatService} 共用 {@code messages.db}），
 * 与主服务共享同一份用户余额数据。所有公开方法都加锁串行执行。
 * </p>
 */
public class McpAccessTokenStore {

    /** 记录 {@code paid_usage_record.usage_type} 使用的默认用量类型。 */
    public static final String DEFAULT_USAGE_TYPE = "MCP工具";

    /** 令牌明文前缀，便于在日志与界面中识别。 */
    public static final String TOKEN_PREFIX = "mcp_";

    /** 令牌明文中随机部分的字节数（16 字节 → 32 位十六进制字符）。 */
    private static final int TOKEN_RANDOM_BYTES = 16;

    /** 一个访问令牌的持久化信息（不含明文）。 */
    public static class Token {
        private final long id;
        private final String uid;
        private final String name;
        private final String tokenPrefix;
        private final int quotaLimit;
        private final int quotaUsed;
        private final int callCount;
        private final boolean enabled;
        private final long createdAt;
        private final Long expiresAt;
        private final Long lastUsedAt;

        Token(long id, String uid, String name, String tokenPrefix, int quotaLimit, int quotaUsed, int callCount,
                boolean enabled, long createdAt, Long expiresAt, Long lastUsedAt) {
            this.id = id;
            this.uid = uid;
            this.name = name;
            this.tokenPrefix = tokenPrefix;
            this.quotaLimit = quotaLimit;
            this.quotaUsed = quotaUsed;
            this.callCount = callCount;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.lastUsedAt = lastUsedAt;
        }

        /** 令牌数据库主键。 */
        public long getId() {
            return id;
        }

        /** 令牌所属用户 ID。 */
        public String getUid() {
            return uid;
        }

        /** 用户为令牌设置的备注名。 */
        public String getName() {
            return name;
        }

        /** 明文前缀（用于列表展示，无法反推出完整令牌）。 */
        public String getTokenPrefix() {
            return tokenPrefix;
        }

        /** 单令牌累计额度上限，0 表示不限制。 */
        public int getQuotaLimit() {
            return quotaLimit;
        }

        /** 该令牌累计已消耗额度。 */
        public int getQuotaUsed() {
            return quotaUsed;
        }

        /** 该令牌累计调用次数（含免费工具）。 */
        public int getCallCount() {
            return callCount;
        }

        /** 是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 创建时间（毫秒时间戳）。 */
        public long getCreatedAt() {
            return createdAt;
        }

        /** 过期时间（毫秒时间戳），null 表示永不过期。 */
        public Long getExpiresAt() {
            return expiresAt;
        }

        /** 最后使用时间（毫秒时间戳），null 表示从未使用。 */
        public Long getLastUsedAt() {
            return lastUsedAt;
        }

        /**
         * 令牌当前是否可用（启用且未过期）。
         *
         * @param now 当前毫秒时间戳
         * @return 可用返回 true
         */
        public boolean isUsable(long now) {
            return enabled && (expiresAt == null || expiresAt.longValue() > now);
        }

        /** 单令牌剩余额度，未设置上限时返回 -1。 */
        public int getRemainingQuota() {
            return quotaLimit <= 0 ? -1 : Math.max(0, quotaLimit - quotaUsed);
        }

        /**
         * 序列化为对外展示的 JSON（不含明文与摘要）。
         *
         * @return 令牌信息
         */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("uid", uid);
            json.addProperty("name", name);
            json.addProperty("tokenPrefix", tokenPrefix);
            json.addProperty("quotaLimit", quotaLimit);
            json.addProperty("quotaUsed", quotaUsed);
            json.addProperty("remainingQuota", getRemainingQuota());
            json.addProperty("callCount", callCount);
            json.addProperty("enabled", enabled);
            json.addProperty("createdAt", createdAt);
            if (expiresAt != null) {
                json.addProperty("expiresAt", expiresAt.longValue());
            }
            if (lastUsedAt != null) {
                json.addProperty("lastUsedAt", lastUsedAt.longValue());
            }
            return json;
        }
    }

    /** 新建令牌的结果：持久化信息 + 只出现一次的明文。 */
    public static class CreatedToken {
        private final Token token;
        private final String plaintext;

        CreatedToken(Token token, String plaintext) {
            this.token = token;
            this.plaintext = plaintext;
        }

        /** 令牌持久化信息。 */
        public Token getToken() {
            return token;
        }

        /** 令牌明文，仅在创建时返回一次。 */
        public String getPlaintext() {
            return plaintext;
        }
    }

    /**
     * 一次工具调用的预扣结果。
     * <p>由 {@link #charge} 生成，调用成功交给 {@link #commit}，失败交给 {@link #refund} 退回。</p>
     */
    public static class Charge {
        private final long tokenId;
        private final String uid;
        private final int cost;
        private final long logId;
        private final long startTime;

        Charge(long tokenId, String uid, int cost, long logId, long startTime) {
            this.tokenId = tokenId;
            this.uid = uid;
            this.cost = cost;
            this.logId = logId;
            this.startTime = startTime;
        }

        /** 令牌主键。 */
        public long getTokenId() {
            return tokenId;
        }

        /** 令牌所属用户。 */
        public String getUid() {
            return uid;
        }

        /** 本次预扣的额外配额数量。 */
        public int getCost() {
            return cost;
        }

        /** 消费记录主键。 */
        public long getLogId() {
            return logId;
        }

        /** 预扣开始的毫秒时间戳。 */
        public long getStartTime() {
            return startTime;
        }
    }

    /** 一条工具调用消费记录。 */
    public static class UsageRecord {
        private final long id;
        private final long tokenId;
        private final String uid;
        private final String tool;
        private final int cost;
        private final int callCost;
        private final int usageCost;
        private final boolean ok;
        private final String error;
        private final String requestId;
        private final String clientIp;
        private final long durationMs;
        private final int resultChars;
        private final long createdAt;

        UsageRecord(long id, long tokenId, String uid, String tool, int cost, int callCost, int usageCost, boolean ok,
                String error, String requestId, String clientIp, long durationMs, int resultChars, long createdAt) {
            this.id = id;
            this.tokenId = tokenId;
            this.uid = uid;
            this.tool = tool;
            this.cost = cost;
            this.callCost = callCost;
            this.usageCost = usageCost;
            this.ok = ok;
            this.error = error;
            this.requestId = requestId;
            this.clientIp = clientIp;
            this.durationMs = durationMs;
            this.resultChars = resultChars;
            this.createdAt = createdAt;
        }

        public long getId() {
            return id;
        }

        public long getTokenId() {
            return tokenId;
        }

        public String getUid() {
            return uid;
        }

        public String getTool() {
            return tool;
        }

        /** 本次调用实际扣除的额外配额（失败时为 0）。 */
        public int getCost() {
            return cost;
        }

        /** 本次调用的固定调用费（名义值，失败时也保留）。 */
        public int getCallCost() {
            return callCost;
        }

        /** 工具内部用量折算出的费用。 */
        public int getUsageCost() {
            return usageCost;
        }

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getClientIp() {
            return clientIp;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public int getResultChars() {
            return resultChars;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        /**
         * 序列化为对外展示的 JSON（时间为毫秒时间戳，另附可读时间）。
         *
         * @return 消费记录
         */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("tokenId", tokenId);
            json.addProperty("uid", uid);
            json.addProperty("tool", tool);
            json.addProperty("cost", cost);
            json.addProperty("callCost", callCost);
            json.addProperty("usageCost", usageCost);
            json.addProperty("ok", ok);
            if (error != null && !error.isEmpty()) {
                json.addProperty("error", error);
            }
            if (requestId != null && !requestId.isEmpty()) {
                json.addProperty("requestId", requestId);
            }
            if (clientIp != null && !clientIp.isEmpty()) {
                json.addProperty("clientIp", clientIp);
            }
            json.addProperty("durationMs", durationMs);
            json.addProperty("resultChars", resultChars);
            json.addProperty("createdAt", createdAt);
            json.addProperty("createdAtText",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(createdAt)));
            return json;
        }
    }

    /** 数据库连接（与主服务共用）。 */
    private final Connection database;
    /** 默认用量类型（写入 {@code paid_usage_record.usage_type}）。 */
    private final String usageType;
    /** 令牌随机源。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建存储并初始化所需的表。
     *
     * @param database  SQLite 连接（由调用方负责关闭）
     * @param usageType 写入现有消费统计时使用的用量类型，null 时使用 {@link #DEFAULT_USAGE_TYPE}
     * @throws SQLException 建表失败
     */
    public McpAccessTokenStore(Connection database, String usageType) throws SQLException {
        this.database = database;
        this.usageType = usageType == null || usageType.trim().isEmpty() ? DEFAULT_USAGE_TYPE : usageType;
        initTables();
    }

    /** 建表（幂等）。 */
    private void initTables() throws SQLException {
        try (Statement stmt = database.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS mcp_access_token ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uid TEXT NOT NULL,"
                    + "name TEXT NOT NULL DEFAULT '',"
                    + "token_hash TEXT NOT NULL UNIQUE,"
                    + "token_prefix TEXT NOT NULL DEFAULT '',"
                    + "quota_limit INTEGER NOT NULL DEFAULT 0,"
                    + "quota_used INTEGER NOT NULL DEFAULT 0,"
                    + "call_count INTEGER NOT NULL DEFAULT 0,"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "created_at INTEGER NOT NULL,"
                    + "expires_at INTEGER,"
                    + "last_used_at INTEGER)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mcp_token_uid ON mcp_access_token(uid)");
            stmt.execute("CREATE TABLE IF NOT EXISTS mcp_tool_call_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "token_id INTEGER NOT NULL,"
                    + "uid TEXT NOT NULL,"
                    + "tool TEXT NOT NULL,"
                    + "cost INTEGER NOT NULL DEFAULT 0,"
                    + "call_cost INTEGER NOT NULL DEFAULT 0,"
                    + "usage_cost INTEGER NOT NULL DEFAULT 0,"
                    + "ok INTEGER NOT NULL DEFAULT 0,"
                    + "error TEXT,"
                    + "request_id TEXT,"
                    + "client_ip TEXT,"
                    + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                    + "result_chars INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mcp_log_uid ON mcp_tool_call_log(uid, id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mcp_log_token ON mcp_tool_call_log(token_id, id)");
        }
        // 兼容旧库：补上计费明细列（旧版本只有 cost 列）
        ensureColumn("mcp_tool_call_log", "call_cost", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("mcp_tool_call_log", "usage_cost", "INTEGER NOT NULL DEFAULT 0");
    }

    /** 若表中缺少某列则补上（SQLite 的 ADD COLUMN 是幂等的补充操作）。 */
    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (Statement stmt = database.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement stmt = database.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ex) {
            // 并发初始化时另一个线程可能已经加过该列，确认列存在即可
            try (Statement stmt = database.createStatement();
                    ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("name"))) {
                        return;
                    }
                }
            }
            throw ex;
        }
    }

    // ==================== 令牌管理 ====================

    /**
     * 为用户创建一个访问令牌。
     *
     * @param uid       用户 ID，不能为空
     * @param name      备注名，可为空
     * @param quotaLimit 单令牌额度上限，小于等于 0 表示不限制
     * @param expiresAt 过期时间（毫秒时间戳），null 表示永不过期
     * @return 含明文的创建结果
     * @throws IllegalArgumentException 用户 ID 非法
     * @throws SQLException             写入失败
     */
    public synchronized CreatedToken createToken(String uid, String name, int quotaLimit, Long expiresAt)
            throws SQLException {
        if (uid == null || uid.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        String normalizedUid = uid.trim();
        String plaintext = generatePlaintext();
        String hash = sha256(plaintext);
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO mcp_access_token"
                + " (uid, name, token_hash, token_prefix, quota_limit, quota_used, call_count, enabled, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, 0, 1, ?, ?)";
        long id;
        try (PreparedStatement ps = database.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normalizedUid);
            ps.setString(2, name == null ? "" : name.trim());
            ps.setString(3, hash);
            ps.setString(4, prefixOf(plaintext));
            ps.setInt(5, Math.max(0, quotaLimit));
            ps.setLong(6, now);
            if (expiresAt == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, expiresAt.longValue());
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("创建访问令牌失败：未返回主键");
                }
                id = keys.getLong(1);
            }
        }
        Token token = new Token(id, normalizedUid, name == null ? "" : name.trim(), prefixOf(plaintext),
                Math.max(0, quotaLimit), 0, 0, true, now, expiresAt, null);
        return new CreatedToken(token, plaintext);
    }

    /**
     * 按令牌明文查找令牌（不校验启用状态与有效期）。
     *
     * @param plaintext 令牌明文
     * @return 令牌信息，不存在返回 null
     */
    public synchronized Token findByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        String sql = "SELECT * FROM mcp_access_token WHERE token_hash = ?";
        try (PreparedStatement ps = database.prepareStatement(sql)) {
            ps.setString(1, sha256(plaintext));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readToken(rs);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    /**
     * 列出某个用户的全部令牌（不含明文）。
     *
     * @param uid 用户 ID
     * @return 令牌列表，永不 null
     */
    public synchronized List<Token> listTokens(String uid) {
        List<Token> result = new ArrayList<>();
        if (uid == null || uid.trim().isEmpty()) {
            return result;
        }
        String sql = "SELECT * FROM mcp_access_token WHERE uid = ? ORDER BY id DESC";
        try (PreparedStatement ps = database.prepareStatement(sql)) {
            ps.setString(1, uid.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readToken(rs));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return result;
    }

    /**
     * 吊销（禁用）一个令牌。
     *
     * @param uid 用户 ID（用于防止越权吊销他人令牌）
     * @param id  令牌主键
     * @return 实际更新了记录返回 true
     */
    public synchronized boolean revokeToken(String uid, long id) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        try (PreparedStatement ps = database.prepareStatement(
                "UPDATE mcp_access_token SET enabled = 0 WHERE id = ? AND uid = ?")) {
            ps.setLong(1, id);
            ps.setString(2, uid.trim());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * 删除一个令牌及其消费记录。
     *
     * @param uid 用户 ID
     * @param id  令牌主键
     * @return 实际删除了令牌返回 true
     */
    public synchronized boolean deleteToken(String uid, long id) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        try {
            try (PreparedStatement ps = database.prepareStatement(
                    "DELETE FROM mcp_tool_call_log WHERE token_id = ? AND uid = ?")) {
                ps.setLong(1, id);
                ps.setString(2, uid.trim());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement(
                    "DELETE FROM mcp_access_token WHERE id = ? AND uid = ?")) {
                ps.setLong(1, id);
                ps.setString(2, uid.trim());
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * 查询用户当前的额外配额余额（{@code user_paid_balance}）。
     *
     * @param uid 用户 ID
     * @return 余额，用户不存在或查询失败返回 0
     */
    public synchronized int getPaidBalance(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT balance FROM user_paid_balance WHERE user_id = ?")) {
            ps.setString(1, uid.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return 0;
        }
    }

    // ==================== 计费 ====================

    /**
     * 工具调用前预扣费用。
     * <p>
     * 依次校验令牌有效性、令牌额度上限、用户额外配额余额，然后：
     * 从 {@code user_paid_balance} 扣费、累加令牌已用额度与调用次数、写入一条待完成的消费记录。
     * 任一校验失败都抛出 {@link McpException}，且不会留下扣费。
     * </p>
     *
     * @param token     已认证的令牌
     * @param tool      工具名称
     * @param cost      本次费用（额外配额单位），小于等于 0 表示免费
     * @param clientIp  客户端 IP，可为 null
     * @param requestId 请求标识，可为 null
     * @return 预扣结果，交给 {@link #commit} 或 {@link #refund}
     * @throws McpException 令牌失效、用户余额不足或令牌额度不足
     */
    public synchronized Charge charge(Token token, String tool, int cost, String clientIp, String requestId)
            throws McpException {
        long now = System.currentTimeMillis();
        if (token == null || !token.isUsable(now)) {
            throw McpException.unauthorized("访问令牌已失效或已过期");
        }
        int price = Math.max(0, cost);
        // 1. 先扣用户额外配额（条件更新保证并发下不会透支）
        if (price > 0) {
            try (PreparedStatement ps = database.prepareStatement(
                    "UPDATE user_paid_balance SET balance = balance - ? WHERE user_id = ? AND balance >= ?")) {
                ps.setInt(1, price);
                ps.setString(2, token.getUid());
                ps.setInt(3, price);
                if (ps.executeUpdate() != 1) {
                    JsonObject data = new JsonObject();
                    data.addProperty("required", price);
                    data.addProperty("balance", getPaidBalance(token.getUid()));
                    throw McpException.quotaExceeded(
                            "额外配额不足：本次调用需要 " + price + "，当前余额 " + getPaidBalance(token.getUid()),
                            data);
                }
            } catch (SQLException ex) {
                throw new McpException(McpProtocol.ERROR_INTERNAL, "扣减额外配额失败", ex);
            }
        }
        // 2. 更新令牌用量（同时再次校验启用状态、有效期与单令牌额度上限）
        boolean tokenUpdated;
        try (PreparedStatement ps = database.prepareStatement(
                "UPDATE mcp_access_token SET quota_used = quota_used + ?, call_count = call_count + 1,"
                        + " last_used_at = ? WHERE id = ? AND enabled = 1"
                        + " AND (expires_at IS NULL OR expires_at > ?)"
                        + " AND (quota_limit = 0 OR quota_used + ? <= quota_limit)")) {
            ps.setInt(1, price);
            ps.setLong(2, now);
            ps.setLong(3, token.getId());
            ps.setLong(4, now);
            ps.setInt(5, price);
            tokenUpdated = ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            refundBalance(token.getUid(), price);
            throw new McpException(McpProtocol.ERROR_INTERNAL, "更新令牌用量失败", ex);
        }
        if (!tokenUpdated) {
            refundBalance(token.getUid(), price);
            Token latest = findById(token.getId());
            if (latest == null || !latest.isUsable(now)) {
                throw McpException.unauthorized("访问令牌已失效或已过期");
            }
            JsonObject data = new JsonObject();
            data.addProperty("quotaLimit", latest.getQuotaLimit());
            data.addProperty("quotaUsed", latest.getQuotaUsed());
            data.addProperty("remainingQuota", latest.getRemainingQuota());
            throw McpException.quotaExceeded("该访问令牌的额度已用尽", data);
        }
        // 3. 写入待完成的消费记录
        long logId = insertLog(token, tool, price, clientIp, requestId, now);
        return new Charge(token.getId(), token.getUid(), price, logId, now);
    }

    /**
     * 工具调用成功：补扣工具内部用量产生的费用，补写用户可见的消费记录
     * （{@code paid_usage_record} 与 {@code daily_usage}），并把 MCP 消费记录标记为成功。
     * <p>
     * 固定调用费已在 {@link #charge} 中预扣；本方法补扣 {@code usageCost}，并把
     * {@code cost} 更新为两者的合计。内部用量已经真实产生，因此这里与主流程的
     * {@code consumePaidTokens} 一样直接扣减余额（余额不足时可能为负），保证费用不丢失。
     * </p>
     *
     * @param charge      预扣结果
     * @param usageCost   工具内部用量折算出的费用（小于 0 按 0 处理）
     * @param durationMs  调用耗时（毫秒）
     * @param resultChars 结果字符数
     */
    public synchronized void commit(Charge charge, int usageCost, long durationMs, int resultChars) {
        if (charge == null) {
            return;
        }
        int extra = Math.max(0, usageCost);
        if (extra > 0) {
            // 工具已经执行完成，内部用量对应的成本已经发生，按实际金额补扣
            try (PreparedStatement ps = database.prepareStatement(
                    "UPDATE user_paid_balance SET balance = balance - ? WHERE user_id = ?")) {
                ps.setInt(1, extra);
                ps.setString(2, charge.getUid());
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            try (PreparedStatement ps = database.prepareStatement(
                    "UPDATE mcp_access_token SET quota_used = quota_used + ? WHERE id = ?")) {
                ps.setInt(1, extra);
                ps.setLong(2, charge.getTokenId());
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
        long totalLong = (long) charge.getCost() + extra;
        int total = totalLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalLong;
        if (total > 0) {
            recordUserConsumption(charge.getUid(), total);
        }
        try (PreparedStatement ps = database.prepareStatement(
                "UPDATE mcp_tool_call_log SET ok = 1, cost = ?, usage_cost = ?, duration_ms = ?, result_chars = ? WHERE id = ?")) {
            ps.setInt(1, total);
            ps.setInt(2, extra);
            ps.setLong(3, durationMs);
            ps.setInt(4, resultChars);
            ps.setLong(5, charge.getLogId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 旧版结算入口：不含工具内部用量（内部费用按 0 处理）。
     *
     * @param charge      预扣结果
     * @param durationMs  调用耗时（毫秒）
     * @param resultChars 结果字符数
     * @deprecated 计费改为“固定调用费 + 工具内部用量”，请使用
     *             {@link #commit(Charge, int, long, int)}
     */
    @Deprecated
    public synchronized void commit(Charge charge, long durationMs, int resultChars) {
        commit(charge, 0, durationMs, resultChars);
    }

    /**
     * 工具调用失败：退回预扣的固定调用费与令牌额度，并把消费记录标记为失败。
     * <p>
     * 失败时内部用量不向用户收费，因此记录中的实际费用 {@code cost} 与内部费用均为 0，
     * 只在 {@code call_cost} 中保留名义固定调用费。
     * </p>
     *
     * @param charge 预扣结果
     * @param error  失败原因（写入消费记录）
     */
    public synchronized void refund(Charge charge, String error) {
        if (charge == null) {
            return;
        }
        if (charge.getCost() > 0) {
            refundBalance(charge.getUid(), charge.getCost());
            try (PreparedStatement ps = database.prepareStatement(
                    "UPDATE mcp_access_token SET quota_used = MAX(0, quota_used - ?) WHERE id = ?")) {
                ps.setInt(1, charge.getCost());
                ps.setLong(2, charge.getTokenId());
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
        try (PreparedStatement ps = database.prepareStatement(
                "UPDATE mcp_tool_call_log SET ok = 0, cost = 0, usage_cost = 0, error = ?, duration_ms = ? WHERE id = ?")) {
            ps.setString(1, error == null ? "未知错误" : error);
            ps.setLong(2, Math.max(0, System.currentTimeMillis() - charge.getStartTime()));
            ps.setLong(3, charge.getLogId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // ==================== 消费记录查询 ====================

    /**
     * 查询消费记录。
     *
     * @param uid     用户 ID，null 表示不按用户过滤
     * @param tokenId 令牌主键，null 表示不按令牌过滤
     * @param limit   最多返回的条数（1..1000）
     * @return 记录列表，按时间倒序
     */
    public synchronized List<UsageRecord> listUsage(String uid, Long tokenId, int limit) {
        List<UsageRecord> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM mcp_tool_call_log WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (uid != null && !uid.trim().isEmpty()) {
            sql.append(" AND uid = ?");
            params.add(uid.trim());
        }
        if (tokenId != null) {
            sql.append(" AND token_id = ?");
            params.add(tokenId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(1000, limit)));
        try (PreparedStatement ps = database.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readUsage(rs));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return result;
    }

    /**
     * 汇总统计：令牌数、调用次数、消费额度。
     *
     * @param uid 用户 ID，null 表示统计全部用户
     * @return 统计信息
     */
    public synchronized JsonObject statistics(String uid) {
        JsonObject json = new JsonObject();
        String userFilter = uid == null || uid.trim().isEmpty() ? null : uid.trim();
        try {
            String tokenSql = "SELECT COUNT(*) AS total,"
                    + " SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS active,"
                    + " SUM(quota_used) AS used, SUM(call_count) AS calls FROM mcp_access_token"
                    + (userFilter == null ? "" : " WHERE uid = ?");
            try (PreparedStatement ps = database.prepareStatement(tokenSql)) {
                if (userFilter != null) {
                    ps.setString(1, userFilter);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        json.addProperty("tokenTotal", rs.getInt("total"));
                        json.addProperty("tokenActive", rs.getInt("active"));
                        json.addProperty("quotaUsed", rs.getInt("used"));
                        json.addProperty("tokenCalls", rs.getInt("calls"));
                    }
                }
            }
            String logSql = "SELECT COUNT(*) AS total,"
                    + " SUM(CASE WHEN ok = 1 THEN 1 ELSE 0 END) AS okCount,"
                    + " SUM(cost) AS cost, SUM(call_cost) AS callCost, SUM(usage_cost) AS usageCost"
                    + " FROM mcp_tool_call_log"
                    + (userFilter == null ? "" : " WHERE uid = ?");
            try (PreparedStatement ps = database.prepareStatement(logSql)) {
                if (userFilter != null) {
                    ps.setString(1, userFilter);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        json.addProperty("callTotal", rs.getInt("total"));
                        json.addProperty("callOk", rs.getInt("okCount"));
                        json.addProperty("costTotal", rs.getInt("cost"));
                        json.addProperty("callCostTotal", rs.getInt("callCost"));
                        json.addProperty("usageCostTotal", rs.getInt("usageCost"));
                    }
                }
            }
            if (userFilter != null) {
                json.addProperty("paidBalance", getPaidBalance(userFilter));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return json;
    }

    // ==================== 内部实现 ====================

    /** 按主键读取令牌，不存在返回 null。 */
    private Token findById(long id) {
        try (PreparedStatement ps = database.prepareStatement("SELECT * FROM mcp_access_token WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readToken(rs) : null;
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /** 把额外配额退回用户余额。 */
    private void refundBalance(String uid, int cost) {
        if (cost <= 0) {
            return;
        }
        try (PreparedStatement ps = database.prepareStatement(
                "UPDATE user_paid_balance SET balance = balance + ? WHERE user_id = ?")) {
            ps.setInt(1, cost);
            ps.setString(2, uid);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /** 把成功的消费补写进应用原有的用量统计表（失败只记日志，不影响工具结果）。 */
    private void recordUserConsumption(String uid, int cost) {
        if (cost <= 0 || uid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(now));
        try (PreparedStatement ps = database.prepareStatement(
                "INSERT INTO daily_usage (user_id, date, free_used, paid_used) VALUES (?, ?, 0, ?)"
                        + " ON CONFLICT(user_id, date) DO UPDATE SET paid_used = paid_used + ?")) {
            ps.setString(1, uid);
            ps.setString(2, date);
            ps.setInt(3, cost);
            ps.setInt(4, cost);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        try (PreparedStatement ps = database.prepareStatement(
                "INSERT INTO paid_usage_record (user_id, chat_id, usage_type, date, tokens) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setString(2, null);
            ps.setString(3, usageType);
            ps.setString(4, date);
            ps.setInt(5, cost);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /** 写入一条待完成的消费记录，返回主键。 */
    private long insertLog(Token token, String tool, int cost, String clientIp, String requestId, long now) {
        String sql = "INSERT INTO mcp_tool_call_log"
                + " (token_id, uid, tool, cost, call_cost, usage_cost, ok, request_id, client_ip, duration_ms, result_chars, created_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, 0, 0, ?)";
        try (PreparedStatement ps = database.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, token.getId());
            ps.setString(2, token.getUid());
            ps.setString(3, tool == null ? "" : tool);
            ps.setInt(4, cost);
            ps.setInt(5, cost);
            ps.setString(6, requestId);
            ps.setString(7, clientIp);
            ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return -1;
    }

    /** 从结果集读取令牌。 */
    private static Token readToken(ResultSet rs) throws SQLException {
        long expiresAt = rs.getLong("expires_at");
        Long expires = rs.wasNull() ? null : Long.valueOf(expiresAt);
        long lastUsed = rs.getLong("last_used_at");
        Long lastUsedAt = rs.wasNull() ? null : Long.valueOf(lastUsed);
        return new Token(rs.getLong("id"), rs.getString("uid"), rs.getString("name"),
                rs.getString("token_prefix"), rs.getInt("quota_limit"), rs.getInt("quota_used"),
                rs.getInt("call_count"), rs.getInt("enabled") != 0, rs.getLong("created_at"), expires, lastUsedAt);
    }

    /** 从结果集读取消费记录。 */
    private static UsageRecord readUsage(ResultSet rs) throws SQLException {
        return new UsageRecord(rs.getLong("id"), rs.getLong("token_id"), rs.getString("uid"),
                rs.getString("tool"), rs.getInt("cost"), rs.getInt("call_cost"), rs.getInt("usage_cost"),
                rs.getInt("ok") != 0, rs.getString("error"), rs.getString("request_id"),
                rs.getString("client_ip"), rs.getLong("duration_ms"), rs.getInt("result_chars"),
                rs.getLong("created_at"));
    }

    /** 生成令牌明文：{@code mcp_} + 随机十六进制。 */
    private String generatePlaintext() {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + toHex(bytes);
    }

    /** 令牌明文的前缀（用于展示）。 */
    private static String prefixOf(String plaintext) {
        int length = Math.min(plaintext.length(), TOKEN_PREFIX.length() + 8);
        return plaintext.substring(0, length);
    }

    /** 计算 SHA-256 十六进制摘要。 */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // 所有 JVM 都必须支持 SHA-256
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /** 字节数组转小写十六进制。 */
    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = hex[value >>> 4];
            chars[i * 2 + 1] = hex[value & 0x0F];
        }
        return new String(chars);
    }
}
