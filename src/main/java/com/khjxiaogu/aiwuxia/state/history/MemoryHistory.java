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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.ReverseIterator;

/**
 * {@link HistoryHolder} 的基于内存的实现，支持序列化。
 * <p>
 * 内部使用 {@link ArrayList} 存储 {@link HistoryMemoryItem} 条目，
 * 使用 {@link AtomicInteger} 生成全局唯一且递增的条目标识符。
 * 添加新条目时会自动将 {@code prevIdentifier} 设置为上一个条目的标识符。
 * </p>
 * <p>
 * 提供多种迭代器：
 * </p>
 * <ul>
 *   <li>{@link NonDeletedIterator}：过滤已删除条目（{@code isDeleted() == false}）。</li>
 *   <li>{@link SendableIterator}：过滤已删除且无效上下文的条目
 *       （{@code isValidContext() && !isDeleted()}）。</li>
 * </ul>
 *
 * @see HistoryMemoryItem
 */
public class MemoryHistory implements Serializable, HistoryHolder {

	/** 序列化版本标识 */
	private static final long serialVersionUID = 8307578868037728518L;

	/** 历史条目列表 */
	List<HistoryMemoryItem> history = new ArrayList<>();
	/** 唯一标识符生成器 */
	AtomicInteger idgenerator = new AtomicInteger();


	/**
	 * 构造一个空的内存历史容器。
	 */
	public MemoryHistory() {
		super();
	}

	@Override
	public boolean isEmpty() {
		return history.isEmpty();
	}

	/**
	 * 返回正向迭代器，过滤已删除的条目。
	 *
	 * @return 正向 {@link NonDeletedIterator}
	 */
	@Override
	public Iterator<HistoryItem> iterator() {
		return new NonDeletedIterator<>(this, history.iterator());
	}

	/**
	 * 添加一个新的历史条目。
	 * <p>
	 * 新条目的标识符由 {@link #newUniqueId()} 自动生成，
	 * {@code prevIdentifier} 自动设置为容器中最后一个条目的标识符。
	 * </p>
	 *
	 * @param role        角色
	 * @param content     显示内容
	 * @param fullContent 上下文内容，可为 null
	 * @param reasoner    推理内容，可为 null
	 * @param isSendable  是否为有效的上下文
	 * @return 新创建的可变历史条目
	 */
	@Override
	public synchronized MutableHistoryItem add(Role role, MessageContents content, MessageContents fullContent,
			MessageContents reasoner, boolean isSendable) {
		HistoryMemoryItem mhi;
		HistoryItem last = peekLast();
		history.add(mhi = new HistoryMemoryItem(newUniqueId(), role, content, fullContent, reasoner, isSendable));
		if (last != null)
			mhi.setPrevIdentifier(last.getIdentifier());
		return mhi;
	}

	@Override
	public synchronized void clear() {
		history.clear();
	}

	/**
	 * 按索引移除条目。
	 *
	 * @param index 要移除的条目索引
	 * @return 被移除的条目
	 */
	public synchronized HistoryItem remove(int index) {
		return history.remove(index);
	}

	@Override
	public int size() {
		return history.size();
	}

	/**
	 * 按标识符移除条目。
	 * <p>
	 * 从反向迭代器中查找第一个匹配的条目并移除。
	 * </p>
	 *
	 * @param identifier 要移除的条目的标识符
	 */
	@Override
	public synchronized void removeOf(int identifier) {
		for (Iterator<HistoryItem> it = reverseIterator(); it.hasNext();) {
			HistoryItem hi = it.next();
			if (hi.getIdentifier() == identifier) {
				it.remove();
			}
		}
	}

