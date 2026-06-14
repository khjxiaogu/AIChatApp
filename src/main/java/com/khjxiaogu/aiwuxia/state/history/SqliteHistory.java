/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.state.history;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;

import com.google.gson.Gson;
import com.khjxiaogu.aiwuxia.state.GsonHelper;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * {@link HistoryHolder} 的基于 SQLite + JSON 文件的持久化实现。
 * <p>
 * 轻量元数据（角色、标识符、标志位等）存储在 SQLite 数据库的一行中；
 * 重度字段（{@code content}、{@code context}、{@code reasoner}、
 * {@code lastState}）存储为每条记录一个 JSON 文件。
 * 内存中维护一个最多 20 条记录的 LRU 缓存；驱逐时条目从内存完全移除，
 * 重新获取时从 SQLite + JSON 重建。
 * </p>
 * <p>
 * 追加操作（{@link #appendLine}、{@link #append}、{@link #appendContext}）
 * 仅修改内存中的缓存，不立即写入 JSON —— JSON 持久化在 {@link #flush()}
 * 中批量执行。应用应在每次修改完成后调用 {@link #flush()}。
 * </p>
 *
 * <h3>文件布局</h3>
 * <pre>
 *   &lt;basePath&gt;.db          — SQLite 数据库（元数据）
 *   &lt;basePath&gt;_data/
 *     1.json               — 条目 1 的重度字段
 *     2.json               — 条目 2 的重度字段
 *     ...
 * </pre>
 *
 * @see SqliteHistoryItem
 */
public class SqliteHistory implements HistoryHolder, SqliteHistoryItem.HeavyDataProvider {

	// ── JSON 序列化目标 ───────────────────────────────────

	/** 用于 Gson 序列化/反序列化重度字段的 POJO。 */
	static class HeavyData {
		MutableMessageContents content;
		MutableMessageContents context;
		MutableMessageContents reasoner;
		ApplicationState lastState;
	}

	// ── 路径 ──────────────────────────────────────────────

	private final File dataDir;
	private final Gson gson = GsonHelper.getPrettyPrintGson();

	// ── SQLite ────────────────────────────────────────────

	private final Connection conn;
	private final PreparedStatement insertStmt;
	private final PreparedStatement updateShouldSendStmt;
	private final PreparedStatement updateDeletedStmt;
	private final PreparedStatement updateSendReasonerStmt;
	private final PreparedStatement updateAudioIdStmt;
	private final PreparedStatement updateTokenLengthStmt;
	private final PreparedStatement deleteMetaStmt;
	private final PreparedStatement selectByIdStmt;
	private final PreparedStatement selectMaxIdStmt;
	private final PreparedStatement countStmt;
	private final PreparedStatement contextLimitStmt;
	private int idCounter;

	// ── 缓存 ──────────────────────────────────────────────

	/**
	 * 插入顺序缓存，最大容量 20。
	 * <p>
	 * 自动驱逐被禁用（{@code removeEldestEntry} 始终返回 {@code false}）；
	 * 驱逐仅在 {@link #flush()} 中手动执行，按标识符升序移除最旧的条目，
	 * 以保留最新的 20 条（按数据库插入顺序即标识符大小）。
	 * </p>
	 */
	private final LinkedHashMap<Integer, SqliteHistoryItem> cache = new LinkedHashMap<Integer, SqliteHistoryItem>(20, 0.75f, false);

	/**
	 * 最后未删除条目的缓存引用。
	 * <p>
	 * 避免每次调用 {@link #findLastItem()} 时扫描整个缓存或查询数据库。
	 * 在 {@code add}、{@code clear}、{@code removeOf}、{@code setDeleted}
	 * 等可能改变最后条目的操作中同步更新或置空。
	 * </p>
	 */
	private SqliteHistoryItem lastItem;

	// ── 构造 ──────────────────────────────────────────────

	/**
	 * 创建或打开一个基于 SQLite 的历史记录容器。
	 *
	 * @param basePath 存档文件路径
	 * @throws SQLException           如果数据库连接或初始化失败
	 * @throws ClassNotFoundException 如果找不到 SQLite JDBC 驱动
	 */
	public SqliteHistory(File basePath) throws SQLException, ClassNotFoundException {
		this.dataDir = new File(basePath,"history");
		if (!dataDir.exists()) {
			dataDir.mkdirs();
		}

		Class.forName("org.sqlite.JDBC");
		String dbPath = new File(basePath,"metadata.db").getAbsolutePath();
		conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

		try (Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS history ("
					+ "identifier INTEGER PRIMARY KEY, "
					+ "prev_identifier INTEGER NOT NULL DEFAULT -1, "
					+ "role TEXT NOT NULL, "
					+ "should_send INTEGER NOT NULL DEFAULT 1, "
					+ "deleted INTEGER NOT NULL DEFAULT 0, "
					+ "send_reasoner INTEGER NOT NULL DEFAULT 0, "
					+ "audio_id TEXT, "
					+ "token_length INTEGER NOT NULL DEFAULT 0"
					+ ")");
		}

		// 初始化 idCounter
		try (Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(
						"SELECT COALESCE(MAX(identifier), 0) FROM history")) {
			if (rs.next()) {
				idCounter = rs.getInt(1);
			}
		}

		// 预编译 SQL
		insertStmt = conn.prepareStatement(
				"INSERT INTO history (identifier, prev_identifier, role, "
				+ "should_send, deleted, send_reasoner, audio_id, token_length) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");

		updateShouldSendStmt = conn.prepareStatement(
				"UPDATE history SET should_send=? WHERE identifier=?");
		updateDeletedStmt = conn.prepareStatement(
				"UPDATE history SET deleted=? WHERE identifier=?");
		updateSendReasonerStmt = conn.prepareStatement(
				"UPDATE history SET send_reasoner=? WHERE identifier=?");
		updateAudioIdStmt = conn.prepareStatement(
				"UPDATE history SET audio_id=? WHERE identifier=?");
		updateTokenLengthStmt = conn.prepareStatement(
				"UPDATE history SET token_length=? WHERE identifier=?");

		deleteMetaStmt = conn.prepareStatement(
				"DELETE FROM history WHERE identifier=?");

		selectByIdStmt = conn.prepareStatement(
				"SELECT identifier, prev_identifier, role, should_send, "
				+ "deleted, send_reasoner, audio_id, token_length "
				+ "FROM history WHERE identifier=?");

		selectMaxIdStmt = conn.prepareStatement(
				"SELECT MAX(identifier) FROM history WHERE deleted=0");

		countStmt = conn.prepareStatement(
				"SELECT COUNT(*) FROM history WHERE deleted=0");

		contextLimitStmt = conn.prepareStatement(
				"SELECT COUNT(*) FROM history "
				+ "WHERE should_send=1 AND deleted=0 AND role='ASSISTANT'");
	}

	// ── HeavyDataProvider ─────────────────────────────────

	@Override
	public void loadHeavy(SqliteHistoryItem item) {
		File jsonFile = new File(dataDir, item.identifier + ".json");
		if (jsonFile.exists()) {
			try {
				byte[] bytes = Files.readAllBytes(jsonFile.toPath());
				String json = new String(bytes, "UTF-8");
				HeavyData hd = gson.fromJson(json, HeavyData.class);
				if (hd != null) {
					item.displayContent = hd.content;
					item.contextContent = hd.context;
					item.reasonContent = hd.reasoner;
					item.lastState = hd.lastState;
				}
			} catch (IOException e) {
				// 文件读取失败，字段保持 null
			}
		}
		// 确保 displayContent 至少为 ""
		if (item.displayContent == null) {
			item.displayContent = new MutableMessageContents();
		}
	}

	// ── 缓存访问 ──────────────────────────────────────────

	/**
	 * 按标识符加载条目：先查缓存，未命中则从数据库读取轻量字段并放入缓存。
	 * <p>
	 * 不触发重度字段的 JSON 加载 —— 重度字段由
	 * {@link SqliteHistoryItem#ensureLoaded()} 懒加载。
	 * </p>
	 *
	 * @param identifier 条目标识符
	 * @return 条目，如果数据库中不存在则返回 {@code null}
	 */
	SqliteHistoryItem loadItem(int identifier) {
		SqliteHistoryItem item = cache.get(identifier);
		if (item != null)
			return item;

		try {
			selectByIdStmt.setInt(1, identifier);
			try (ResultSet rs = selectByIdStmt.executeQuery()) {
				if (rs.next()) {
					item = new SqliteHistoryItem(this);
					item.identifier = rs.getInt(1);
					item.prevIdentifier = rs.getInt(2);
					item.role = Role.valueOf(rs.getString(3));
					item.shouldSend = rs.getInt(4) != 0;
					item.deleted = rs.getInt(5) != 0;
					item.sendReasoner = rs.getInt(6) != 0;
					item.audioId = rs.getString(7);
					if (rs.wasNull())
						item.audioId = null;
					item.tokenLength = rs.getLong(8);
					cache.put(identifier, item);
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return item;
	}

	/**
	 * 生成新的唯一标识符。
	 */
	private  int newUniqueId() {
		return ++idCounter;
	}



	// ── HistoryHolder 实现 ────────────────────────────────

	@Override
	public  boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public int size() {
		try {
			try (ResultSet rs = countStmt.executeQuery()) {
				if (rs.next())
					return rs.getInt(1);
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return 0;
	}

	@Override
	public  HistoryItem add(Role role,MessageContents content,
			MessageContents fullContent, MessageContents reasoner,
			boolean isValidContext) {
		int id = newUniqueId();
		HistoryItem last = peekLast();
		int prevId = (last != null) ? last.getIdentifier() : -1;

		try {
			insertStmt.setInt(1, id);
			insertStmt.setInt(2, prevId);
			insertStmt.setString(3, role.name());
			insertStmt.setInt(4, isValidContext ? 1 : 0);
			insertStmt.setInt(5, 0);
			insertStmt.setInt(6, 0);
			insertStmt.setString(7, null);
			insertStmt.setLong(8, 0);
			insertStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

		SqliteHistoryItem item = new SqliteHistoryItem(this);
		item.identifier = id;
		item.prevIdentifier = prevId;
		item.role = role;
		item.shouldSend = isValidContext;
		item.displayContent = new MutableMessageContents(content);
		if(fullContent!=null)
			item.contextContent = new MutableMessageContents(fullContent);
		if(reasoner!=null)
			item.reasonContent = new MutableMessageContents(reasoner);
		item.lastState = null;
		item.dirty = true;
		cache.put(id, item);
		lastItem = item;
		return item;
	}

	@Override
	public  void clear() {
		try (Statement stmt = conn.createStatement()) {
			stmt.execute("DELETE FROM history");
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		File[] files = dataDir.listFiles();
		if (files != null) {
			for (File f : files) {
				f.delete();
			}
		}
		cache.clear();
		lastItem = null;
		idCounter = 0;
	}

	@Override
	public  void removeOf(int identifier) {
		SqliteHistoryItem item = loadItem(identifier);
		if (item != null) {
			item.deleted = true;
		}
		try {
			deleteMetaStmt.setInt(1, identifier);
			deleteMetaStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		File jsonFile = new File(dataDir, identifier + ".json");
		jsonFile.delete();
		if (lastItem != null && lastItem.identifier == identifier)
			lastItem = null;
		cache.remove(identifier);
	}

	@Override
	public HistoryItem getById(int id) {
		return loadItem(id);
	}

	@Override
	public  HistoryItem removeLast() {
		SqliteHistoryItem last = peekLast();
		if (last != null) {
			removeOf(last.identifier);
		}
		return last;
	}

	@Override
	public  HistoryItem deleteLast() {
		SqliteHistoryItem last = peekLast();
		if (last != null) {
			setDeleted(last, true);
		}
		return last;
	}

	@Override
	public SqliteHistoryItem peekLast() {
		// 快速路径：缓存命中且未被删除
		if (lastItem != null && !lastItem.deleted)
			return lastItem;
		// 回退到数据库查询
		try {
			try (ResultSet rs = selectMaxIdStmt.executeQuery()) {
				if (rs.next()) {
					int id = rs.getInt(1);
					if (!rs.wasNull() && id > 0) {
						lastItem = loadItem(id);
						return lastItem;
					}
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		lastItem = null;
		return null;
	}

	@Override
	public long getContextLimit() {
		try {
			try (ResultSet rs = contextLimitStmt.executeQuery()) {
				if (rs.next())
					return rs.getLong(1);
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return 0;
	}

	// ── 元数据 setter ─────────────────────────────────────

	@Override
	public  void setValidContext(HistoryItem hi, boolean sendable) {
		if (!(hi instanceof SqliteHistoryItem))
			return;
		SqliteHistoryItem item = (SqliteHistoryItem) hi;
		item.shouldSend = sendable;
		try {
			updateShouldSendStmt.setInt(1, sendable ? 1 : 0);
			updateShouldSendStmt.setInt(2, item.identifier);
			updateShouldSendStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public  void setDeleted(HistoryItem hi, boolean sendable) {
		if (!(hi instanceof SqliteHistoryItem))
			return;
		SqliteHistoryItem item = (SqliteHistoryItem) hi;
		item.deleted = sendable;
		if (sendable && item == lastItem)
			lastItem = null;
		try {
			updateDeletedStmt.setInt(1, sendable ? 1 : 0);
			updateDeletedStmt.setInt(2, item.identifier);
			updateDeletedStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public  void setAudioId(HistoryItem hi, String audioId) {
		if (!(hi instanceof SqliteHistoryItem))
			return;
		SqliteHistoryItem item = (SqliteHistoryItem) hi;
		item.audioId = audioId;
		try {
			updateAudioIdStmt.setString(1, audioId);
			updateAudioIdStmt.setInt(2, item.identifier);
			updateAudioIdStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public  void setSendReasoner(HistoryItem hi, boolean sendReasoner) {
		if (!(hi instanceof SqliteHistoryItem))
			return;
		SqliteHistoryItem item = (SqliteHistoryItem) hi;
		item.sendReasoner = sendReasoner;
		try {
			updateSendReasonerStmt.setInt(1, sendReasoner ? 1 : 0);
			updateSendReasonerStmt.setInt(2, item.identifier);
			updateSendReasonerStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public  void setTokenLength(HistoryItem hi, long l) {
		if (!(hi instanceof SqliteHistoryItem))
			return;
		SqliteHistoryItem item = (SqliteHistoryItem) hi;
		item.tokenLength = l;
		try {
			updateTokenLengthStmt.setLong(1, l);
			updateTokenLengthStmt.setInt(2, item.identifier);
			updateTokenLengthStmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public  void setLastState(HistoryItem hi, ApplicationState lastState) {
		if (!(hi instanceof SqliteHistoryItem))
			return;
		SqliteHistoryItem item = (SqliteHistoryItem) hi;
		item.ensureLoaded();
		item.lastState = lastState;
		item.dirty = true;
	}

	// ── 追加操作 ──────────────────────────────────────────

	@Override
	public  void appendLine(String content, boolean addToContext) {
		append(content+"\n",addToContext);
	}

	@Override
	public  void append(String content, boolean addToContext) {
		SqliteHistoryItem item = peekLast();
		if (item == null)
			return;

		item.ensureLoaded();

		if (!addToContext) {
			// 冻结上下文：如果尚未显式设置，则用当前显示内容创建快照
			if (item.contextContent == null) {
				if(item.displayContent != null)
					item.contextContent = new MutableMessageContents(item.displayContent);
				else
					item.contextContent = new MutableMessageContents();
			}
		}else {
			if(item.contextContent!=null)
				item.contextContent.append(content);
		}
		
		if (item.displayContent == null) {
			item.displayContent = new MutableMessageContents();
			
		}
		item.displayContent.append(content);
		item.dirty = true;
	}

	@Override
	public  void appendContext(String content) {
		SqliteHistoryItem item = peekLast();
		if (item == null)
			return;

		item.ensureLoaded();

		if (item.contextContent == null) {
			item.contextContent = new MutableMessageContents(content);
		} else {
			item.contextContent.append(content);
		}
		item.dirty = true;
	}

	// ── flush ─────────────────────────────────────────────

	/**
	 * 批量持久化所有已修改的重度字段到 JSON 文件，并驱逐超出缓存容量的条目。
	 * <p>
	 * 应用应在每次修改完成后调用此方法：
	 * </p>
	 * <ol>
	 *   <li>将所有 {@code dirty} 条目的重度字段序列化为 JSON 并写入文件。</li>
	 *   <li>将缓存大小缩减至 20 条，按标识符升序移除最旧的条目（仅从内存移除，
	 *       数据库和 JSON 文件不受影响），保留最新插入的 20 条。</li>
	 * </ol>
	 */
	@Override
	public  void flush() {
		// 步骤 1：写入所有脏条目
		for (SqliteHistoryItem item : cache.values()) {
			if (item.dirty) {
				HeavyData hd = new HeavyData();
				hd.content = item.displayContent;
				hd.context = item.contextContent;
				hd.reasoner = item.reasonContent;
				hd.lastState = item.lastState;

				String json = gson.toJson(hd);
				File jsonFile = new File(dataDir, item.identifier + ".json");
				try {
					Files.write(jsonFile.toPath(), json.getBytes("UTF-8"));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				item.dirty = false;
			}
		}

		// 步骤 2：驱逐超出容量的条目 —— 移除标识符最小的条目，保留最新 20 条
		while (cache.size() > 20) {
			Integer minId = null;
			for (Integer id : cache.keySet()) {
				if (minId == null || id < minId) {
					minId = id;
				}
			}
			if (minId != null) {
				cache.remove(minId);
			}
		}
	}

	// ── 迭代器 ────────────────────────────────────────────

	@Override
	public Iterator<HistoryItem> iterator() {
		List<Integer> ids = queryIds(
				"SELECT identifier FROM history WHERE deleted=0 "
				+ "ORDER BY identifier ASC");
		return new SqliteIterator(ids.iterator());
	}

	@Override
	public Iterator<HistoryItem> reverseIterator() {
		List<Integer> ids = queryIds(
				"SELECT identifier FROM history WHERE deleted=0 "
				+ "ORDER BY identifier DESC");
		return new SqliteIterator(ids.iterator());
	}

	@Override
	public Iterator<HistoryItem> validContextIterator() {
		List<Integer> ids = queryIds(
				"SELECT identifier FROM history "
				+ "WHERE should_send=1 AND deleted=0 "
				+ "ORDER BY identifier ASC");
		return new SqliteIterator(ids.iterator());
	}

	private List<Integer> queryIds(String sql) {
		List<Integer> ids = new ArrayList<>();
		try (Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next())
				ids.add(rs.getInt(1));
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return ids;
	}

	/**
	 * 基于标识符列表的迭代器，每次 {@link #next()} 调用
	 * {@link #loadItem(int)} 以懒加载条目。
	 */
	private class SqliteIterator implements Iterator<HistoryItem> {

		private final Iterator<Integer> idIter;
		private HistoryItem nextItem;
		private HistoryItem toRemove;

		SqliteIterator(Iterator<Integer> idIter) {
			this.idIter = idIter;
			advance();
		}

		private void advance() {
			toRemove = nextItem;
			if (idIter.hasNext()) {
				nextItem = loadItem(idIter.next());
			} else {
				nextItem = null;
			}
		}

		@Override
		public boolean hasNext() {
			return nextItem != null;
		}

		@Override
		public HistoryItem next() {
			if (!hasNext())
				throw new NoSuchElementException();
			HistoryItem result = nextItem;
			advance();
			return result;
		}

		@Override
		public void remove() {
			if (toRemove == null)
				throw new NoSuchElementException();
			setDeleted(toRemove, true);
			throw new UnsupportedOperationException();
		}
	}

	// ── 资源管理 ──────────────────────────────────────────

	/**
	 * 关闭数据库连接并释放资源。
	 * <p>
	 * 调用此方法前应确保已调用 {@link #flush()} 以持久化所有未写入的数据。
	 * </p>
	 */
	public void close() {
		flush();
		try {
			insertStmt.close();
			updateShouldSendStmt.close();
			updateDeletedStmt.close();
			updateSendReasonerStmt.close();
			updateAudioIdStmt.close();
			updateTokenLengthStmt.close();
			deleteMetaStmt.close();
			selectByIdStmt.close();
			selectMaxIdStmt.close();
			countStmt.close();
			contextLimitStmt.close();
			conn.close();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setAudioId(String audioId) {
		SqliteHistoryItem last = peekLast();
		if (last != null) {
			this.setAudioId(last, audioId);
		}
	}

	@Override
	public void setLastState(ApplicationState lastState) {
		SqliteHistoryItem last = peekLast();
		if (last != null) {
			this.setLastState(last, lastState);
		}
	}

	@Override
	public void append(MessageContent content, boolean addToContext) {
		SqliteHistoryItem item = peekLast();
		if (item == null)
			return;

		item.ensureLoaded();

		if (!addToContext) {
			// 冻结上下文：如果尚未显式设置，则用当前显示内容创建快照
			if (item.contextContent == null) {
				if(item.displayContent != null)
					item.contextContent = new MutableMessageContents(item.displayContent);
				else
					item.contextContent = new MutableMessageContents();
			}
		}else {
			if(item.contextContent!=null)
				item.contextContent.add(content);
		}
		
		if (item.displayContent == null) {
			item.displayContent = new MutableMessageContents();
			
		}
		item.displayContent.add(content);
		item.dirty = true;
	}
	@Override
	public void appendReasoner(MessageContent current) {
		SqliteHistoryItem item = peekLast();
		if (item == null)
			return;

		item.ensureLoaded();

		if (item.reasonContent == null) {
			item.reasonContent = new MutableMessageContents();
		}
		item.reasonContent.add(current);
		item.dirty = true;
	}
}
