package com.khjxiaogu.aiwuxia.utils;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按触发顺序访问资源的管理器。
 * <p>每个 AI Agent 在触发时通过 {@link #register()} 获取一个顺序凭证（{@link OrderHandle}）。</p>
 * <p>Agent 在真正需要调用资源时才调用 {@link OrderHandle#acquire()}，此时会阻塞直到轮到自己。</p>
 * <p>使用 {@link ResourceAccess#close()} 释放资源并允许下一个 Agent 执行。</p>
 * <p>若 Agent 决定放弃调用，可调用 {@link OrderHandle#cancel()} 或直接关闭 {@code OrderHandle}。</p>
 */
public class ResourceOrderManager {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final Set<Long> completed = new HashSet<>();
    private long nextSequence = 1;       // 下一个分配的序号
    private long currentSequence = 1;    // 当前应执行的序号

    /**
     * 注册一个 AI Agent，分配触发顺序序号。
     *
     * @return 顺序凭证，用于后续获取资源访问权或放弃调用
     */
    public OrderHandle register() {
        lock.lock();
        try {
            long seq = nextSequence++;
            return new OrderHandleImpl(seq, this);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 内部实现：顺序凭证（支持引用计数）
     */
    private static class OrderHandleImpl implements OrderHandle {
        private final long sequence;
        private final ResourceOrderManager manager;
        private volatile State state = State.INIT;
        private int refCount;          // 引用计数，受 manager.lock 保护
        private boolean completedCalled = false; // 防止重复 complete
        private boolean cancelCalled = false; 
        OrderHandleImpl(long sequence, ResourceOrderManager manager) {
            this.sequence = sequence;
            this.manager = manager;
            this.refCount = 1;         // 自身占用一个引用
        }

        @Override
        public ResourceAccess acquire() throws InterruptedException {
            if (state == State.ACQUIRED) {
                throw new IllegalStateException("Handle already used");
            }
            if (state == State.COMPLETED) {
                throw new IllegalStateException("Handle already completed");
            }
            return manager.doAcquire(this);
        }

        @Override
        public AutoCloseable fork() {
            manager.lock.lock();
            try {
                if (state == State.COMPLETED) {
                    throw new IllegalStateException("Handle already completed, cannot fork");
                }
                refCount++;
                // 返回一个子句柄，close 时减少引用计数
                return (AutoCloseable) () -> {
                    manager.lock.lock();
                    try {
                        if (refCount > 0) {
                            refCount--;
                            tryComplete();
                        }
                    } finally {
                        manager.lock.unlock();
                    }
                };
            } finally {
                manager.lock.unlock();
            }
        }

        @Override
        public void cancel() {
            manager.lock.lock();
            try {
                if (cancelCalled) return;
                cancelCalled=true;
                releaseRef();      // 释放自身引用
            } finally {
                manager.lock.unlock();
            }
        }

        @Override
        public void close() {
            cancel();
        }

        /**
         * 增加引用计数（在成功获取资源时调用）
         */
        @SuppressWarnings("unused")
		void addRef() {
            manager.lock.lock();
            try {
                refCount++;
            } finally {
                manager.lock.unlock();
            }
        }

        /**
         * 释放一个引用（由资源访问对象或 fork 子句柄调用）
         */
        void releaseRef() {
            manager.lock.lock();
            try {
                if (refCount > 0) {
                    refCount--;
                    tryComplete();
                }
            } finally {
                manager.lock.unlock();
            }
        }

        /**
         * 当引用计数归零时，真正完成顺序槽位
         */
        private void tryComplete() {
            if (refCount == 0 && !completedCalled) {
                completedCalled = true;
                manager.complete(sequence);
                state=State.COMPLETED;
            }
        }

        long getSequence() {
            return sequence;
        }

        boolean compareAndSetState(State expected, State newState) {
            if (state == expected) {
                state = newState;
                return true;
            }
            return false;
        }

        enum State {
            INIT, ACQUIRED, COMPLETED;
        }
    }

    /**
     * 等待直到轮到此凭证，然后返回资源访问对象。
     */
    private ResourceAccess doAcquire(OrderHandleImpl handle) throws InterruptedException {
        lock.lock();
        try {
            long seq = handle.getSequence();
            // 等待直到当前序号等于本凭证序号，且本凭证未被提前取消（completed不包含seq）
            while (currentSequence != seq && !completed.contains(seq)) {
                condition.await();
            }
            // 检查是否被取消（completed包含seq 或 状态已变为RELEASED）
            if (completed.contains(seq) || handle.state == OrderHandleImpl.State.COMPLETED) {
                throw new IllegalStateException("Resource access cancelled before acquisition");
            }
            // 此时 currentSequence == seq，且未取消，标记已获取
            if (!handle.compareAndSetState(OrderHandleImpl.State.INIT, OrderHandleImpl.State.ACQUIRED)) {
                throw new IllegalStateException("Concurrent state change");
            }
            return new ResourceAccessImpl(seq, this);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 完成指定序号（正常使用完毕或主动放弃）。
     */
    void complete(long seq) {
        lock.lock();
        try {
            completed.add(seq);
            // 将当前序号向后推进到第一个未完成的序号
            while (completed.contains(currentSequence)) {
                currentSequence++;
            }
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 资源访问对象，实现 AutoCloseable，close 时释放顺序。
     */
    private static class ResourceAccessImpl implements ResourceAccess {
        private final long sequence;
        private final ResourceOrderManager manager;
        private boolean closed = false;

        ResourceAccessImpl(long sequence, ResourceOrderManager manager) {
            this.sequence = sequence;
            this.manager = manager;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                manager.complete(sequence);
            }
        }
    }

    // ----- 公开接口定义 -----

    /**
     * 顺序凭证，由 {@link ResourceOrderManager#register()} 返回。
     */
    public interface OrderHandle extends AutoCloseable {
        /**
         * 获取资源访问对象，必要时阻塞直到轮到此 Agent。
         *
         * @return 资源访问对象（AutoCloseable）
         * @throws InterruptedException 若等待被中断
         */
        ResourceAccess acquire() throws InterruptedException;
        /**
         * 派生一个新的 AutoCloseable 子句柄，该子句柄关闭时会释放一个引用。
         * 只有所有派生子句柄、原 handle 以及获取的 ResourceAccess 都关闭后，才释放顺序槽位。
         */
        AutoCloseable fork();
        /**
         * 放弃本次资源调用，跳过当前 Agent 的顺序。
         * 若已经获取了资源访问权，则不应调用此方法。
         */
        void cancel();

        /**
         * 关闭凭证，等同于 {@link #cancel()}。
         */
        @Override
        void close();
    }

    /**
     * 资源访问对象，表示当前 Agent 持有资源使用权。
     * 使用 try-with-resources 确保资源释放。
     */
    public interface ResourceAccess extends AutoCloseable {
        @Override
        void close();
    }
}