	@Override
	public HistoryItem getById(int id) {
		for (HistoryMemoryItem hmi : history) {
			if (hmi.getIdentifier() == id) {
				return hmi;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return "[" + history + "]";
	}

	/**
	 * 返回反向迭代器（从最新到最旧），过滤已删除的条目。
	 *
	 * @return 反向 {@link NonDeletedIterator}
	 */
	@Override
	public Iterator<HistoryItem> reverseIterator() {
		return new NonDeletedIterator<>(this, new ReverseIterator<>(history));
	}

	/**
	 * 生成一个新的唯一标识符。
	 * <p>
	 * 使用 {@link AtomicInteger#incrementAndGet()} 生成递增的 ID。
	 * 如果生成的值小于当前列表大小，则调整为列表大小以确保唯一性。
	 * </p>
	 *
	 * @return 新的唯一标识符
	 */
	public int newUniqueId() {
		int num = idgenerator.incrementAndGet();
		if (num < history.size()) {
			num = history.size();
			idgenerator.set(num);
		}

		return num;
	}

	/**
	 * 可发送条目迭代器。
	 * <p>
	 * 仅遍历 {@code isValidContext() == true && !isDeleted()} 的条目。
	 * </p>
	 * <p>
	 * 注意：{@link #remove()} 会先将当前条目标记为无效上下文，
	 * 然后抛出 {@link UnsupportedOperationException} —— 因此条目不会被真正移除，
	 * 仅失去有效上下文标志。
	 * </p>
	 *
	 * @param <T> 条目类型，必须扩展 {@link HistoryItem}
	 */
	public static class SendableIterator<T extends HistoryItem> implements Iterator<T> {

		/** 底层迭代器 */
		private final Iterator<? extends T> iterator;
		/** 缓存的下一个符合条件的元素 */
		private T nextItem;
		/** 所属的历史容器，用于 remove() 操作 */
		private HistoryHolder holder;

		/**
		 * 使用底层迭代器构造可发送条目迭代器。
		 *
		 * @param holder   所属的历史容器
		 * @param iterator 底层迭代器
		 */
		public SendableIterator(HistoryHolder holder, Iterator<? extends T> iterator) {
			this.iterator = iterator;
			this.holder = holder;
			advance();
		}

		/**
		 * 使用列表构造可发送条目迭代器。
		 *
		 * @param holder 所属的历史容器
		 * @param list   条目列表
		 */
		public SendableIterator(HistoryHolder holder, List<? extends T> list) {
			this(holder, list.iterator());
		}

		/**
		 * 预加载下一个符合条件的元素。
		 */
		private void advance() {
			while (iterator.hasNext()) {
				T item = iterator.next();
				if (item.isValidContext() && !item.isDeleted()) {
					nextItem = item;
					return;
				}
			}
			nextItem = null;
		}

		@Override
		public boolean hasNext() {
			return nextItem != null;
		}

		@Override
		public T next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			T result = nextItem;
			advance();
			return result;
		}

		/**
		 * 将当前条目标记为无效上下文。
		 * <p>
		 * 调用后立即抛出 {@link UnsupportedOperationException}，
		 * 因此调用方不应依赖此方法移除元素。
		 * </p>
		 *
		 * @throws UnsupportedOperationException 始终抛出
		 */
		@Override
		public void remove() {
			if (nextItem == null)
				throw new NoSuchElementException();
			holder.setValidContext(nextItem, false);
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * 非删除条目迭代器。
	 * <p>
	 * 仅遍历 {@code !isDeleted()} 的条目（即未被标记为删除的条目）。
	 * </p>
	 * <p>
	 * 注意：{@link #remove()} 通过 {@code setDeleted(true)} 将当前条目标记为已删除，
	 * 而非从列表中真正移除，然后抛出 {@link UnsupportedOperationException}。
	 * </p>
	 *
	 * @param <T> 条目类型，必须扩展 {@link HistoryItem}
	 */
	public static class NonDeletedIterator<T extends HistoryItem> implements Iterator<T> {

		/** 底层迭代器 */
		private final Iterator<? extends T> iterator;
		/** 缓存的下一个符合条件的元素 */
		private T nextItem;
		/** 上一次返回的元素，供 remove() 使用 */
		private T toRemove;
		/** 所属的历史容器 */
		private HistoryHolder holder;

		/**
		 * 使用底层迭代器构造非删除条目迭代器。
		 *
		 * @param holder   所属的历史容器
		 * @param iterator 底层迭代器
		 */
		public NonDeletedIterator(HistoryHolder holder, Iterator<? extends T> iterator) {
			this.iterator = iterator;
			this.holder = holder;
			advance();
		}

		/**
		 * 使用列表构造非删除条目迭代器。
		 *
		 * @param holder 所属的历史容器
		 * @param list   条目列表
		 */
		public NonDeletedIterator(HistoryHolder holder, List<? extends T> list) {
			this(holder, list.iterator());
		}

		/**
		 * 预加载下一个未被删除的元素，并记录上一元素供 remove() 使用。
		 */
		private void advance() {
			toRemove = nextItem;
			while (iterator.hasNext()) {
				T item = iterator.next();
				if (!item.isDeleted()) {
					nextItem = item;
					return;
				}
			}
			nextItem = null;
		}

		@Override
		public boolean hasNext() {
			return nextItem != null;
		}

		@Override
		public T next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			T result = nextItem;
			advance();
			return result;
		}

		/**
		 * 将上一个通过 {@link #next()} 返回的条目标记为已删除。
		 * <p>
		 * 调用后立即抛出 {@link UnsupportedOperationException}，
		 * 条目仅被标记删除，不会从列表中真正移除。
		 * </p>
		 *
		 * @throws UnsupportedOperationException 始终抛出
		 */
		@Override
		public void remove() {
			if (toRemove == null)
				throw new NoSuchElementException();
			holder.setDeleted(toRemove, true);
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * 返回有效上下文迭代器，仅遍历同时满足有效上下文和未删除条件的条目。
	 *
	 * @return {@link SendableIterator}
	 */
	@Override
	public Iterator<HistoryItem> validContextIterator() {
		return new SendableIterator<>(this, history.iterator());
	}

	/**
	 * 返回可修改的有效上下文迭代器。
	 *
	 * @return {@link SendableIterator}
	 */
	public Iterator<MutableHistoryItem> modifiableValidContextIterator() {
		return new SendableIterator<>(this, history.iterator());
	}

	/**
	 * 移除并返回最后一个未被删除的条目。
	 *
	 * @return 被移除的条目，如果没有未被删除的条目则返回 null
	 */
	@Override
	public HistoryItem removeLast() {
		if (history.isEmpty())
			return null;
		for (int i = history.size() - 1; i >= 0; i--) {
			if (!history.get(i).isDeleted())
				return history.remove(i);
		}
		return null;
	}

	/**
	 * 查看最后一个未被删除的条目（不移除）。
	 *
	 * @return 最后一个条目，如果没有则返回 null
	 */
	@Override
	public MutableHistoryItem peekLast() {
		Iterator<HistoryItem> rit = reverseIterator();
		if (rit.hasNext())
			return (MutableHistoryItem) rit.next();
		return null;
	}

	/**
	 * 将最后一个未被删除的条目标记为已删除（软删除，不从列表中移除）。
	 *
	 * @return 被标记删除的条目
	 */
	@Override
	public HistoryItem deleteLast() {
		HistoryItem last = peekLast();
		this.setDeleted(last, true);
		return last;
	}

	/**
	 * 获取上下文上限。
	 * <p>
	 * 统计满足以下条件的条目数：有效上下文、未删除、角色为 {@link Role#ASSISTANT}。
	 * </p>
	 *
	 * @return 符合条件的条目数量
	 */
	@Override
	public long getContextLimit() {
		long limit = 0;
		for (HistoryMemoryItem hmi : history) {
			if (hmi.isValidContext() && !hmi.isDeleted() && hmi.getRole() == Role.ASSISTANT) {
				limit++;
			}
		}
		return limit;
	}

	/**
	 * 设置指定条目的有效上下文标志。
	 *
	 * @param hi       历史条目，必须为 {@link HistoryMemoryItem} 实例
	 * @param sendable 是否为有效上下文
	 * @throws IllegalArgumentException 如果条目不是 {@link HistoryMemoryItem}
	 */
	@Override
	public void setValidContext(HistoryItem hi, boolean sendable) {
		if (hi instanceof HistoryMemoryItem) {
			HistoryMemoryItem mhi = (HistoryMemoryItem) hi;
			mhi.setValidContext(sendable);
			return;
		}
		throw new IllegalArgumentException("Not a valid memory history");
	}

	/**
	 * 设置指定条目的删除状态。
	 *
	 * @param hi       历史条目，必须为 {@link HistoryMemoryItem} 实例
	 * @param sendable 是否标记为已删除
	 * @throws IllegalArgumentException 如果条目不是 {@link HistoryMemoryItem}
	 */
	@Override
	public void setDeleted(HistoryItem hi, boolean sendable) {
		if (hi instanceof HistoryMemoryItem) {
			HistoryMemoryItem mhi = (HistoryMemoryItem) hi;
			mhi.setDeleted(sendable);
			return;
		}
		throw new IllegalArgumentException("Not a valid memory history");
	}

	/**
	 * 设置指定条目的音频标识符。
	 *
	 * @param hi      历史条目，必须为 {@link HistoryMemoryItem} 实例
	 * @param audioId 音频 ID 字符串
	 * @throws IllegalArgumentException 如果条目不是 {@link HistoryMemoryItem}
	 */
	@Override
	public void setAudioId(HistoryItem hi, String audioId) {
		if (hi instanceof HistoryMemoryItem) {
			HistoryMemoryItem mhi = (HistoryMemoryItem) hi;
			mhi.setAudioId(audioId);
			return;
		}
		throw new IllegalArgumentException("Not a valid memory history");
	}

	/**
	 * 设置指定条目是否允许发送推理内容。
	 *
	 * @param hi           历史条目，必须为 {@link HistoryMemoryItem} 实例
	 * @param sendReasoner 是否允许发送推理内容
	 * @throws IllegalArgumentException 如果条目不是 {@link HistoryMemoryItem}
	 */
	@Override
	public void setSendReasoner(HistoryItem hi, boolean sendReasoner) {
		if (hi instanceof HistoryMemoryItem) {
			HistoryMemoryItem mhi = (HistoryMemoryItem) hi;
			mhi.setSendReasoner(sendReasoner);
			return;
		}
		throw new IllegalArgumentException("Not a valid memory history");
	}

	/**
	 * 设置指定条目的最后应用状态。
	 *
	 * @param hi        历史条目，必须为 {@link HistoryMemoryItem} 实例
	 * @param lastState 应用状态
	 * @throws IllegalArgumentException 如果条目不是 {@link HistoryMemoryItem}
	 */
	@Override
	public void setLastState(HistoryItem hi, ApplicationState lastState) {
		if (hi instanceof HistoryMemoryItem) {
			HistoryMemoryItem mhi = (HistoryMemoryItem) hi;
			mhi.setLastState(lastState);
			return;
		}
		throw new IllegalArgumentException("Not a valid memory history");
	}

	/**
	 * 设置指定条目的令牌长度。
	 *
	 * @param hi 历史条目，必须为 {@link HistoryMemoryItem} 实例
	 * @param l  令牌长度
	 * @throws IllegalArgumentException 如果条目不是 {@link HistoryMemoryItem}
	 */
	@Override
	public void setTokenLength(HistoryItem hi, long l) {
		if (hi instanceof HistoryMemoryItem) {
			HistoryMemoryItem mhi = (HistoryMemoryItem) hi;
			mhi.setTokenLength(l);
			return;
		}
		throw new IllegalArgumentException("Not a valid memory history");
	}

	/**
	 * 向最后一个条目追加一行内容（委托给 {@link #peekLast()}）。
	 *
	 * @param content      要追加的内容
	 * @param addToContext 是否同时写入上下文
	 */
	@Override
	public void appendLine(String content, boolean addToContext) {
		MutableHistoryItem mhi = peekLast();
		mhi.appendLine(content, addToContext);
	}

	/**
	 * 向最后一个条目追加内容（委托给 {@link #peekLast()}）。
	 *
	 * @param content      要追加的内容
	 * @param addToContext 是否同时写入上下文
	 */
	@Override
	public void append(String content, boolean addToContext) {
		MutableHistoryItem mhi = peekLast();
		mhi.append(content, addToContext);
	}

	/**
	 * 向最后一个条目的上下文内容追加内容（委托给 {@link #peekLast()}）。
	 *
	 * @param content 要追加到上下文的内容
	 */
	@Override
	public void appendContext(String content) {
		MutableHistoryItem mhi = peekLast();
		mhi.appendContext(content);
	}

	@Override
	public void setAudioId(String audioId) {
		setAudioId(peekLast(),audioId);
	}

	@Override
	public void setLastState(ApplicationState lastState) {
		setLastState(peekLast(),lastState);
	}

	@Override
	public void flush() {
	}

	@Override
	public void append(MessageContent content, boolean addToContext) {
		MutableHistoryItem mhi = peekLast();
		mhi.append(content, addToContext);
	}

	@Override
	public void appendReasoner(MessageContent current) {
		MutableHistoryItem mhi = peekLast();
		mhi.appendReasoner(current);
	}
}
