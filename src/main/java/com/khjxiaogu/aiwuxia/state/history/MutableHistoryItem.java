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
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * 可修改的历史条目接口。
 * <p>
 * 扩展 {@link HistoryItem}，提供对条目内容的写操作，包括追加显示/上下文内容、
 * 设置上下文内容、追加推理内容以及设置各种元数据。
 * </p>
 *
 * @see HistoryItem
 * @see HistoryMemoryItem
 */
public interface MutableHistoryItem extends HistoryItem {

	/**
	 * 追加一行内容到当前条目的显示内容，并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 与 {@link #append(String, boolean)} 的区别在于此方法会自动添加换行符（{@code "\n"}）。
	 * </p>
	 *
	 * @param content      要追加的内容字符串
	 * @param addToContext 若为 {@code true}，则将该内容也追加到上下文内容中
	 */
	void appendLine(String content, boolean addToContext);

	/**
	 * 追加内容到当前条目的显示内容，并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 与 {@link #appendLine(String, boolean)} 的区别在于此方法不会自动添加换行符。
	 * </p>
	 *
	 * @param content      要追加的内容字符串
	 * @param addToContext 若为 {@code true}，则将该内容也追加到上下文内容中
	 */
	void append(String content, boolean addToContext);

	/**
	 * 追加多模态内容到当前条目的显示内容，并根据参数决定是否同时写入上下文内容。
	 * <p>
	 * 与 {@link #append(String, boolean)} 的区别在于接受 {@link MessageContent}，
	 * 可以追加图片、视频等非文本内容。
	 * </p>
	 *
	 * @param content      要追加的多模态内容
	 * @param addToContext 若为 {@code true}，则将该内容也追加到上下文内容中
	 */
	void append(MessageContent content, boolean addToContext);

	/**
	 * 强制将内容追加到上下文内容中，不影响显示内容或推理内容。
	 *
	 * @param content 要追加到上下文的内容字符串
	 */
	void appendContext(String content);

	/**
	 * 设置完整的上下文内容，替换现有的上下文内容。
	 * <p>
	 * 传入 {@code null} 可清空上下文内容。
	 * </p>
	 *
	 * @param fullContent 新的完整上下文内容字符串，可为 null
	 */
	void setContextContent(String fullContent);

	/**
	 * 追加推理器相关的内容。
	 * <p>
	 * 用于记录 AI 模型的推理过程或中间结果。
	 * </p>
	 *
	 * @param currentReasoner 要追加的推理内容
	 */
	void appendReasoner(MessageContent currentReasoner);

	/**
	 * 设置该条目最后一次关联的应用状态。
	 *
	 * @param lastState {@link ApplicationState} 对象，可为 null
	 */
	void setLastState(ApplicationState lastState);

	/**
	 * 设置前一条目的标识符，用于链表式追溯。
	 *
	 * @param prevIdentifier 前一条目的标识符，-1 表示无前驱
	 */
	void setPrevIdentifier(int prevIdentifier);

	/**
	 * 设置推理内容（替换现有的推理内容）。
	 *
	 * @param reasonContent 推理内容字符串
	 */
	void setReasonContent(String reasonContent);

	/**
	 * 设置该条目的令牌长度。
	 *
	 * @param tokenLength 令牌数量
	 */
	void setTokenLength(long tokenLength);

}
