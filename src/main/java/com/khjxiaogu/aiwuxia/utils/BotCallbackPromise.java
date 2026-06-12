package com.khjxiaogu.aiwuxia.utils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonObject; // 假设使用Gson库，您可根据实际项目替换为其他JSON类型
import com.khjxiaogu.aiwuxia.NapCatAIConnector.BotCallback;

/**
 * 将BotCallback与CompletableFuture绑定的工具类。
 * - 提供回调函数，当回调被调用时，根据status决定Future的完成状态。
 * - 若1分钟内回调未被调用，Future将因超时异常而完成。
 * - 线程安全，确保回调与超时仅生效一次。
 */
public class BotCallbackPromise {
    private final CompletableFuture<JsonObject> future;
    private final BotCallback callback;
    private static final ScheduledExecutorService scheduler =Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final ScheduledFuture<?> timeoutTask;

    /**
     * 构造一个新的绑定实例，立即启动1分钟超时计时。
     */
    public BotCallbackPromise() {
        this.future = new CompletableFuture<>();
        // 使用守护线程的调度器，避免阻止JVM退出


        // 超时任务：1分钟后若尚未完成，则异常完成Future
        this.timeoutTask = scheduler.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                future.completeExceptionally(new TimeoutException("BotCallback not called within 1 minute"));
            }
        }, 1, TimeUnit.MINUTES);

        // 回调实现：根据status决定Future的结果，并取消超时任务
        this.callback = (status, data) -> {
            if (completed.compareAndSet(false, true)) {
                timeoutTask.cancel(false); // 取消超时任务
                if (status == 0) {
                    future.complete(data);
                } else {
                    future.completeExceptionally(new BotCallbackException(status, data));
                }
            }
        };
    }

    /**
     * 获取绑定的BotCallback实例。
     * @return BotCallback函数式接口，调用它将影响对应的CompletableFuture
     */
    public BotCallback getBotCallback() {
        return callback;
    }

    /**
     * 获取与BotCallback绑定的CompletableFuture。
     * @return 等待回调结果或超时的CompletableFuture
     */
    public CompletableFuture<JsonObject> getFuture() {
        return future;
    }

    /**
     * 自定义异常，封装非零状态码及对应的数据。
     */
    public static class BotCallbackException extends Exception {
        /**
		 * 
		 */
		private static final long serialVersionUID = -2584989602125281954L;
		private final int status;
        private final JsonObject data;

        public BotCallbackException(int status, JsonObject data) {
            super("BotCallback failed with status: " + status);
            this.status = status;
            this.data = data;
        }

        public int getStatus() {
            return status;
        }

        public JsonObject getData() {
            return data;
        }
    }
}