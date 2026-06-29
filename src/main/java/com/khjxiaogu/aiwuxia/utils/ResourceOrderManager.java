package com.khjxiaogu.aiwuxia.utils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final Set<Long> completed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private long nextSequence = 1;       // 下一个分配的序号
    private long currentSequence = 1;    // 当前应执行的序号
    private volatile OrderHandleImpl currentHolder; // 当前持有资源的句柄
    private final ScheduledExecutorService deadlockDetector =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ResourceOrderManager-deadlock-detector");
            t.setDaemon(true);
            return t;
        });
    public ResourceOrderManager() {
        deadlockDetector.scheduleWithFixedDelay(() -> {
            OrderHandleImpl holder = currentHolder;
            if (holder != null) {
                long elapsed = System.currentTimeMillis() - holder.acquireTimestamp;
                if (elapsed > 120_000) {
                    System.err.println("[DEADLOCK] ResourceOrderManager lock held for "
                        + elapsed + "ms by sequence " + holder.getSequence()
                        + ", refCount=" + holder.refCount);
                    holder.registar.printStackTrace();
                    // snapshot lists under refLock
                    List<Exception> forkers;
                    synchronized (holder.refLock) {
                        forkers   = new ArrayList<>(holder.forkerStacks);
                    }
                    for (int i = 0; i < forkers.size(); i++) {
                        System.err.println("[DEADLOCK] Forker #" + (i + 1) + ":");
                        forkers.get(i).printStackTrace(System.err);
                    }
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 关闭死锁检测器，在不再需要使用此管理器时调用。
     */
    public void shutdown() {
        deadlockDetector.shutdownNow();
    }

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
        private final Object refLock = new Object(); // 保护 refCount / completedCalled / cancelCalled
        private volatile State state = State.INIT;
        private int refCount;          // 引用计数，受 refLock 保护
        private boolean completedCalled = false; // 防止重复 complete
        private final Object cancelLock = new Object();
        private boolean cancelCalled = false; 
        private final List<Exception> forkerStacks   = new ArrayList<>(); // 受 refLock 保护
        private final Exception registar;
        volatile long acquireTimestamp;
        OrderHandleImpl(long sequence, ResourceOrderManager manager) {
            this.sequence = sequence;
            this.manager = manager;
            this.refCount = 1;         // 自身占用一个引用
            this.registar=new Exception();
        }

        @Override
        public ResourceAccess acquire() throws InterruptedException {
        	if (state == State.COMPLETED) {
                throw new IllegalStateException("Handle already completed");
            }
        	if (state == State.INIT) {
	            synchronized (refLock) {
	                if (state == State.INIT) {
	                	manager.doAcquire(this);
	                    state = State.ACQUIRED;
	                }
	            }
        	}
            return fork();
        }

        @Override
        public ResourceAccess fork() {
            synchronized (refLock) {
                if (state == State.COMPLETED) {
                    throw new IllegalStateException("Handle already completed, cannot fork");
                }
                refCount++;
                Exception forkerStack = new Exception("fork() called");
                forkerStacks.add(forkerStack);
                return (ResourceAccess) () -> {
                    synchronized (refLock) {
                        forkerStacks.remove(forkerStack);
                    }
                    releaseRef();
                };
            }
        }

        @Override
        public void close() {
        	if (!cancelCalled) {
	            synchronized(cancelLock) {
	            	if (!cancelCalled) {
	            		cancelCalled = true;
	            		releaseRef();
	            	}
	            }
        	}
        }
        private boolean shouldComplete() {
        	if (refCount == 0 && !completedCalled) {
                completedCalled = true;
                state = State.COMPLETED;
                return true;
            }
        	return false;
        }
        /**
         * 释放一个引用（由资源访问对象或 fork 子句柄调用）
         */
        void releaseRef() {
            boolean shouldComplete = false;
            synchronized (refLock) {
                if (refCount > 0) {
                    refCount--;
                    shouldComplete=shouldComplete();
                }
            }
            if (shouldComplete) {
                manager.complete(sequence);
            }
        }

        long getSequence() {
            return sequence;
        }

        enum State {
            INIT, ACQUIRED, COMPLETED;
        }
    }

    /**
     * 等待直到轮到此凭证，并标记为已获取。
     */
    private void doAcquire(OrderHandleImpl handle) throws InterruptedException {
        lock.lock();
        try {
            long seq = handle.getSequence();
            // 等待直到当前序号等于本凭证序号，且本凭证未被提前取消（completed不包含seq）
            while (currentSequence != seq && !completed.contains(seq)) {
                condition.await();
            }
            // 检查是否被取消
            if (completed.contains(seq)) {
                throw new IllegalStateException("Resource access cancelled before acquisition");
            }
            // state already set to ACQUIRED by caller
            handle.acquireTimestamp = System.currentTimeMillis();
            this.currentHolder = handle;
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
            // 如果完成的是当前持有者的序号，清除 currentHolder
            if (currentHolder != null && currentHolder.getSequence() == seq) {
                currentHolder = null;
            }
            // 将当前序号向后推进到第一个未完成的序号
            while (completed.remove(currentSequence)) {
                currentSequence++;
            }
            condition.signalAll();
        } finally {
            lock.unlock();
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
        ResourceAccess fork();

        /**
         * 关闭凭证。
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