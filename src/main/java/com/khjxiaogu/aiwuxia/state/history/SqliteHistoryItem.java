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

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * {@link HistoryItem} 的基于 SQLite+JSON 的实现，支持懒加载重度字段。
 * <p>
 * 轻量字段（角色、标识符、标志位等）始终在内存中；重度字段
 * （{@code displayContent}、{@code contextContent}、{@code reasonContent}、
 * {@code lastState}）通过 {@link HeavyDataProvider} 回调按需从 JSON 文件加载。
 * </p>
 * <p>
 * </p>
 *
 * @see SqliteHistory
 */
class SqliteHistoryItem implements HistoryItem {

	/**
	 * 重度字段加载回调接口。
	 * <p>
	 * 由 {@link SqliteHistory} 实现，当某个 {@link SqliteHistoryItem}
	 * 的重度字段首次被访问时调用，以从对应的 JSON 文件中反序列化数据。
	 * </p>
	 */
	interface HeavyDataProvider {
		void loadHeavy(SqliteHistoryItem item);
	}

	// ── 轻量字段（始终在内存中）───────────────────────────

	int identifier = -1;
	int prevIdentifier = -1;
	long tokenLength = 0;
	Role role;
	boolean shouldSend;
	boolean deleted = false;
	boolean sendReasoner = false;
	String audioId;

	// ── 重度字段（懒加载 / 首次设置时填充）─────────────────

	String displayContent;
	MutableMessageContents contextContent;
	MutableMessageContents reasonContent;
	ApplicationState lastState;

	/** 重度字段是否已修改（需在下次 {@code flush()} 时写回 JSON） */
	boolean dirty = false;

	/** 重度字段加载回调，首次构造时设置，加载后保留以便后续使用 */
	HeavyDataProvider provider;

	/**
	 * 使用指定的重度字段加载器构造一个空条目。
	 *
	 * @param provider 加载器，用于按需加载重度字段
	 */
	SqliteHistoryItem(HeavyDataProvider provider) {
		this.provider = provider;
	}

	/**
	 * 确保重度字段已加载。
	 * <p>
	 * 如果 {@code displayContent} 仍为 {@code null}，则通过
	 * {@link HeavyDataProvider} 从 JSON 文件加载所有重度字段。
	 * 加载后 {@code displayContent} 至少为 {@code ""}。
	 * </p>
	 */
	void ensureLoaded() {
		if (displayContent == null && provider != null) {
			provider.loadHeavy(this);
		}
	}

	// ── HistoryItem getters ────────────────────────────────

	@Override
	public MessageContents getContextContent() {
		ensureLoaded();
		if (contextContent != null)
			return contextContent;
		if (displayContent != null)
			return new MutableMessageContents(displayContent);
		return null;
	}

	@Override
	public CharSequence getDisplayContent() {
		ensureLoaded();
		return displayContent;
	}

	@Override
	public Role getRole() {
		return role;
	}

	@Override
	public int getIdentifier() {
		return identifier;
	}

	@Override
	public MessageContents getReasoningContent() {
		ensureLoaded();
		return reasonContent;
	}

	@Override
	public boolean isValidContext() {
		return shouldSend;
	}

	@Override
	public boolean isDeleted() {
		return deleted;
	}

	@Override
	public String getAudioId() {
		return audioId;
	}

	@Override
	public ApplicationState getLastState() {
		ensureLoaded();
		return lastState;
	}

	@Override
	public int getPrevIdentifier() {
		return prevIdentifier;
	}

	@Override
	public long getTokenLength() {
		return tokenLength;
	}

	@Override
	public boolean maySendReasoner() {
		return sendReasoner;
	}
}
