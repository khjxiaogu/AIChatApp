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
package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;

import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;

/**
 * 过渡适配器：把 {@link MessageReader} 的内容流还原成逐字符读取的 {@link Reader}。
 * <p>
 * 正文统一改为按 {@link MessageContent} 分段流式给出之后，仍按字符流处理正文的应用
 * （{@code new BufferedReader(...)} 逐行读、{@code read(char[])} 逐块读等）可以通过
 * {@link com.khjxiaogu.aiwuxia.llm.AIOutput#getContentReader()} 取得本适配器，
 * 不必改写自己已有的读取逻辑：
 * </p>
 * <pre>
 * BufferedReader reader = new BufferedReader(resp.getContentReader());
 * String line;
 * while ((line = reader.readLine()) != null) {
 *     // 与旧版逐行读取完全一致
 * }
 * </pre>
 * <p>
 * 纯文本段落按原样展开进字符流，因此逐字符、逐行读取得到的文本与内容段的先后顺序一致；
 * 图片、视频、工具调用等非纯文本内容不会进入字符流（避免把图片描述之类的文本混进正文），
 * 需要它们时用 {@link #pollMessage()} 依次取出。读取在内容流上阻塞等待新内容，
 * 内容流结束时 {@link #read(char[], int, int)} 返回 {@code -1}，读流失败则以
 * {@link IOException} 的形式抛出。
 * </p>
 * <p>
 * 本类只做读取方向的适配，写入仍然直接作用于内部的 {@link MessageReader}，
 * 同一个适配器实例应由一个读取线程独占使用。
 * </p>
 *
 * @see MessageReader
 * @see com.khjxiaogu.aiwuxia.llm.AIOutput#getContentReader()
 */
public class MessageReaderAdapter extends Reader {
	/** 被适配的内容流 */
	private final MessageReader wrapped;
	/** 已展开成字符、尚未被读走的文本 */
	private final StringBuilder internal = new StringBuilder();
	/** 已取出但无法展开成字符的内容段（图片等），按取出的先后顺序排队 */
	private final Deque<MessageContent> multimedia = new ArrayDeque<>();
	/** 内部文本缓冲区中当前读取到的位置 */
	private int position;
	/** 内部文本缓冲区当前的长度 */
	private int length;
	/** 是否已经读到内容流的末尾 */
	private boolean ended;

	/**
	 * 构造一个包装指定内容流的字符读取适配器。
	 *
	 * @param wrapped 要适配的内容流，不可为 null
	 */
	public MessageReaderAdapter(MessageReader wrapped) {
		super();
		this.wrapped = wrapped;
	}

	/**
	 * 取出一个无法展开成字符的内容段（图片、视频等）。
	 * <p>
	 * 这些内容段在字符流中被跳过，需要结构化处理的调用方用本方法按先后顺序取出。
	 * </p>
	 *
	 * @return 下一个非文本内容段，没有时返回 null
	 */
	public MessageContent pollMessage() {
		synchronized (lock) {
			return multimedia.pollFirst();
		}
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		if (off < 0 || len < 0 || off + len > cbuf.length) {
			throw new IndexOutOfBoundsException();
		}
		if (len == 0)
			return 0;
		while (true) {
			synchronized (lock) {
				if (position < length) {
					int count = Math.min(len, length - position);
					internal.getChars(position, position + count, cbuf, off);
					position += count;
					// 缓冲区已经全部读走时清空，长正文因此不会让缓冲区无限增长
					if (position >= length) {
						internal.setLength(0);
						position = 0;
						length = 0;
					}
					return count;
				}
				if (ended)
					return -1;
			}
			// 缓冲区读空后从内容流取段：等待新内容时不能持有本适配器的锁，
			// 否则其它线程调用 close() 会被挡在锁外，阻塞在 read() 上的读取方永远醒不过来
			MessageContent content = wrapped.read();
			synchronized (lock) {
				if (content == null) {
					ended = true;
					return -1;
				}
				if (content.isPlainText()) {
					// 纯文本展开进字符缓冲区；空文本段不会产生可读字符，直接跳过
					internal.append(content.toText());
					length = internal.length();
				} else {
					// 非文本内容不进入字符流，另行排队供 pollMessage() 取出
					multimedia.addLast(content);
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		synchronized (lock) {
			ended = true;
		}
		wrapped.close();
	}

	/**
	 * 内容流是否已经结束。
	 *
	 * @return 内容流结束时返回 {@code true}
	 */
	public boolean isEnded() {
		return ended || wrapped.isEnded();
	}
}
