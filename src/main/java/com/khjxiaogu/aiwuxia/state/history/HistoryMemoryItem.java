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

import java.io.Serializable;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * {@link MutableHistoryItem} 的基于内存的实现，支持序列化。
 * <p>
 * 显示内容使用 {@link StringBuilder} 存储，上下文内容和推理内容分别使用
 * {@link MutableMessageContents} 存储。支持内容追加时的双路写入：
 * 显示内容总是写入，上下文内容根据 {@code shouldSend} 参数选择性写入。
 * </p>
 * <p>
 * 该类为包级可见，外部应通过 {@link HistoryHolder} 接口操作。
 * </p>
 *
 * @see MemoryHistory
 */
class HistoryMemoryItem implements Serializable, MutableHistoryItem {

	/** 序列化版本标识 */
	private static final long serialVersionUID = -2560643964648148908L;

	/** 条目标识符 */
	private int identifier = -1;
	/** 前一条目的标识符，用于维护链表关系（-1 表示无前驱） */
	private int prevIdentifier = -1;
	/** 令牌长度（transient，不参与序列化） */
	private transient long tokenLength = 0;
	/** 条目关联的角色 */
	private Role role;
	/** 显示内容（始终写入） */
	private MutableMessageContents content;
	/** 发送给 LLM 的上下文内容（可为 null，null 时使用 content 的副本） */
	private MutableMessageContents sendContent;
	/** 推理内容（可为 null） */
	private MutableMessageContents reasonContent;
	/** 是否为有效的上下文 */
	private boolean shouldSend;
	/** 是否已删除 */
	private boolean deleted = false;
	/** 是否允许发送推理内容 */
	private boolean sendReasoner = true;
	/** 最后一次关联的应用状态 */
	private ApplicationState lastState;
	/** 关联的音频标识符 */
	private String audioId;

	/**
	 * 获取前一条目的标识符。
	 *
	 * @return 前一条目的标识符，-1 表示无前驱
	 */
	@Override
	public int getPrevIdentifier() {
		return prevIdentifier;
	}

	/**
	 * 设置前一条目的标识符。
	 *
	 * @param prevIdentifier 前一条目的标识符
	 */
	@Override
	public void setPrevIdentifier(int prevIdentifier) {
		this.prevIdentifier = prevIdentifier;
	}

	/**
	 * 使用角色、内容和有效上下文标志构造历史条目。
	 *
	 * @param role        角色
	 * @param content     显示内容
	 * @param shouldSend  是否为有效的上下文
	 */
	HistoryMemoryItem(Role role, String content, boolean shouldSend) {
		super();
		this.setContextContent(null);
		this.content = new MutableMessageContents(content);
		this.setRole(role);
		this.shouldSend = shouldSend;
	}

	/**
	 * 使用角色、显示内容和完整上下文内容构造历史条目（默认为有效上下文）。
	 *
	 * @param role        角色
	 * @param content     显示内容
	 * @param fullContent 完整上下文内容
	 */
	HistoryMemoryItem(Role role, String content, MessageContents fullContent) {
		super();
		this.setRole(role);
		this.content = new MutableMessageContents(content);
		this.sendContent = new MutableMessageContents(fullContent);
		this.shouldSend = true;
	}

