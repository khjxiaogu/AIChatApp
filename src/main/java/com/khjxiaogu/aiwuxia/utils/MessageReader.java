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
import java.util.ArrayDeque;

import com.khjxiaogu.aiwuxia.llm.message.MessageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;

public class MessageReader{
    private final ArrayDeque<MessageContent> internal = new ArrayDeque<>();
    private boolean ended;
    private final Object lock = new Object();
    private IOException sourceException;
    public MessageReader() {}
    public MessageReader(MessageContents messages) {
    	for(MessageContent msg:messages)
    		internal.add(msg);
    	
    }
    
    public void setEnded() {
    	if(!ended) {
	        synchronized (lock) {
	        	if(!ended) {
		            ended = true;
		            lock.notifyAll();
	        	}
	        }
    	}
    }

    public void putCh(MessageContent event) {
        synchronized (lock) {
            if (ended) {
                throw new IllegalStateException("Reader already closed");
            }
            internal.addLast(event);
            lock.notifyAll();
        }
    }
    public void putException(IOException ex) {
    	synchronized (lock) {
    		this.sourceException=ex;
            ended = true;
            lock.notifyAll();
        }
    }
    public MessageContent read() throws IOException {


        synchronized (lock) {
            // 等待直到有数据可读或流结束
            while (internal.isEmpty() && !ended) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading", e);
                }
            }
            // 如果流结束且无数据，返回 -1
            if (internal.isEmpty() && ended) {
            	if(sourceException!=null)
                	throw sourceException;
                return null;
            }


            return internal.pollFirst();
        }
    }

    public void close() throws IOException {
        synchronized (lock) {
            ended = true;
            lock.notifyAll();
        }
    }

    public boolean isEnded() {
        return ended;
    }
}