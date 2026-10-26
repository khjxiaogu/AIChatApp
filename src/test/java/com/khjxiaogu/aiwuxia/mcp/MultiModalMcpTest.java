package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;

/**
 * 图片识别并发合并测试。
 * <p>
 * 只使用内存里的假对象存储，不访问网络，也不调用多模态模型：
 * 第一个识别会卡在 {@code exists} 上，第二个同 id 识别应当等待第一个而不是再次进入
 * {@code exists}；第一个结束后两者应返回同一个结果。
 * </p>
 */
public class MultiModalMcpTest {

	/**
	 * 运行用例。
	 *
	 * @param args 忽略
	 * @throws Exception 测试失败时
	 */
	public static void main(String[] args) throws Exception {
		testConcurrentSameIdIsMerged();
		System.out.println("MultiModalMcpTest: 全部用例通过");
	}

	/** 同一图片 id 的并发识别应合并为一次。 */
	private static void testConcurrentSameIdIsMerged() throws Exception {
		String id = repeat('a', 72);
		SlowStorage tos = new SlowStorage(id);
		AtomicReference<String> firstResult = new AtomicReference<>();
		AtomicReference<String> secondResult = new AtomicReference<>();
		Thread first = new Thread(() -> firstResult.set(MultiModalMcp.recognizeImage(tos, id, null)), "mm-first");
		Thread second = new Thread(() -> secondResult.set(MultiModalMcp.recognizeImage(tos, id, null)), "mm-second");
		first.setDaemon(true);
		second.setDaemon(true);
		try {
			first.start();
			check(tos.awaitEntered(), "第一个识别应进入对象存储检查");
			second.start();
			// 等第二个线程进入等待：合并成功时它等在第一个 future 上；
			// 没有合并时它会再次进入 exists，exists 计数会变成 2。
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && second.isAlive()
					&& second.getState() != Thread.State.WAITING
					&& second.getState() != Thread.State.TIMED_WAITING) {
				Thread.sleep(10);
			}
			check(tos.getExistsCalls() == 1,
					"第二个识别不应重复检查图片，实际 exists 调用次数：" + tos.getExistsCalls());
			tos.release();
			first.join(5000);
			second.join(5000);
			check(!first.isAlive() && !second.isAlive(), "识别线程应在超时前结束");
			check("图片不存在或被清理".equals(firstResult.get()), "假存储应返回图片不存在，实际：" + firstResult.get());
			check(firstResult.get().equals(secondResult.get()), "两个识别应返回同一个结果");
			check(tos.getExistsCalls() == 1, "最终只应检查一次图片，实际：" + tos.getExistsCalls());
		} finally {
			tos.release();
			first.interrupt();
			second.interrupt();
		}
	}

	private static String repeat(char c, int count) {
		StringBuilder builder = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			builder.append(c);
		}
		return builder.toString();
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}

	/** 只在目标图片 id 上阻塞的假对象存储。 */
	private static final class SlowStorage implements ObjectStorageProvider {
		private final String id;
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger existsCalls = new AtomicInteger();

		SlowStorage(String id) {
			this.id = id;
		}

		boolean awaitEntered() throws InterruptedException {
			return entered.await(5, TimeUnit.SECONDS);
		}

		void release() {
			release.countDown();
		}

		int getExistsCalls() {
			return existsCalls.get();
		}

		@Override
		public boolean exists(String fn, Consumer<UsageIntf<?>> addUsage) {
			if (id.equals(fn)) {
				existsCalls.incrementAndGet();
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return false;
			}
			return false;
		}

		@Override
		public String upload(String fn, byte[] data, Consumer<UsageIntf<?>> addUsage) throws IOException {
			throw new IOException("测试不上传文件");
		}

		@Override
		public String getUrl(String fn, Consumer<UsageIntf<?>> addUsage) {
			return "https://example/" + fn;
		}

		@Override
		public String getPublicUrl(String fn, Consumer<UsageIntf<?>> addUsage) {
			return "https://example/" + fn;
		}
	}
}
