package com.khjxiaogu.aiwuxia.tools;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalRequest;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallApprover;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * QQ 群工具调用审核：把需要许可的工具调用私聊给配置的管理员，管理员回复「允许」/「拒绝」。
 * <p>
 * 参考 {@code DeepseekHarnessConnector} 的审批流程：
 * </p>
 * <ol>
 *   <li>工具调用被 {@link ToolCallPermission} 判定为需要审核后，{@link ToolCallManager} 会调用
 *       这里提供的 {@link ToolCallApprover}；</li>
 *   <li>审核请求私聊发给该机器人配置的 {@code adminId}，内容包括群号、机器人名、触发者、
 *       工具名、原因和参数；</li>
 *   <li>管理员在私聊里回复「允许」/「同意」/「1」等放行，回复「拒绝」/「不同意」/「2」等拒绝；
 *       拒绝时还可以回复「拒绝 原因」（冒号、逗号、空格分隔均可），原因会作为工具结果回传给模型；</li>
 *   <li>等待超过配置的超时时间按拒绝处理，并私聊告知管理员；</li>
 *   <li>同一会话的请求由 {@link ToolApprovalManager} 串行化，避免多个确认同时弹给管理员。</li>
 * </ol>
 * <p>
 * 只有管理员本人在私聊里的答复算数；没有配置管理员时由调用方直接按拒绝处理。
 * </p>
 */
public class QqToolApprovalManager implements Closeable {

	/** 默认等待管理员答复的超时：2 分钟。 */
	public static final long DEFAULT_TIMEOUT_MILLIS = 120000L;

	/** 私聊发送器：把一条文本发给指定 QQ。 */
	@FunctionalInterface
	public interface PrivateMessageSender {
		/**
		 * 发送一条私聊文本。
		 *
		 * @param qq   QQ 号
		 * @param text 文本
		 * @throws Exception 发送失败
		 */
		void send(long qq, String text) throws Exception;
	}

	/** 一条等待管理员答复的审核请求。 */
	private static final class PendingApproval {
		final AIGroupSession session;
		final long botId;
		final long adminId;
		final ToolApprovalRequest request;
		/** 是否已经得到管理员答复或已超时（避免重复处理）。 */
		boolean answered;

		PendingApproval(AIGroupSession session, long botId, long adminId, ToolApprovalRequest request) {
			this.session = session;
			this.botId = botId;
			this.adminId = adminId;
			this.request = request;
		}
	}

