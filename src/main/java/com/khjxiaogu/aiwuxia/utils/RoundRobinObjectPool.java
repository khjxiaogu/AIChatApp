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