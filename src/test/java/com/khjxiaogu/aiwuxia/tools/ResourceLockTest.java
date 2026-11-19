package com.khjxiaogu.aiwuxia.tools;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;

/**
 * {@link ResourceLock} 的自检，重点是 H3 使用的 {@link ResourceLock#acquireAtLeast(int)}：
 * 先满足最低份数，然后一次性占满当前全部剩余资源。
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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