	/**
	 * 使用标识符、角色、显示内容和完整上下文内容构造历史条目（默认为有效上下文）。
	 *
	 * @param identifier  条目标识符
	 * @param role        角色
	 * @param content     显示内容
	 * @param fullContent 完整上下文内容
	 */
	HistoryMemoryItem(int identifier, Role role, String content, MessageContents fullContent) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		this.content = new MutableMessageContents(content);
		this.sendContent = new MutableMessageContents(fullContent);
		this.shouldSend = true;
	}

	/**
	 * 使用标识符、角色、显示内容、上下文内容和有效上下文标志构造历史条目。
	 *
	 * @param identifier  条目标识符
	 * @param role        角色
	 * @param content     显示内容，可为 null
	 * @param fullContent 完整上下文内容
	 * @param shouldSend  是否为有效的上下文
	 */
	HistoryMemoryItem(int identifier, Role role, String content, MessageContents fullContent, boolean shouldSend) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		if (content != null)
			this.content = new MutableMessageContents(content);
		this.sendContent = new MutableMessageContents(fullContent);
		this.shouldSend = shouldSend;
	}

	/**
	 * 使用全部参数构造历史条目。
	 *
	 * @param identifier  条目标识符
	 * @param role        角色
	 * @param content     显示内容，可为 null
	 * @param fullContent 完整上下文内容，可为 null
	 * @param reasoner    推理内容，可为 null
	 * @param shouldSend  是否为有效的上下文
	 */
	HistoryMemoryItem(int identifier, Role role, MessageContents content, MessageContents fullContent, MessageContents reasoner,
			boolean shouldSend) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		if (content != null)
			this.content = new MutableMessageContents(content);
		if (fullContent != null)
			this.sendContent = new MutableMessageContents(fullContent);
		if (reasoner != null)
			this.reasonContent = new MutableMessageContents(reasoner);
		this.shouldSend = shouldSend;
	}

	/**
	 * 使用标识符、角色、内容和有效上下文标志构造历史条目（不设置上下文内容）。
	 *
	 * @param identifier 条目标识符
	 * @param role       角色
	 * @param content    显示内容
	 * @param shouldSend 是否为有效的上下文
	 */
	HistoryMemoryItem(int identifier, Role role, String content, boolean shouldSend) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		this.content = new MutableMessageContents(content);
		this.shouldSend = shouldSend;
	}

	/**
	 * 获取上下文内容。
	 * <p>
	 * 如果已设置独立的上下文内容（{@code sendContent != null}），则直接返回；
	 * 否则基于当前的显示内容创建一个新的 {@link MutableMessageContents} 作为上下文内容。
	 * </p>
	 *
	 * @return 上下文内容
	 */
	@Override
	public MutableMessageContents getContextContent() {
		if (sendContent != null)
			return sendContent;
		return new MutableMessageContents(content);
	}

	@Override
	public MessageContents getDisplayContent() {
		return content;
	}

	/**
	 * 判断是否已设置独立的上下文内容。
	 *
	 * @return {@code true} 如果已设置；{@code false} 否则
	 */
	public boolean hasContextContent() {
		return this.sendContent != null;
	}

	/**
	 * 创建上下文内容（懒初始化）。
	 * <p>
	 * 仅当 {@code sendContent} 为 null 时才基于当前显示内容创建。
	 * </p>
	 */
	public void createContextContent() {
		if (sendContent == null)
			this.sendContent = new MutableMessageContents(content.toString());
	}

	/**
	 * 创建或获取推理内容（懒初始化）。
	 * <p>
	 * 如果 {@code reasonContent} 为 null，则创建一个空的 {@link MutableMessageContents}。
	 * </p>
	 *
	 * @return 推理内容的可变容器
	 */
	public MutableMessageContents createReasonContent() {
		if (reasonContent == null)
			this.reasonContent = new MutableMessageContents();
		return reasonContent;
	}

	/**
	 * 追加一行内容（自动添加换行符），并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 双路写入逻辑：
	 * </p>
	 * <ul>
	 *   <li>显示内容（{@code content}）始终写入。</li>
	 *   <li>如果当前条目本身是有效上下文（{@code shouldSend == true}）：
	 *     <ul>
	 *       <li>若追加内容也应写入上下文（{@code shouldSend == true}），
	 *           且已有独立上下文内容，则同时写入上下文内容。</li>
	 *       <li>若追加内容不应写入上下文，则先创建上下文内容的快照
	 *           （以后续写入时保持之前的上下文内容不变）。</li>
	 *     </ul>
	 *   </li>
	 * </ul>
	 *
	 * @param content    要追加的内容
	 * @param shouldSend 是否同时写入上下文内容
	 */
	@Override
	public void appendLine(String content, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createContextContent();
			} else {
				if (sendContent != null) {
					sendContent.append(content).append("\n");
				}
			}
		}
		this.content.append(content).append("\n");
	}

	/**
	 * 追加内容（不自动添加换行符），并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 双路写入逻辑与 {@link #appendLine(String, boolean)} 相同，但不添加换行符。
	 * </p>
	 *
	 * @param content    要追加的内容
	 * @param shouldSend 是否同时写入上下文内容
	 */
	@Override
	public void append(String content, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createContextContent();
			} else {
				if (sendContent != null) {
					sendContent.append(content);
				}
			}
		}
		this.content.append(content);
	}

	@Override
	public void append(MessageContent mc, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createContextContent();
			} else {
				if (sendContent != null) {
					sendContent.add(mc.copy());
				}
			}
		}
		this.content.add(mc.copy());
	}

	/**
	 * 强制将内容追加到上下文内容中，显示内容不受影响。
	 *
	 * @param content 要追加到上下文的内容
	 */
	@Override
	public void appendContext(String content) {
		createContextContent();
		this.sendContent.append(content);
	}

	/**
	 * 设置完整的上下文内容。
	 *
	 * @param fullContent 上下文内容字符串，为 null 时清空上下文内容
	 */
	@Override
	public void setContextContent(String fullContent) {
		if (fullContent == null)
			this.sendContent = null;
		else
			this.sendContent = new MutableMessageContents(fullContent);
	}

	@Override
	public void appendReasoner(MessageContent fullContent) {
		createReasonContent().add(fullContent);
	}

	@Override
	public String toString() {
		return "HistoryItem [role=" + getRole() + ", fullContent=" + getContextContent() + "]";
	}

	@Override
	public Role getRole() {
		return role;
	}

	@Override
	public int getIdentifier() {
		return identifier;
	}

	/**
	 * 获取推理内容。
	 *
	 * @return 推理内容，未设置时返回 null
	 */
	@Override
	public MutableMessageContents getReasoningContent() {
		if (reasonContent == null)
			return null;
		return reasonContent;
	}

	@Override
	public boolean isValidContext() {
		return shouldSend;
	}

	/**
	 * 设置该条目是否为有效的上下文。
	 *
	 * @param sendable {@code true} 表示有效上下文
	 */
	public void setValidContext(boolean sendable) {
		this.shouldSend = sendable;
	}

	@Override
	public String getAudioId() {
		return audioId;
	}

	/**
	 * 设置关联的音频标识符。
	 *
	 * @param audioId 音频 ID 字符串，可为 null
	 */
	public void setAudioId(String audioId) {
		this.audioId = audioId;
	}

	@Override
	public ApplicationState getLastState() {
		return lastState;
	}

	@Override
	public void setLastState(ApplicationState lastState) {
		this.lastState = lastState;
	}

	/**
	 * 设置角色（包级可见）。
	 *
	 * @param role 角色
	 */
	void setRole(Role role) {
		this.role = role;
	}

	/**
	 * 设置条目标识符（包级可见）。
	 *
	 * @param identifier 标识符
	 */
	void setIdentifier(int identifier) {
		this.identifier = identifier;
	}

	@Override
	public boolean isDeleted() {
		return deleted;
	}

	/**
	 * 设置该条目的删除状态。
	 *
	 * @param sendable {@code true} 表示已删除
	 */
	public void setDeleted(boolean sendable) {
		deleted = sendable;
	}

	/**
	 * 设置推理内容（替换现有的推理内容）。
	 *
	 * @param reasonContent 推理内容字符串
	 */
	@Override
	public void setReasonContent(String reasonContent) {
		this.reasonContent = new MutableMessageContents(reasonContent);
	}

	@Override
	public long getTokenLength() {
		return tokenLength;
	}

	@Override
	public void setTokenLength(long tokenLength) {
		this.tokenLength = tokenLength;
	}

	@Override
	public boolean maySendReasoner() {
		return sendReasoner;
	}

	/**
	 * 设置是否允许将推理内容发送给客户端。
	 *
	 * @param sendReasoner {@code true} 允许发送
	 */
	public void setSendReasoner(boolean sendReasoner) {
		this.sendReasoner = sendReasoner;
	}
}
