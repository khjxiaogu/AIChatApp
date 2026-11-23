package com.khjxiaogu.aiwuxia.tools;
import java.util.ArrayList;
import java.util.List;
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
 * <p>
 * 多个申请同时等待时按 {@link TaskPriority} 调度：生图、语音这类快任务先拿资源，歌声合成其次，
 * 视频生成最后。为避免慢任务被一直插队饿死，任何任务在等待期间被插队达到
 * {@link #DEFAULT_MAX_OVERTAKES 上限}（默认 5 次）后，会转为最高优先级：下一次资源够时先分给它；
 * 资源不够时其它任务也不许抢占，等资源归还给它。
 * </p>
 * <p>
 * 优先级只决定“资源忙时谁先上”，不抢占已经开始的任务。默认优先级是
 * {@link TaskPriority#NORMAL}，不显式指定优先级的旧调用保持原有的先来先得行为。
 * </p>
 */
public class ResourceLock {

    /** 默认最多被插队次数：达到后转为优先调度，避免饿死。 */
    public static final int DEFAULT_MAX_OVERTAKES = 5;

    private final int total;
    /** 一个任务最多被其它任务插队的次数。 */
    private final int maxOvertakes;
    private int available;
    private final Lock lock = new ReentrantLock();
    private final Condition sufficient = lock.newCondition();
    /** 正在等待的申请；授权时按优先级挑选，同优先级按到达顺序。 */
    private final List<Waiter> waiters = new ArrayList<>();
    /** 到达序号，用于同优先级先来先得与紧急任务的先后。 */
    private long sequence;

    /**
     * @param total 资源总量，必须 > 0
     */
    public ResourceLock(int total) {
        this(total, DEFAULT_MAX_OVERTAKES);
    }

    /**
     * @param total        资源总量，必须 > 0
     * @param maxOvertakes 一个任务最多被插队的次数，非正数时使用 {@value #DEFAULT_MAX_OVERTAKES}
     */
    public ResourceLock(int total, int maxOvertakes) {
        if (total <= 0) {
            throw new IllegalArgumentException("total must be positive");
        }
        this.total = total;
        this.available = total;
        this.maxOvertakes = maxOvertakes > 0 ? maxOvertakes : DEFAULT_MAX_OVERTAKES;
    }

    /**
     * 按默认优先级申请资源（可中断）。
     *
     * @param amount 需要的资源量，必须 > 0 且 <= total
     * @return 资源占用锁，使用完毕后必须调用 {@link ResourcePermit#release()} 或
     *         使用 try-with-resources 释放
     * @throws InterruptedException 如果等待被中断
     */
    public ResourcePermit acquire(int amount) {
        return acquire(amount, TaskPriority.NORMAL);
    }

    /**
     * 按指定优先级申请资源（可中断）。
     * <p>
     * 资源不足时进入等待队列；队列按优先级调度，同等优先级先来先得。
     * </p>
     *
     * @param amount   需要的资源量，必须 > 0 且 <= total
     * @param priority 调度优先级，null 视为 {@link TaskPriority#NORMAL}
     * @return 资源占用锁
     * @throws IllegalArgumentException 参数非法或等待被中断
     */
    public ResourcePermit acquire(int amount, TaskPriority priority) {
        validateAmount(amount);
        return awaitGrant(amount, false, priority);
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
        return acquireAtLeast(minimum, TaskPriority.NORMAL);
    }

    /**
     * 按指定优先级申请“至少 N 份后独占剩余全部”的资源。
     *
     * @param minimum  最低资源要求
     * @param priority 调度优先级，null 视为 {@link TaskPriority#NORMAL}
     * @return 资源占用锁
     * @throws IllegalArgumentException 参数非法或等待被中断
     */
    public ResourcePermit acquireAtLeast(int minimum, TaskPriority priority) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum must be positive");
        }
        if (minimum > total) {
            throw new IllegalArgumentException("资源锁容量为 " + total + "，不足最低要求 " + minimum + " 份");
        }
        return awaitGrant(minimum, true, priority);
    }

    private void validateAmount(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount > total) {
            throw new IllegalArgumentException("amount exceeds total capacity");
        }
    }

    /** 把申请加入等待队列，直到被调度器授予资源。 */
    private ResourcePermit awaitGrant(int amount, boolean atLeast, TaskPriority priority) {
        TaskPriority effective = priority == null ? TaskPriority.NORMAL : priority;
        lock.lock();
        try {
            Waiter waiter = new Waiter(amount, atLeast, effective, sequence++);
            waiters.add(waiter);
            while (true) {
                dispatchLocked();
                if (waiter.granted > 0) {
                    waiters.remove(waiter);
                    return new ResourcePermit(waiter.granted);
                }
                try {
                    sufficient.await();
                } catch (InterruptedException e) {
                    waiters.remove(waiter);
                    if (waiter.granted > 0) {
                        // 已经授予但还没返回：把资源还回去，避免泄漏
                        available += waiter.granted;
                    }
                    // 等待队列变了：唤醒其它等待者重新评估，避免它们一直睡下去
                    sufficient.signalAll();
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                    throw new IllegalArgumentException("interrupted");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按优先级把当前可满足的等待者依次授予；必须在持锁状态下调用。
     * <p>
     * 每授予一个任务，其余仍在等待的任务记一次“被插队”。达到上限的任务在
     * {@link #pickBestLocked()} 里优先，保证不会一直等不到资源。
     * </p>
     */
    private void dispatchLocked() {
        while (true) {
            Waiter best = pickBestLocked();
            if (best == null) {
                return;
            }
            if (best.atLeast) {
                best.granted = available;
                available = 0;
            } else {
                best.granted = best.amount;
                available -= best.amount;
            }
            // 已授予的申请立刻移出等待队列，避免被重复授予
            waiters.remove(best);
            for (Waiter other : waiters) {
                other.overtaken++;
            }
            // 让被授予者立刻醒来返回；也唤醒其它等待者重新评估（情况已变化）
            sufficient.signalAll();
        }
    }

    /**
     * 挑选下一个应授予的等待者。
     *
     * @return 选中的等待者；返回 null 表示当前没有可授予对象，或需要为被插队到上限的任务保留资源
     */
    private Waiter pickBestLocked() {
        boolean hasUrgent = false;
        Waiter urgent = null;
        for (Waiter waiter : waiters) {
            if (waiter.overtaken >= maxOvertakes) {
                hasUrgent = true;
                // 紧急任务里挑最早到达、且当前资源满足的
                if (canGrantLocked(waiter) && (urgent == null || waiter.seq < urgent.seq)) {
                    urgent = waiter;
                }
            }
        }
        if (hasUrgent) {
            // 有任务被插队到上限：只让它先走；它要的资源还没凑够时，别的任务也不许抢，等资源归还
            return urgent;
        }
        Waiter best = null;
        for (Waiter waiter : waiters) {
            if (!canGrantLocked(waiter)) {
                continue;
            }
            if (best == null || waiter.priority.rank() < best.priority.rank()
                    || (waiter.priority.rank() == best.priority.rank() && waiter.seq < best.seq)) {
                best = waiter;
            }
        }
        return best;
    }

    /** 当前资源是否满足该申请（独占型只要求最低份数够）。 */
    private boolean canGrantLocked(Waiter waiter) {
        return available >= waiter.amount;
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
     * 当前等待调度的申请数（快照值）。
     *
     * @return 等待中的任务数
     */
    public int waiting() {
        lock.lock();
        try {
            return waiters.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 尝试申请资源，最多等待指定时间。
     * <p>
     * 这是不参与优先级排队的兼容接口：只在完全够用时立即占用，否则按超时等待。
     * </p>
     *
     * @return 资源占用锁，如果超时则返回 {@code null}
     */
    public ResourcePermit tryAcquire(int amount, long timeout, TimeUnit unit)
            throws InterruptedException {
        validateAmount(amount);

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
            // 立即按优先级把归还的资源分给等待者
            dispatchLocked();
            // 没有可授予对象时也唤醒等待者，让它们重新检查条件
            sufficient.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 一个等待中的资源申请。 */
    private static final class Waiter {
        /** 需要的资源量；独占型是最低份数。 */
        final int amount;
        /** true 表示“至少 amount 份后独占剩余全部”。 */
        final boolean atLeast;
        final TaskPriority priority;
        /** 到达序号：同优先级先来先得。 */
        final long seq;
        /** 被授予的资源量，0 表示还没被授予。 */
        int granted;
        /** 已被插队的次数。 */
        int overtaken;

        Waiter(int amount, boolean atLeast, TaskPriority priority, long seq) {
            this.amount = amount;
            this.atLeast = atLeast;
            this.priority = priority;
            this.seq = seq;
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
