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

import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;

/**
 * 可写入的历史记录上下文。
 * <p>
 * 应用不直接向会话或历史容器追加内容，而是先取得一个可写入上下文，再通过它写入本轮的内容：
 * </p>
 * <pre>
 * try (HistoryWriteContext write = session.beginWrite(Role.ASSISTANT)) {
 *     write.appendReasoner(thinking);
 *     write.appendLine(answer, true);
 * }
 * </pre>
 * <p>
 * 上下文既可以对应一条<b>新记录</b>（由 {@code beginWrite} 打开：取得时就已经分配好标识符，
 * 但尚未进入历史容器，因此不参与上下文构建、总结清理与历史分页，也不会落盘），
 * 也可以对应<b>最后一条同角色记录</b>（由 {@code continueWrite} 打开：记录本来就在历史里，
 * 用于把多次到达的输入拼进同一条记录）。
 * </p>
 * <p>
 * 结束方式：
 * </p>
 * <ul>
 *   <li>try-with-resources 正常结束调用 {@link #close()}：新记录写过内容就提交进历史，
 *       只写了思维链之类的空记录直接撤回；续写记录不做任何提交动作。</li>
 *   <li>{@link #commit()}：显式提交（把新记录插入到历史记录的正确位置）。</li>
 *   <li>{@link #rollback()}：显式撤回（硬删除该记录，并通知前端移除对应消息）。</li>
 * </ul>
 * <p>
 * 上下文结束（提交或撤回）之后再写入会抛出 {@link IllegalStateException}，
 * 重复调用 {@link #commit()}、{@link #rollback()}、{@link #close()} 则是空操作。
 * </p>
 *
 * @see MutableHistoryItem
 */
public interface HistoryWriteContext extends MutableHistoryItem, AutoCloseable {

	/**
	 * 提交本次写入的记录。
	 * <p>
	 * 清除未完成状态，并按标识符顺序插入到历史记录中它应在的位置；续写已有记录时记录本来就在历史里，
	 * 只做收尾处理。已经提交或撤回时为空操作。
	 * </p>
	 */
	void commit();

	/**
	 * 撤回本次写入的记录（硬删除）。
	 * <p>
	 * 新记录从未进入历史，直接丢弃，并在其标识符仍是最新分配时回滚标识符；
	 * 续写已有记录时把该记录从历史中移除。已经提交或撤回时为空操作。
	 * </p>
	 */
	void rollback();

	/**
	 * 结束本次写入。
	 * <p>
	 * 没有显式提交或撤回时按内容结算：写过显示内容或上下文内容就提交，
	 * 只写了思维链的空记录直接撤回；续写已有记录时只清理状态。
	 * </p>
	 * <p>
	 * 提交、撤回与关闭都会先等待本记录上尚未收尾的后台异步写入（例如后台读流写思维链），
	 * 因此方法返回后本记录不会再有新的写入。
	 * </p>
	 */
	@Override
	void close();

	/**
	 * 本次写入是否已经结束（已提交或已撤回）。
	 *
	 * @return 已结束时返回 {@code true}
	 */
	boolean isResolved();

	/**
	 * 追加一段思维链内容，并返回本次是否真的写入。
	 * <p>
	 * 后台异步读流使用本方法：本记录已经提交或撤回时返回 {@code false}，
	 * 读流据此收工，不会把内容写到已经定型的记录上。
	 * </p>
	 *
	 * @param reasoner 本次追加的思维链内容
	 * @return 已写入返回 {@code true}；记录已经结束、本次内容被丢弃时返回 {@code false}
	 */
	default boolean appendReasonerIfOpen(MessageContent reasoner) {
		appendReasoner(reasoner);
		return true;
	}

	/**
	 * 追加一整组内容到本记录。
	 *
	 * @param contents     要追加的内容集合，空集合不会产生任何写入
	 * @param addToContext 若为 {@code true}，则同时写入上下文内容
	 */
	default void append(MessageContents contents, boolean addToContext) {
		for (MessageContent content : contents)
			append(content, addToContext);
	}

	/**
	 * 追加一条只给用户看、不进入模型上下文的内容（例如界面提示语）。
	 *
	 * @param content 要追加的内容字符串
	 */
	default void appendDisplayOnly(String content) {
		setContextContent("");
		append(content, false);
	}

	/**
	 * 追加一段只给用户看、不进入模型上下文的内容（例如多模态提示语）。
	 *
	 * @param contents 要追加的内容集合
	 */
	default void appendDisplayOnly(MessageContents contents) {
		setContextContent("");
		append(contents, false);
	}
}
