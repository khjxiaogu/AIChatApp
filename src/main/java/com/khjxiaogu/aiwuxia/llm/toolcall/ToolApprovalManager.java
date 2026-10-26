package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工具调用许可等待管理器。
 * <p>
 * 任何线程（工具调用执行线程）需要用户许可时调用 {@link #request}，该方法会<b>阻塞当前线程</b>
 * 直到许可结果返回；同一用户（会话）的多个请求被<b>串行化</b>处理：同一时刻只向用户提出一个请求，
 * 其余请求排队等待，避免同时弹出多个确认框、也避免用户答复与请求错配。
 * </p>
 * <p>
 * 结果通过两种方式回填，二者等价：</p>
 * <ul>
 *   <li>{@link ToolApprovalRequester} 拿到 {@link ToolApprovalRequest} 后调用其
 *       {@code setResult/approve/deny}；</li>
 *   <li>会话收到用户答复（例如 WebSocket 帧）后调用 {@link #complete(String, boolean)}。</li>
 * </ul>
 * <p>
 * 为避免无人值守时永久阻塞，默认带超时（{@link #DEFAULT_TIMEOUT_MILLIS}），超时按<b>拒绝</b>处理
 * （失败关闭）；连接断开等场景可调用 {@link #cancelAll(String)} 立即按拒绝收敛。
 * </p>
 */
public class ToolApprovalManager {

	/** 默认等待用户答复的超时时间：3 分钟，超时按拒绝处理。 */
	public static final long DEFAULT_TIMEOUT_MILLIS = 3 * 60 * 1000L;

	private final ToolApprovalRequester requester;
	private final long timeoutMillis;
	/** 按用户（会话）隔离的串行执行队列 */
	private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
	/** 待答复的请求：请求 id → 请求（供按 id 回填答复） */
	private final Map<String, ToolApprovalRequest> pending = new ConcurrentHashMap<>();

	public ToolApprovalManager(ToolApprovalRequester requester) {
		this(requester, DEFAULT_TIMEOUT_MILLIS);
	}

	/**
	 * @param requester     异步请求接口，不可为 null
	 * @param timeoutMillis 等待答复的超时毫秒数，小于等于 0 表示无限等待
	 */
	public ToolApprovalManager(ToolApprovalRequester requester, long timeoutMillis) {
		this.requester = requester;
		this.timeoutMillis = timeoutMillis;
	}

	/**
	 * 请求一次工具调用许可，当前线程阻塞直到获得结果。
	 *
	 * @param userId    会话/用户标识，同一标识的请求串行执行
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数
	 * @param reason    需要许可的原因，可为 null
	 * @return 许可返回 true；用户拒绝、超时或线程被中断返回 false
	 * @throws InterruptedException 等待过程中线程被中断（此时该请求按拒绝收敛）
	 */
	public boolean request(String userId, String toolName, String arguments, String reason)
			throws InterruptedException {
		return request(userId, toolName, arguments, null, reason);
	}

	/**
	 * 请求一次工具调用许可，并附带面向用户的参数文本（参数名已换成可读名称）。
	 *
	 * @param userId           会话/用户标识，同一标识的请求串行执行
	 * @param toolName         工具名称
	 * @param arguments        模型给出的原始参数
	 * @param displayArguments 面向用户的参数文本，可为 null（此时界面回退到原始参数）
	 * @param reason           需要许可的原因，可为 null
	 * @return 许可返回 true；用户拒绝、超时或线程被中断返回 false
	 * @throws InterruptedException 等待过程中线程被中断（此时该请求按拒绝收敛）
	 */
	public boolean request(String userId, String toolName, String arguments, String displayArguments, String reason)
			throws InterruptedException {
		RequestContext ctx = new RequestContext(userId, toolName, arguments, displayArguments, reason);
		session(userId).execute(ctx);
		return await(ctx);
	}

	/**
	 * 为指定会话创建阻塞等待许可的许可方，可直接用于
	 * {@link ToolCallManager#setApprover(ToolCallApprover)}。
	 *
	 * @param userId 会话/用户标识
	 */
	public ToolCallApprover approverFor(String userId) {
		return new ManagedToolCallApprover(this, userId);
	}

	/**
	 * 按请求 id 回填用户答复（会话收到答复时调用）。
	 *
	 * @param requestId 请求 id
	 * @param approved  是否许可
	 * @return 答复是否被接受；请求不存在或已有结果（超时、断线取消、重复答复）返回 false
	 */
	public boolean complete(String requestId, boolean approved) {
		if (requestId == null) {
			return false;
		}
		ToolApprovalRequest request = pending.get(requestId);
		return request != null && request.setResult(approved);
	}

	/**
	 * 放弃某会话的全部待答复与排队请求（例如连接断开），一律按拒绝处理。
	 *
	 * @param userId 会话/用户标识
	 */
	public void cancelAll(String userId) {
		UserSession session = sessions.get(key(userId));
		if (session != null) {
			session.cancelAll();
		}
	}

	/** 待答复的请求快照（请求 id → 请求），只读。 */
	public Map<String, ToolApprovalRequest> getPendingRequests() {
		return Collections.unmodifiableMap(pending);
	}

	/** 等待答复的超时毫秒数，小于等于 0 表示无限等待。 */
	public long getTimeoutMillis() {
		return timeoutMillis;
	}

	private UserSession session(String userId) {
		return sessions.computeIfAbsent(key(userId), k -> new UserSession());
	}

	private static String key(String userId) {
		return userId == null ? "" : userId;
	}

	/** 阻塞等待结果；超时/异常/中断时按拒绝收敛，保证队列不会卡住。 */
	private boolean await(RequestContext ctx) throws InterruptedException {
		try {
			return timeoutMillis <= 0 ? ctx.future.get() : ctx.future.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			cancel(ctx);
			return false;
		} catch (ExecutionException e) {
			cancel(ctx);
			return false;
		} catch (InterruptedException e) {
			cancel(ctx);
			throw e;
		}
	}

	/** 放弃一个请求：标记取消、按拒绝完成调用方 future，并解除正在等待答复的请求。 */
	private void cancel(RequestContext ctx) {
		ctx.cancelled = true;
		ctx.future.complete(false);
		ToolApprovalRequest current = ctx.current;
		if (current != null) {
			// 触发 whenComplete：完成 future 并处理队列中的下一个请求
			current.setResult(false);
		}
	}

	private void notifyRequester(ToolApprovalRequest request) {
		if (request == null || request.isCompleted()) {
			return;
		}
		try {
			requester.requestApproval(request);
		} catch (Throwable ex) {
			ex.printStackTrace();
			request.setResult(false);
		}
	}

	/** 调用方的上下文：排队状态与正在等待答复的请求。 */
	private static class RequestContext {
		final String userId;
		final String toolName;
		final String arguments;
		final String displayArguments;
		final String reason;
		final CompletableFuture<Boolean> future = new CompletableFuture<>();
		volatile boolean cancelled;
		volatile ToolApprovalRequest current;

		RequestContext(String userId, String toolName, String arguments, String displayArguments, String reason) {
			this.userId = userId;
			this.toolName = toolName;
			this.arguments = arguments;
			this.displayArguments = displayArguments;
			this.reason = reason;
		}
	}

	/**
	 * 单个用户（会话）的请求队列与串行执行逻辑。
	 */
	private class UserSession {
		private final ReentrantLock lock = new ReentrantLock();
		private boolean busy = false;
		private final Queue<RequestContext> waitingQueue = new ConcurrentLinkedQueue<>();
		/** 正在等待答复的请求上下文，空闲时为 null */
		private RequestContext currentCtx;

		/** 执行请求（可能排队等待）。 */
		void execute(RequestContext ctx) {
			ToolApprovalRequest toNotify = null;
			lock.lock();
			try {
				if (!busy) {
					busy = true;
					toNotify = start(ctx);
				} else {
					waitingQueue.add(ctx);
				}
			} finally {
				lock.unlock();
			}
			// 在锁外通知请求方，避免请求方同步回填结果时嵌套持锁
			notifyRequester(toNotify);
		}

		/** 在持锁状态下启动一个请求，返回需要通知请求方的记录。 */
		private ToolApprovalRequest start(RequestContext ctx) {
			ToolApprovalRequest request = new ToolApprovalRequest(ctx.userId, ctx.toolName, ctx.arguments,
					ctx.displayArguments, ctx.reason);
			ctx.current = request;
			currentCtx = ctx;
			pending.put(request.getId(), request);
			request.whenComplete((result, ex) -> {
				pending.remove(request.getId());
				ctx.current = null;
				ctx.future.complete(ex != null ? false : result);
				processNextRequest();
			});
			return request;
		}

		/** 处理队列中的下一个请求；队列为空则标记空闲。 */
		private void processNextRequest() {
			ToolApprovalRequest toNotify = null;
			lock.lock();
			try {
				while (true) {
					RequestContext next = waitingQueue.poll();
					if (next == null) {
						// 无更多等待请求，标记为空闲
						busy = false;
						currentCtx = null;
						break;
					}
					if (next.cancelled) {
						// 等待期间已被调用方放弃，直接按拒绝收敛
						next.future.complete(false);
						continue;
					}
					toNotify = start(next);
					break;
				}
			} finally {
				lock.unlock();
			}
			notifyRequester(toNotify);
		}

		/** 放弃该会话全部排队与等待中的请求。 */
		void cancelAll() {
			List<ToolApprovalRequest> toDeny = new ArrayList<>();
			lock.lock();
			try {
				RequestContext next;
				while ((next = waitingQueue.poll()) != null) {
					next.cancelled = true;
					next.future.complete(false);
				}
				RequestContext current = currentCtx;
				if (current != null) {
					current.cancelled = true;
					current.future.complete(false);
					if (current.current != null) {
						toDeny.add(current.current);
					}
				}
			} finally {
				lock.unlock();
			}
			// 在锁外解除等待，避免 whenComplete 回调重入
			for (ToolApprovalRequest request : toDeny) {
				request.setResult(false);
			}
		}
	}
}
