package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.io.Reader;

public class BlockingReader extends Reader {
    private final StringBuilder internal = new StringBuilder();
    private int position;
    private int length;
    private boolean ended;
    private final Object lock = new Object();

    public BlockingReader() {}

    public void setEnded() {
        synchronized (lock) {
            ended = true;
            lock.notifyAll();
        }
    }

    public void putCh(String event) {
        synchronized (lock) {
            if (ended) {
                throw new IllegalStateException("Reader already closed");
            }
            internal.append(event);
            length = internal.length();
            lock.notifyAll();
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
            while (position >= length && !ended) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading", e);
                }
            }

            // 如果流结束且无数据，返回 -1
            if (position >= length && ended) {
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
        synchronized (lock) {
            ended = true;
            lock.notifyAll();
        }
    }

    public boolean isEnded() {
        synchronized (lock) {
            return ended;
        }
    }
}