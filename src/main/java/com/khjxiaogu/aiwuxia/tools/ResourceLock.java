package com.khjxiaogu.aiwuxia.tools;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 资源阻塞锁：支持按任意数量申请/释放资源，资源不足时阻塞调用线程。
 * <p>
 * 除了固定份数的 {@link #acquire(int)}，还提供 {@link #acquireAtLeast(int)}：先等到最低份数可用，
 * 再一次性占满当前全部剩余资源，供 H3 这类“必须独占显存”的重模型使用。
 * </p>
 */
public class ResourceLock {

    private final int total;
    private int available;
    private final Lock lock = new ReentrantLock();
    private final Condition sufficient = lock.newCondition();

    /**
     * @param total 资源总量，必须 > 0
     */
    public ResourceLock(int total) {
        if (total <= 0) {
            throw new IllegalArgumentException("total must be positive");
        }
        this.total = total;
        this.available = total;
    }

    /**
     * 申请资源（可中断）。
     *
     * @param amount 需要的资源量，必须 > 0 且 <= total
     * @return 资源占用锁，使用完毕后必须调用 {@link ResourcePermit#release()} 或
     *         使用 try-with-resources 释放
     * @throws InterruptedException 如果等待被中断
     */
    public ResourcePermit acquire(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount > total) {
            throw new IllegalArgumentException("amount exceeds total capacity");
        }

        lock.lock();
        try {
            while (available < amount) {
                try {
					sufficient.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
					Thread.currentThread().interrupt();
					throw new IllegalArgumentException("interrupted");
				}
            }
            available -= amount;
            return new ResourcePermit(amount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 申请资源：先等到至少 {@code minimum} 份可用，然后一次性占满当前全部剩余资源。
     * <p>
     * 面向 H3 这类“必须独占显存”的重模型：资源不足 {@code minimum} 份时一直等待，满足后
     * 不再给其它任务留余量（本次占用数 = 等待结束时 {@link #available()} 的值）。释放时按实际
     * 占用数归还，因此等待期间别人释放的资源也会一并归还。
     * </p>
     *
     * @param minimum 最低资源要求，必须 > 0 且 <= {@link #total()}
     * @return 资源占用锁，使用完毕后必须调用 {@link ResourcePermit#release()} 或
     *         使用 try-with-resources 释放
     * @throws IllegalArgumentException {@code minimum} 非法，或锁容量本身小于 {@code minimum}
     */
    public ResourcePermit acquireAtLeast(int minimum) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum must be positive");
        }
        if (minimum > total) {
            throw new IllegalArgumentException("资源锁容量为 " + total + "，不足最低要求 " + minimum + " 份");
        }

        lock.lock();
        try {
            while (available < minimum) {
                try {
                    sufficient.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                    throw new IllegalArgumentException("interrupted");
                }
            }
            int amount = available;
            available = 0;
            return new ResourcePermit(amount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 资源总量（构造时指定）。
     *
     * @return 资源锁的总容量
     */
    public int total() {
        lock.lock();
        try {
            return total;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 尝试申请资源，最多等待指定时间。
     *
     * @return 资源占用锁，如果超时则返回 {@code null}
     */
    public ResourcePermit tryAcquire(int amount, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount > total) {
            throw new IllegalArgumentException("amount exceeds total capacity");
        }

        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (available < amount) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = sufficient.awaitNanos(nanos);
            }
            available -= amount;
            return new ResourcePermit(amount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当前可用资源量（快照值，调用后可能立即变化）。
     */
    public int available() {
        lock.lock();
        try {
            return available;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放资源（由 ResourcePermit 内部调用）。
     */
    private void release(ResourcePermit permit) {
        lock.lock();
        try {
            if (permit.released) {
                throw new IllegalStateException("permit has already been released");
            }
            permit.released = true;
            available += permit.amount;
            // 唤醒所有等待线程（它们会自行检查条件）
            sufficient.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 资源占用锁，持有它表示已占用指定数量的资源。
     * 支持显式调用 {@link #release()} 或使用 try-with-resources 自动释放。
     */
    public class ResourcePermit implements AutoCloseable {
        private final int amount;
        private volatile boolean released = false;

        private ResourcePermit(int amount) {
            this.amount = amount;
        }

        /**
         * 释放占用的资源。
         */
        public void release() {
            ResourceLock.this.release(this);
        }

        /**
         * 同 {@link #release()}，方便 try-with-resources 使用。
         */
        @Override
        public void close() {
            release();
        }

        public int amount() {
            return amount;
        }
    }
}