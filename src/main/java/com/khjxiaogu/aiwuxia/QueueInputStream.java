package com.khjxiaogu.aiwuxia;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class QueueInputStream extends InputStream {
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private byte[] currentChunk;
    private int pos;
    private volatile boolean finished;

    // 生产者调用此方法添加数据
    public void addChunk(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            finished = true;
        } else {
            queue.offer(chunk);
        }
    }
    @Override
    public int read() throws IOException {
        // 非阻塞尝试获取数据，如果没有则返回 -1（或可改为阻塞，但需注意单线程死锁）
        if (currentChunk == null || pos >= currentChunk.length) {
            try {
            	
                currentChunk = queue.poll(10, TimeUnit.MILLISECONDS); // 超时避免永久阻塞
                if (currentChunk == null) {
                    return -1;
                }
            	
                pos = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return currentChunk[pos++] & 0xFF;
    }
    @Override
	public int available() throws IOException {
    	
    	if (currentChunk == null || pos >= currentChunk.length) {
            try {
            	
                currentChunk = queue.poll(10, TimeUnit.MILLISECONDS); // 超时避免永久阻塞
                if (currentChunk == null) {
                    return 0;
                }
                pos = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
		return currentChunk.length-pos;
	}
    // 也可实现带超时的 read 方法，或使用 available() 判断
}