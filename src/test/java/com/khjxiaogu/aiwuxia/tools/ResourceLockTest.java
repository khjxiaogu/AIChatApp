package com.khjxiaogu.aiwuxia.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;

/**
 * {@link ResourceLock} 的自检，重点是 H3 使用的 {@link ResourceLock#acquireAtLeast(int)}：
 * 先满足最低份数，然后一次性占满当前全部剩余资源；以及按 {@link TaskPriority} 调度时的
 * 优先级顺序与“最多被插队 5 次”的防饿死策略。
 * <p>
 * 不依赖 JUnit，直接用 {@code java} 运行本类的 {@link #main(String[])}。
 * </p>
 */
public class ResourceLockTest {

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 * @throws Exception 线程等待失败
	 */
	public static void main(String[] args) throws Exception {
		testTakesAll();
		testWaitsForMinimum();
		testTakesRemainingAboveMinimum();
		testCapacityBelowMinimum();
		testPriorityOrder();
		testAntiStarvation();
		System.out.println("ResourceLockTest: 全部用例通过");
	}

	/** 空闲时直接占满整把锁，释放后完全归还。 */
	private static void testTakesAll() {
		ResourceLock lock = new ResourceLock(12);
		check(lock.total() == 12, "total() 应为 12，实际：" + lock.total());
		ResourcePermit permit = lock.acquireAtLeast(8);
		check(permit.amount() == 12, "空闲时应占满 12 份，实际：" + permit.amount());
		check(lock.available() == 0, "占用后可用资源应为 0，实际：" + lock.available());
		permit.release();
		check(lock.available() == 12, "释放后应归还 12 份，实际：" + lock.available());
	}

	/** 剩余不足 8 份时等待；等到后把别人释放的份数也一起占满。 */
	private static void testWaitsForMinimum() throws Exception {
		ResourceLock lock = new ResourceLock(12);
		ResourcePermit holder = lock.acquire(6);
		check(lock.available() == 6, "先持有 6 份后应剩 6 份");

		AtomicReference<ResourcePermit> result = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			result.set(lock.acquireAtLeast(8));
			done.countDown();
		}, "resource-lock-test-waiter");
		waiter.setDaemon(true);
		waiter.start();
		check(!done.await(150, TimeUnit.MILLISECONDS), "剩余 6 份不足 8 份时不应立即获得资源");

		holder.release();
		check(done.await(2, TimeUnit.SECONDS), "资源释放后应很快获得资源");
		ResourcePermit permit = result.get();
		check(permit != null && permit.amount() == 12, "应占满释放后的 12 份，实际："
				+ (permit == null ? "null" : permit.amount()));
		check(lock.available() == 0, "占用后可用资源应为 0");
		permit.release();
		check(lock.available() == 12, "全部释放后可用资源应恢复为 12");
	}

	/** 剩余已经多于最低要求时不再等待，直接把剩余全部拿走。 */
	private static void testTakesRemainingAboveMinimum() {
		ResourceLock lock = new ResourceLock(12);
		ResourcePermit holder = lock.acquire(2);
		check(lock.available() == 10, "先持有 2 份后应剩 10 份");
		ResourcePermit permit = lock.acquireAtLeast(8);
		check(permit.amount() == 10, "应拿走剩余 10 份，实际：" + permit.amount());
		check(lock.available() == 0, "占用后可用资源应为 0");
		permit.release();
		holder.release();
		check(lock.available() == 12, "全部释放后可用资源应恢复为 12");
	}

	/** 锁容量本身小于最低要求时直接报错，而不是永远等待。 */
	private static void testCapacityBelowMinimum() {
		ResourceLock lock = new ResourceLock(6);
		try {
			lock.acquireAtLeast(8);
			check(false, "容量 6 的锁不应满足最低 8 份的要求");
		} catch (IllegalArgumentException ex) {
			check(ex.getMessage() != null && ex.getMessage().contains("不足最低要求"),
					"错误信息应说明容量不足，实际：" + ex.getMessage());
		}
	}

	/**
	 * 优先级：同时等待时按 快任务 > 普通任务 > 重型任务 的顺序拿资源。
	 * <p>
	 * 先到的是视频（LOW），但资源释放后应先给生图（HIGH），再给歌声（NORMAL），最后才是视频。
	 * </p>
	 */
	private static void testPriorityOrder() throws Exception {
		ResourceLock lock = new ResourceLock(12);
		ResourcePermit holder = lock.acquire(12);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch done = new CountDownLatch(3);
		startWaiter(lock, TaskPriority.LOW, "video", order, done);
		awaitWaiting(lock, 1);
		startWaiter(lock, TaskPriority.NORMAL, "svs", order, done);
		awaitWaiting(lock, 2);
		startWaiter(lock, TaskPriority.HIGH, "image", order, done);
		awaitWaiting(lock, 3);

		holder.release();
		check(done.await(5, TimeUnit.SECONDS), "三个等待任务应全部完成");
		check(order.equals(Arrays.asList("image", "svs", "video")),
				"应按 快任务 > 普通 > 重型 的顺序执行，实际：" + order);
	}

	/**
	 * 防饿死：低优先级任务被插队 5 次后必须优先执行，即使仍有高优先级任务在等。
	 */
	private static void testAntiStarvation() throws Exception {
		ResourceLock lock = new ResourceLock(12);
		ResourcePermit holder = lock.acquire(12);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch done = new CountDownLatch(7);
		startWaiter(lock, TaskPriority.LOW, "video", order, done);
		awaitWaiting(lock, 1);
		for (int i = 1; i <= 6; i++) {
			startWaiter(lock, TaskPriority.HIGH, "image" + i, order, done);
			awaitWaiting(lock, i + 1);
		}

		holder.release();
		check(done.await(5, TimeUnit.SECONDS), "全部任务应完成");
		check(order.size() == 7, "应有 7 次执行，实际：" + order);
		for (int i = 0; i < 5; i++) {
			check(order.get(i).startsWith("image"), "前 5 次应被快任务插队，实际：" + order);
		}
		check("video".equals(order.get(5)), "被插队 5 次后应轮到低优先级任务，实际：" + order);
	}

	/** 启动一个等待指定优先级的任务；拿到资源后记录名字并立即释放。 */
	private static void startWaiter(ResourceLock lock, TaskPriority priority, String name, List<String> order,
			CountDownLatch done) {
		Thread thread = new Thread(() -> {
			try (ResourcePermit permit = lock.acquire(12, priority)) {
				order.add(name);
			}
			done.countDown();
		}, "resource-lock-priority-" + name);
		thread.setDaemon(true);
		thread.start();
	}

	/** 等待队列里的申请数达到预期（确保线程已经进入排队）。 */
	private static void awaitWaiting(ResourceLock lock, int expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 2000;
		while (lock.waiting() < expected) {
			check(System.currentTimeMillis() < deadline, "等待队列未达到 " + expected + " 个");
			Thread.sleep(5);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