	private final PrivateMessageSender sender;
	private final long defaultTimeoutMillis;
	/** 每个会话一个许可管理器，保证同一会话的请求串行询问。 */
	private final Map<AIGroupSession, ToolApprovalManager> managers = new IdentityHashMap<>();
	/** 待答复请求，按到达顺序排列；私聊回复取最前面的一条。 */
	private final Deque<PendingApproval> queue = new ArrayDeque<>();
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, runnable -> {
		Thread thread = new Thread(runnable, "qq-tool-approval");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicInteger pendingCount = new AtomicInteger();

	/**
	 * @param sender              私聊发送器
	 * @param defaultTimeoutMillis 默认等待答复的超时毫秒数，小于等于 0 表示不等待（直接拒绝）
	 */
	public QqToolApprovalManager(PrivateMessageSender sender, long defaultTimeoutMillis) {
		this.sender = sender;
		this.defaultTimeoutMillis = defaultTimeoutMillis > 0 ? defaultTimeoutMillis : DEFAULT_TIMEOUT_MILLIS;
	}

	/**
	 * 为指定会话创建（或复用）许可方。
	 *
	 * @param session       会话
	 * @param adminId       管理员 QQ
	 * @param timeoutMillis 等待答复的超时毫秒数，小于等于 0 时使用默认值
	 * @return 可直接交给 {@code ToolCallManager#setApprover} 的许可方
	 */
	public ToolCallApprover approverFor(AIGroupSession session, long adminId, long timeoutMillis) {
		long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : defaultTimeoutMillis;
		ToolApprovalManager manager;
		synchronized (managers) {
			manager = managers.get(session);
			if (manager == null) {
				// 给 ToolApprovalManager 留一点余量：正常超时由下面的定时任务先收敛，
				// 避免管理器的超时与队列清理之间出现“旧请求挡住新答复”的窗口
				manager = new ToolApprovalManager(
						request -> requestApproval(session, adminId, request, effectiveTimeout),
						effectiveTimeout + 2000L);
				managers.put(session, manager);
			}
		}
		return manager.approverFor("qq-" + session.botId + "-" + session.groupId);
	}

	/** 把一条已排到队首的许可请求私聊给管理员。 */
	private void requestApproval(AIGroupSession session, long adminId, ToolApprovalRequest request,
			long timeoutMillis) {
		PendingApproval pending = new PendingApproval(session, session.botId, adminId, request);
		synchronized (queue) {
			queue.addLast(pending);
			pendingCount.incrementAndGet();
		}
		try {
			sender.send(adminId, buildQuestion(session, adminId, request, timeoutMillis));
		} catch (Throwable t) {
			// 发不出去就失败关闭，别让工具调用白等一个超时
			synchronized (queue) {
				pending.answered = true;
				if (queue.remove(pending)) {
					pendingCount.decrementAndGet();
				}
			}
			request.deny();
			System.out.println("[QQ工具审核] 审核请求私聊发送失败，已按拒绝处理：" + t);
			return;
		}
		// 正常超时由这里先收敛；ToolApprovalManager 自己略晚超时，只作兜底
		long delay = Math.max(1000, timeoutMillis);
		scheduler.schedule(() -> onTimeout(pending), delay, TimeUnit.MILLISECONDS);
	}

	/** 超时：拒绝请求、移出队列并私聊告知管理员。 */
	private void onTimeout(PendingApproval pending) {
		synchronized (queue) {
			if (pending.answered) {
				return;
			}
			pending.answered = true;
			if (queue.remove(pending)) {
				pendingCount.decrementAndGet();
			}
		}
		pending.request.deny();
		send(pending.adminId, "【工具审核】超时未答复，已按拒绝处理。工具：「" + pending.request.getToolName() + "」");
	}

	/**
	 * 处理一条管理员私聊答复。
	 * <p>
	 * 只有「允许/拒绝」这类明确的答复会被消费；其它私聊内容返回 false，由连接器按原逻辑忽略。
	 * </p>
	 *
	 * @param botId    收到私聊的机器人 QQ（来自事件的 {@code self_id}），可为 0
	 * @param senderId 发消息的 QQ
	 * @param text     消息正文
	 * @return true 表示这条私聊已经被当作审核答复处理
	 */
	public boolean answer(long botId, long senderId, String text) {
		ApprovalAnswer answer = parseAnswer(text);
		if (answer == null) {
			return false;
		}
		PendingApproval target = null;
		synchronized (queue) {
			target = findPending(botId, senderId);
			if (target == null) {
				return false;
			}
			if (target.answered) {
				return false;
			}
			queue.remove(target);
			pendingCount.decrementAndGet();
			target.answered = true;
		}
		boolean accepted = answer.allowed ? target.request.approve()
				: target.request.deny(answer.reason);
		if (!accepted) {
			send(target.adminId, "【工具审核】该请求已超时或已处理，本次答复未生效。");
			return true;
		}
		StringBuilder reply = new StringBuilder("【工具审核】已")
				.append(answer.allowed ? "允许" : "拒绝")
				.append("工具「").append(target.request.getToolName()).append("」");
		if (answer.allowed) {
			reply.append("执行。");
		} else {
			if (answer.reason != null) {
				reply.append("，拒绝原因：").append(answer.reason);
			}
			reply.append("。");
		}
		send(target.adminId, reply.toString());
		return true;
	}

	/** 找到与这条私聊匹配的待答复请求：按机器人 + 管理员匹配；botId 未知时才放宽。 */
	private PendingApproval findPending(long botId, long senderId) {
		for (PendingApproval pending : queue) {
			if (pending.adminId == senderId && (botId <= 0 || pending.botId == botId)) {
				return pending;
			}
		}
		return null;
	}

	/** 组装私聊给管理员的审核问题。 */
	private String buildQuestion(AIGroupSession session, long adminId, ToolApprovalRequest request,
			long timeoutMillis) {
		StringBuilder question = new StringBuilder("【工具审核】");
		question.append("群 ").append(session.groupId).append(" 的机器人「")
				.append(safeRoleName(session)).append("」准备调用工具「").append(request.getToolName()).append("」。");
		long speakerId = session.lastSpeakerId;
		if (speakerId > 0) {
			if (speakerId == adminId) {
				question.append("最近发言者是你自己。");
			} else {
				question.append("最近发言者：群友 QQ ").append(speakerId);
				if (session.lastSpeakerName != null && !session.lastSpeakerName.trim().isEmpty()) {
					question.append("（").append(shorten(session.lastSpeakerName.trim(), 40)).append("）");
				}
				question.append("。");
			}
		}
		String reason = request.getReason();
		if (reason != null && !reason.trim().isEmpty()) {
			question.append("\n原因：").append(reason.trim());
		}
		// 优先展示工具声明的人可读参数（参数名已换成“提示词”“参考图”等），
		// 工具没有声明可读名称时再回退到原始 JSON
		String arguments = request.getDisplayArguments();
		if (arguments == null || arguments.trim().isEmpty()) {
			arguments = request.getArguments();
		}
		if (arguments != null && !arguments.trim().isEmpty()) {
			question.append("\n参数：\n").append(shorten(arguments.trim(), 600));
		}
		question.append("\n直接回复「允许」或「拒绝」；拒绝时可回复「拒绝 原因」，原因会转告模型。（")
				.append(timeoutMillis / 1000L).append(" 秒内有效）");
		return question.toString();
	}

	/** 读取机器人名，缺失时用问号占位。 */
	private static String safeRoleName(AIGroupSession session) {
		try {
			String name = session.getRoleName(Role.ASSISTANT);
			return name == null || name.trim().isEmpty() ? "?" : name.trim();
		} catch (Throwable t) {
			return "?";
		}
	}

	/** 管理员答复：是否允许，以及拒绝时附带的原因（可能为 null）。 */
	private static final class ApprovalAnswer {
		final boolean allowed;
		final String reason;

		ApprovalAnswer(boolean allowed, String reason) {
			this.allowed = allowed;
			this.reason = reason;
		}
	}

	/** 可单独作为答复的放行词（不附带原因）。 */
	private static final String[] ALLOW_WORDS = { "允许", "同意", "可以", "好", "是", "行", "批准", "1", "y", "yes",
			"ok", "allow" };

	/** 可单独作为答复的拒绝词；带原因时按最长前缀优先匹配，避免「不同意」被拆成「不」。 */
	private static final String[] DENY_WORDS = { "拒绝原因", "拒绝理由", "不同意", "拒绝", "不行", "驳回", "不要", "不",
			"否", "2", "n", "no", "deny" };

	/**
	 * 解析管理员答复。
	 * <p>
	 * 除了单独的「允许/拒绝」，还支持「拒绝 原因」「拒绝：原因」「拒绝，原因」等写法：
	 * 关键词后必须跟分隔符，剩余部分作为拒绝原因回传给模型。
	 * </p>
	 *
	 * @param text 答复正文
	 * @return 解析结果；不是明确的答复时返回 null
	 */
	private static ApprovalAnswer parseAnswer(String text) {
		if (text == null) {
			return null;
		}
		String candidate = text.trim();
		// 去掉句末标点后再做精确匹配，保留原文供原因使用
		while (!candidate.isEmpty()) {
			char last = candidate.charAt(candidate.length() - 1);
			if (last == '。' || last == '.' || last == '！' || last == '!' || last == '，' || last == ',') {
				candidate = candidate.substring(0, candidate.length() - 1).trim();
			} else {
				break;
			}
		}
		if (candidate.isEmpty()) {
			return null;
		}
		String normalized = candidate.toLowerCase();
		// 1) 整句就是放行/拒绝词（最常见，也兼容大小写英文）
		if (containsWord(ALLOW_WORDS, normalized)) {
			return new ApprovalAnswer(true, null);
		}
		if (containsWord(DENY_WORDS, normalized)) {
			return new ApprovalAnswer(false, null);
		}
		// 2) 关键词 + 分隔符 + 原因
		ApprovalAnswer allowWithRemark = matchWithReason(candidate, normalized, ALLOW_WORDS, true);
		if (allowWithRemark != null) {
			return allowWithRemark;
		}
		return matchWithReason(candidate, normalized, DENY_WORDS, false);
	}

	/** 整句是否恰好等于某个关键词。 */
	private static boolean containsWord(String[] words, String normalized) {
		for (String word : words) {
			if (normalized.equals(word)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 匹配「关键词 + 分隔符 + 剩余文本」。
	 *
	 * @param keywords 关键词列表（调用方保证拒绝词按最长优先排列）
	 * @param allowed 命中后是否按“允许”处理
	 * @return 匹配结果；不匹配返回 null
	 */
	private static ApprovalAnswer matchWithReason(String candidate, String normalized, String[] keywords,
			boolean allowed) {
		for (String keyword : keywords) {
			if (!normalized.startsWith(keyword) || normalized.length() <= keyword.length()) {
				continue;
			}
			char next = normalized.charAt(keyword.length());
			if (!isSeparator(next)) {
				continue;
			}
			String reason = candidate.substring(keyword.length() + 1).trim();
			reason = stripReasonPrefix(reason);
			return new ApprovalAnswer(allowed, allowed || reason.isEmpty() ? null : reason);
		}
		return null;
	}

	/** 关键词与原因之间允许的分隔符。 */
	private static boolean isSeparator(char c) {
		return Character.isWhitespace(c) || c == ':' || c == '：' || c == ',' || c == '，' || c == ';' || c == '；'
				|| c == '|' || c == '-' || c == '—' || c == '、';
	}

	/** 去掉原因前多余的「原因/理由」前缀，让回传给模型的文本更直接。 */
	private static String stripReasonPrefix(String reason) {
		if (reason == null || reason.isEmpty()) {
			return "";
		}
		for (String prefix : new String[] { "原因是", "理由为", "原因", "理由" }) {
			if (reason.startsWith(prefix)) {
				String rest = reason.substring(prefix.length()).trim();
				if (!rest.isEmpty() && isSeparator(rest.charAt(0))) {
					rest = rest.substring(1).trim();
				}
				return rest;
			}
		}
		return reason;
	}

	/** 发送私聊，失败只记录日志，避免拖垮工具调用。 */
	private void send(long qq, String text) {
		try {
			sender.send(qq, text);
		} catch (Throwable t) {
			System.out.println("[QQ工具审核] 私聊发送失败（QQ " + qq + "）：" + t);
		}
	}

	/** 当前待答复数量（供日志/测试）。 */
	public int getPendingCount() {
		return pendingCount.get();
	}

	/** 截断过长文本，保留首尾。 */
	private static String shorten(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		int head = max * 2 / 3;
		int tail = max - head - 3;
		return value.substring(0, head) + "..." + value.substring(value.length() - tail);
	}

	/** 关闭：放弃全部待答复请求（按拒绝）并停止超时线程。 */
	@Override
	public void close() {
		List<PendingApproval> pending;
		synchronized (queue) {
			pending = new ArrayList<>(queue);
			queue.clear();
			pendingCount.set(0);
		}
		for (PendingApproval approval : pending) {
			approval.answered = true;
			approval.request.deny();
		}
		scheduler.shutdownNow();
	}
}
