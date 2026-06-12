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
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;

public class LLMTextReader extends Reader {
    private final StringBuilder internal = new StringBuilder();
    private final Deque<MessageContent> multiMedia=new ArrayDeque<>();
    private int position;
    private int length;
    private final MessageReader wrapped ;


    public LLMTextReader(MessageReader wrapped) {
		super();
		this.wrapped = wrapped;
	}
    public MessageContent pollMultiMedia() {
    	return multiMedia.pollFirst();
    }
	private void putCh(MessageContent event) {
		if(event==null)
			return;
        if(event instanceof PlainText) {
        	internal.append(((PlainText)event).toText());
        	length = internal.length();
        }else {
        	multiMedia.addLast(event);
        }
    }
    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        // 参数校验
        if (off < 0 || len < 0 || off + len > cbuf.length) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) return 0;

        synchronized (lock) {
            // 等待直到有数据可读或流结束
            while (position >= length && !wrapped.isEnded()) {
                putCh(wrapped.read());
            }
            // 如果流结束且无数据，返回 -1
            if (position >= length && wrapped.isEnded()) {
                return -1;
            }

            // 读取数据
            int readCount = 0;
            while (readCount < len && position < length) {
                cbuf[off + readCount] = internal.charAt(position);
                position++;
                readCount++;
            }
            return readCount;
        }
    }

    @Override
    public void close() throws IOException {
    	wrapped.close();
    }

    public boolean isEnded() {
        return wrapped.isEnded();
    }
}