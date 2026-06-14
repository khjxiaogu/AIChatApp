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

import java.util.Iterator;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * 历史条目容器接口。
 * <p>
 * 扩展 {@link Iterable}{@code <HistoryItem>}，用于存储和管理多个 {@link HistoryItem} 条目。
 * 提供正向遍历、反向遍历、有效上下文过滤遍历等多种迭代方式，
 * 以及添加、移除、查看最后一个条目等操作方法。
 * 多个 {@code default add(...)} 便捷方法允许省略部分参数，使用合理的默认值。
 * </p>
 * <p>
 * {@link #appendLine(String, boolean)}、{@link #append(String, boolean)} 和
 * {@link #appendContext(String)} 方法会委托给最后一个条目的对应方法。
 * </p>
 *
 * @see MemoryHistory
 * @see HistoryItem
 */
public interface HistoryHolder extends Iterable<HistoryItem> {

	/**
	 * 判断该容器是否为空。
	 *
	 * @return {@code true} 如果不包含任何历史条目；{@code false} 否则
	 */
	boolean isEmpty();

	/**
	 * 返回一个按添加顺序正向遍历所有历史条目的迭代器。
	 *
	 * @return 正向迭代器
	 */
	Iterator<HistoryItem> iterator();

	/**
	 * 向容器中添加一个新的历史条目。
	 *
	 * @param role           条目的角色（如用户、助手、系统等）
	 * @param content        条目的显示内容
	 * @param fullContent    条目的完整上下文内容，可为 null
	 * @param reasoner       条目的推理内容，可为 null
	 * @param isValidContext 指示该条目是否可作为有效的上下文内容
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	HistoryItem add(Role role, MessageContents content, MessageContents fullContent, MessageContents reasoner,
			boolean isValidContext);

	/**
	 * 清空容器，移除所有历史条目。
	 */
	void clear();

	/**
	 * 返回一个按添加顺序逆向（从最新到最旧）遍历所有历史条目的迭代器。
	 *
	 * @return 反向迭代器
	 */
	Iterator<HistoryItem> reverseIterator();

	/**
	 * 返回当前容器中历史条目的总数。
	 *
	 * @return 条目总数
	 */
	int size();

	/**
	 * 根据唯一标识符移除对应的历史条目。
	 *
	 * @param identifier 要移除的条目的标识符（参见 {@link HistoryItem#getIdentifier()}）
	 */
	void removeOf(int identifier);

	/**
	 * 查看容器中的最后一个历史条目（即最新添加的），不会将其移除。
	 *
	 * @return 最后一个 {@link HistoryItem}，如果容器为空则返回 null
	 */
	default HistoryItem peekLast() {
		Iterator<HistoryItem> rit = reverseIterator();
		if (rit.hasNext())
			return rit.next();
		return null;
	}

	/**
	 * 返回一个迭代器，仅遍历被标记为"有效上下文"的条目。
	 *
	 * @return 仅包含有效上下文条目的迭代器
	 */
	Iterator<HistoryItem> validContextIterator();

	/**
	 * 添加一个新的历史条目，省略上下文内容（设为 null）。
	 *
	 * @param role           条目的角色
	 * @param displayContent 显示内容
	 * @param reasoner       推理内容，可为 null
	 * @param isValidContext 是否为有效的上下文
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	default HistoryItem add(Role role, String displayContent, MessageContents reasoner, boolean isValidContext) {
		return add(role,new MutableMessageContents(displayContent), (MessageContents) null, reasoner, isValidContext);
	}

	/**
	 * 添加一个新的历史条目，省略上下文内容和推理内容（均设为 null）。
	 *
	 * @param role           条目的角色
	 * @param displayContent 显示内容
	 * @param isValidContext 是否为有效的上下文
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	default HistoryItem add(Role role, String displayContent, boolean isValidContext) {
		return add(role, new MutableMessageContents(displayContent), isValidContext?null:new MutableMessageContents(), null, true);
	}

	/**
	 * 添加一个新的历史条目，使用 {@link MessageContents} 作为显示内容，
	 * 其文本表示作为字符串显示内容。
	 *
	 * @param role           条目的角色
	 * @param displayContent 显示内容（将取其文本表示作为字符串）
	 * @param reasoner       推理内容，可为 null
	 * @param isValidContext 是否为有效的上下文
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	default HistoryItem add(Role role, MessageContents displayContent, MessageContents reasoner,
			boolean isValidContext) {
		return add(role, displayContent, isValidContext?null:new MutableMessageContents(), reasoner, true);
	}

	/**
	 * 添加一个新的历史条目，默认标记为有效上下文。
	 *
	 * @param role           条目的角色
	 * @param displayContent 显示内容
	 * @param contextContent 上下文内容，可为 null
	 * @param reasoner       推理内容，可为 null
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	default HistoryItem add(Role role, String displayContent, MessageContents contextContent, MessageContents reasoner) {
		return add(role, new MutableMessageContents(displayContent), contextContent, reasoner, true);
	}

	/**
	 * 添加一个新的历史条目，仅使用显示内容和上下文内容的字符串形式。
	 *
	 * @param role           条目的角色
	 * @param displayContent 显示内容
	 * @param contextContent 上下文内容
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	default HistoryItem add(Role role, String displayContent, String contextContent) {
		return add(role, new MutableMessageContents(displayContent), new MutableMessageContents(contextContent), null, true);
	}

	/**
	 * 添加一个新的历史条目，使用字符串形式的上下文内容和推理内容。
	 *
	 * @param role           条目的角色
	 * @param displayContent 显示内容
	 * @param contextContent 上下文内容
	 * @param reasoner       推理内容
	 * @return 新创建并添加的 {@link HistoryItem} 对象
	 */
	default HistoryItem add(Role role, String displayContent, String contextContent, MessageContents reasoner) {
		return add(role, new MutableMessageContents(displayContent), new MutableMessageContents(contextContent), reasoner, true);
	}

	/**
	 * 移除并返回容器中的最后一个历史条目（即最新添加的条目）。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem}，如果容器为空则可能返回 null
	 */
	HistoryItem removeLast();

	/**
	 * 获取上下文上限。
	 * <p>
	 * 具体含义由实现决定，通常表示当前的上下文数量（如有效且未删除的助手条目数）。
	 * </p>
	 *
	 * @return 上下文上限值
	 */
	public long getContextLimit();

	/**
	 * 将最后一个条目标记为已删除（软删除，不真正移除）。
	 *
	 * @return 被标记删除的条目
	 */
	HistoryItem deleteLast();

	/**
	 * 设置指定条目的有效上下文标志。
	 *
	 * @param hi       历史条目
	 * @param sendable 是否为有效上下文
	 */
	void setValidContext(HistoryItem hi, boolean sendable);

	/**
	 * 设置指定条目的删除状态。
	 *
	 * @param hi       历史条目
	 * @param sendable 是否标记为已删除
	 */
	void setDeleted(HistoryItem hi, boolean sendable);

	/**
	 * 设置指定条目的音频标识符。
	 *
	 * @param hi      历史条目
	 * @param audioId 音频 ID 字符串
	 */
	void setAudioId(HistoryItem hi, String audioId);

	/**
	 * 设置最后条目的音频标识符。
	 *
	 * @param audioId 音频 ID 字符串
	 */
	void setAudioId(String audioId);
	
	/**
	 * 设置指定条目是否允许发送推理内容。
	 *
	 * @param hi           历史条目
	 * @param sendReasoner 是否允许发送推理内容
	 */
	void setSendReasoner(HistoryItem hi, boolean sendReasoner);

	/**
	 * 设置指定条目的令牌长度。
	 *
	 * @param hi 历史条目
	 * @param l  令牌长度
	 */
	void setTokenLength(HistoryItem hi, long l);

	/**
	 * 设置指定条目的最后应用状态。
	 *
	 * @param hi        历史条目
	 * @param lastState 应用状态
	 */
	void setLastState(HistoryItem hi, ApplicationState lastState);
	/**
	 * 设置最后条目的最后应用状态。
	 *
	 * @param lastState 应用状态
	 */
	void setLastState(ApplicationState lastState);
	/**
	 * 根据唯一标识符查找对应的历史条目。
	 *
	 * @param id 条目标识符
	 * @return 对应的 {@link HistoryItem}，如果不存在则返回 null
	 */
	HistoryItem getById(int id);

	/**
	 * 获取给定条目前面第一个非 null 的应用状态。
	 * <p>
	 * 沿 {@code prevIdentifier} 链表向前追溯，返回遇到的第一个
	 * {@link HistoryItem#getLastState()} 不为 null 的状态。
	 * 从当前条目自身开始检查，如果其状态非 null 则直接返回。
	 * </p>
	 *
	 * @param item 起始条目
	 * @return 第一个非 null 的 {@link ApplicationState}，如果没有找到则返回 null
	 */
	default ApplicationState getStateAt(HistoryItem item) {
		HistoryItem current = item;
		while (current != null) {
			ApplicationState state = current.getLastState();
			if (state != null) {
				return state;
			}
			int prevId = current.getPrevIdentifier();
			if (prevId < 0) {
				return null;
			}
			current = getById(prevId);
		}
		return null;
	}

	/**
	 * 向最后一个条目追加一行内容，并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 委托给 {@link #peekLast()} 返回的可变条目执行。
	 * </p>
	 *
	 * @param content      要追加的内容字符串
	 * @param addToContext 若为 {@code true}，则将该内容也追加到上下文内容中
	 */
	void appendLine(String content, boolean addToContext);


	/**
	 * 向最后一个条目追加内容（不自动添加换行符），并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 委托给 {@link #peekLast()} 返回的可变条目执行。
	 * 与 {@link #appendLine(String, boolean)} 的区别在于不添加换行符。
	 * </p>
	 *
	 * @param content      要追加的内容字符串
	 * @param addToContext 若为 {@code true}，则将该内容也追加到上下文内容中
	 */
	void append(String content, boolean addToContext);

	/**
	 * 向最后一个条目追加多模态内容（不自动添加换行符），并根据参数决定是否同时写入上下文内容。
	 *
	 * @param content      要追加的多模态内容
	 * @param addToContext 若为 {@code true}，则将该内容也追加到上下文内容中
	 */
	void append(MessageContent content, boolean addToContext);

	/**
	 * 向最后一个条目的上下文内容追加内容。
	 * <p>
	 * 委托给 {@link #peekLast()} 返回的可变条目执行。
	 * </p>
	 *
	 * @param content 要追加到上下文的内容字符串
	 */
	void appendContext(String content);

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
	void flush();

	void appendReasoner(MessageContent current);

}
