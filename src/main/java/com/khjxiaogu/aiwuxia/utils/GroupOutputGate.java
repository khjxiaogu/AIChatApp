package com.khjxiaogu.aiwuxia.utils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个 QQ 群的发送顺序闸门。
 * <p>
 * 目标：无论工具、模型、语音合成的快慢如何，发往同一个群的消息都按“输入被接纳的先后”顺序发出。
 * </p>
 * <p>
 * 用法：消息被接纳时调用 {@link #register()} 取得序号；该轮真正要发送的内容准备好之后调用
 * {@link #complete(long, Runnable)} 交付“只包含一次 WS 发送”的动作。闸门内部用一条专用线程按序号
 * 依次执行这些动作，因此调用方不会被排序阻塞，也不会占用共享线程池。
 * </p>
 * <p>
 * 若队头序号在超时时间内仍未就绪，闸门只在后台打印错误并跳过该序号，让后面的消息继续发送；
 * 该序号之后完成时会被立即补发（排在已经发出的消息之后），不会向群里发任何错误提示。
 * </p>
 * <p>
 * 超时时间可用系统属性 {@code aiwuxia.groupSendTimeoutMs} 覆盖，默认 5 分钟。计时从某个序号
 * “成为队头”开始，而不是从登记开始，因此前面排队的耗时不会误伤后面已经准备好的回复。
 * </p>
 */
public final class GroupOutputGate {
	/** 队头序号等待就绪的默认上限。 */
	private static final long DEFAULT_TIMEOUT_MS = 300_000L;

	private static final int PENDING = 0;
	private static final int READY = 1;
	private static final int CANCELLED = 2;

	/** 一个序号的槽位。所有字段都受 {@link #lock} 保护。 */
	private static final class Slot {
		Runnable emit;
		int state = PENDING;
	}

	private final String group;
	private final long timeoutMs;
	private final Object lock = new Object();
	private final Map<Long, Slot> slots = new HashMap<>();
	/** 已经超时跳过、后来才完成的发送动作，完成即补发。 */
	private final Deque<Runnable> late = new ArrayDeque<>();
	private long nextSequence = 1L;
	private long nextToSend = 1L;
	/** 当前队头序号与它成为队头的时刻（只用于超时判断，受 lock 保护）。 */
	private long currentHead = -1L;
	private long headSince;
	private boolean closed;
	private final Thread worker;

	public GroupOutputGate(String group) {
		this(group, Long.getLong("aiwuxia.groupSendTimeoutMs", DEFAULT_TIMEOUT_MS));
	}

	public GroupOutputGate(String group, long timeoutMs) {
		this.group = group;
		this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
		this.worker = new Thread(this::run, "qq-send-order-" + group);
		this.worker.setDaemon(true);
		this.worker.start();
	}

	/**
	 * 在“输入被接纳”时登记一个序号。必须早于任何生成、工具与语音调用。
	 *
	 * @return 本轮的顺序号
	 */
	public long register() {
		synchronized (lock) {
			long seq = nextSequence++;
			slots.put(seq, new Slot());
			lock.notifyAll();
			return seq;
		}
	}

	/**
	 * 交付本轮的发送动作。动作只应包含一次 WS 发送（以及必要的本地状态更新），不得包含等待回执、
	 * 模型调用、语音合成等阻塞操作。
	 *
	 * @param seq   {@link #register()} 返回的序号
	 * @param emit  发送动作；为 null 时等价于 {@link #cancel(long)}
	 */
	public void complete(long seq, Runnable emit) {
		if (emit == null) {
			cancel(seq);
			return;
		}
		synchronized (lock) {
			if (seq < nextToSend) {
				// 该序号已经因超时被跳过，完成时补发（排在当前已发出的消息之后）
				late.addLast(emit);
				lock.notifyAll();
				return;
			}
			Slot slot = slots.get(seq);
			if (slot == null) {
				// 槽位已被清理（正常不会发生）：直接补发，避免丢消息
				System.err.println("[SEND-ORDER] group=" + group + " seq=" + seq + " 槽位缺失，直接补发。");
				late.addLast(emit);
				lock.notifyAll();
				return;
			}
			if (slot.state != PENDING) {
				return;
			}
			slot.emit = emit;
			slot.state = READY;
			lock.notifyAll();
		}
	}

	/**
	 * 放弃本轮（例如不是助手回复、或生成阶段就抛错），让闸门继续推进。
	 *
	 * @param seq 序号
	 */
	public void cancel(long seq) {
		synchronized (lock) {
			if (seq < nextToSend) {
				return;
			}
			Slot slot = slots.get(seq);
			if (slot == null || slot.state != PENDING) {
				return;
			}
			slot.state = CANCELLED;
			slot.emit = null;
			lock.notifyAll();
		}
	}

	/** 关闭闸门线程。 */
	public void shutdown() {
		synchronized (lock) {
			closed = true;
			lock.notifyAll();
		}
	}

	private void run() {
		while (true) {
			Runnable action;
			synchronized (lock) {
				action = awaitNext();
			}
			if (action == null) {
				return;
			}
			try {
				action.run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	/**
	 * 取下一个要执行的发送动作。调用时必须持有 {@link #lock}。
	 *
	 * @return 发送动作；null 表示闸门已关闭
	 */
	private Runnable awaitNext() {
		while (true) {
			if (closed) {
				return null;
			}
			Runnable pendingLate = late.pollFirst();
			if (pendingLate != null) {
				return pendingLate;
			}
			Slot head = slots.get(nextToSend);
			if (head == null) {
				if (nextToSend < nextSequence) {
					// 已分配的序号却找不到槽位，正常不会发生；跳过避免卡死
					nextToSend++;
					continue;
				}
				// 下一个序号还没登记，等输入
				try {
					lock.wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					closed = true;
					return null;
				}
				continue;
			}
			if (currentHead != nextToSend) {
				currentHead = nextToSend;
				headSince = System.currentTimeMillis();
			}
			if (head.state == READY) {
				Runnable emit = head.emit;
				slots.remove(nextToSend);
				nextToSend++;
				return emit;
			}
			if (head.state == CANCELLED) {
				slots.remove(nextToSend);
				nextToSend++;
				continue;
			}
			long elapsed = System.currentTimeMillis() - headSince;
			long remain = timeoutMs - elapsed;
			if (remain <= 0) {
				System.err.println("[SEND-TIMEOUT] group=" + group + " seq=" + nextToSend + " 成为队头后 " + elapsed
						+ "ms 仍未就绪，跳过该条发送；完成后再补发。");
				slots.remove(nextToSend);
				nextToSend++;
				continue;
			}
			try {
				lock.wait(remain);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				closed = true;
				return null;
			}
		}
	}
}
