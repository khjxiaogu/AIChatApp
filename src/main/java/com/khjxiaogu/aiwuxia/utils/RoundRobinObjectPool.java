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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RoundRobinObjectPool<T> {
    private final List<T> objects;
    private final boolean[] used;
    private final Lock lock = new ReentrantLock();
    private int nextIndex = 0;

    public RoundRobinObjectPool(List<T> objects) {
        this.objects = new ArrayList<>(objects);
        this.used = new boolean[objects.size()];
    }

    public T borrowObject() throws InterruptedException {
        lock.lock();
        try {
            int size = objects.size();
            int start = nextIndex;
            while (true) {
                if (!used[nextIndex]) {
                    used[nextIndex] = true;
                    T obj = objects.get(nextIndex);
                    nextIndex = (nextIndex + 1) % size;
                    return obj;
                }
                nextIndex = (nextIndex + 1) % size;
                // 如果一圈下来全被占用，则等待（简化处理：返回null或阻塞）
                if (nextIndex == start) {
                    // 可以抛出异常或进入等待，此处简单返回null
                    return null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void returnObject(T obj) {
        lock.lock();
        try {
            int index = objects.indexOf(obj);
            if (index >= 0) {
                used[index] = false;
            }
        } finally {
            lock.unlock();
        }
    }
}