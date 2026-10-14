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
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;

/**
 * 未完成（pending）的历史条目。
 * <p>
 * 由 {@link HistoryHolder#beginPending(Role, boolean)} 创建：创建时就已经从容器分配好了标识符，
 * 但不会插入历史容器，因此不参与上下文构建、总结清理与历史分页，也不会在
 * {@link HistoryHolder#flush()} 时落盘。应用在单次请求中只写入该条目：
 * </p>
 * <ul>
 *   <li>写完后调用 {@link HistoryHolder#commitPending(HistoryItem)}，条目按标识符顺序
 *       插入到历史记录中它应在的位置，并清除未完成状态。</li>
 *   <li>不想保留时调用 {@link HistoryHolder#rollbackPending(HistoryItem)}，条目被硬删除，
 *       并在其标识符仍是最新分配的标识符时回滚标识符，保证下一轮可以复用该标识符。</li>
 * </ul>
 * <p>
 * 内容存储直接复用 {@link HistoryMemoryItem} 的实现：提交后条目本身就成为历史条目，
 * 后续追加、上下文与推理内容的写入语义与新建的历史条目完全一致。
 * </p>
 *
 * @see HistoryHolder#beginPending(Role, boolean)
 * @see HistoryHolder#commitPending(HistoryItem)
 * @see HistoryHolder#rollbackPending(HistoryItem)
 */
class PendingHistoryItem extends HistoryMemoryItem {

	/** 序列化版本标识 */
	private static final long serialVersionUID = 4652360446459027211L;

	/** 是否仍处于未完成状态（未提交进历史） */
	private boolean pending = true;

	/**
	 * 构造一条未完成的历史条目。
	 *
	 * @param identifier     创建时分配的标识符
	 * @param role           条目角色
	 * @param isValidContext 是否为有效的上下文
	 */
	PendingHistoryItem(int identifier, Role role, boolean isValidContext) {
		super(identifier, role, new MutableMessageContents(), null, null, isValidContext);
	}

	@Override
	public boolean isPending() {
		return pending;
	}

	/**
	 * 设置未完成状态。
	 *
	 * @param pending {@code true} 表示仍处于未完成状态
	 */
	void setPending(boolean pending) {
		this.pending = pending;
	}
}
