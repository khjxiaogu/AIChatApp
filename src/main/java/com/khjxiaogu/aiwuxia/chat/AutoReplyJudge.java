package com.khjxiaogu.aiwuxia.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelBudget;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * 群聊“是否需要主动回复”判定器。
 * <p>
 * 默认关闭。开启后，群消息会先累积起来（@ 机器人触发正常回复时清零）：
 * </p>
 * <ul>
 *   <li>累计达到配置的条数（默认 20 条）时立即判定一次；</li>
 *   <li>否则每隔配置的时间（默认 10 分钟）判定一次；</li>
 *   <li>判定时把累计的对话内容作为用户消息发给关闭思考模式的模型，让它判断当前角色是否需要发言；</li>
 *   <li>需要发言时调用注入的回复触发器，走正常的会话回复流程，并记入会话配额。</li>
 * </ul>
 * <p>
 * 判定请求使用 {@link ModelBudget#MIMO} 级别的预算，由路由器在未指定模型时选择最接近的提供商。
 * 判定是尽力而为的：模型不可用、返回无法解析或判定失败时一律按“不回复”处理，不影响正常消息。
 * </p>
 */
public class AutoReplyJudge {

	/** 默认判定间隔：10 分钟。 */
	public static final long DEFAULT_INTERVAL_MS = 10 * 60 * 1000L;
	/** 默认消息条数阈值：20 条。 */
	public static final int DEFAULT_MESSAGE_THRESHOLD = 20;

	/** 单次判定最多携带的群消息条数。 */
	private static final int MAX_JUDGE_MESSAGES = 40;
	/** 单次判定最多携带的字符数，避免长消息把判定请求撑得过大。 */
	private static final int MAX_JUDGE_CHARS = 6000;
	/** 定时检查间隔：每 30 秒扫一次是否到判定时间。 */
	private static final long CHECK_INTERVAL_SECONDS = 30;

	/** 判定提示词：要求模型只输出 JSON。 */
	private static final String JUDGE_PROMPT = "你是群聊机器人的“是否需要发言”判定器。"
			+ "根据给出的群聊记录，判断角色「%s」现在是否应该主动发言。\n"
			+ "判定规则：\n"
			+ "1. 只有最近的消息明确在叫这个角色、向这个角色提问、或话题与该角色直接相关时，才需要发言；\n"
			+ "2. 普通群友之间的闲聊、与角色无关的话题、别人已经互相回应过的话题，不需要发言；\n"
			+ "3. 同一话题已经被回应过时，不要重复发言；\n"
			+ "4. 拿不准时选择不发言。\n"
			+ "只输出 JSON，不要输出其它内容：{\"reply\": true 或 false, \"reason\": \"一句话原因\"}";

	/** 从返回文本中兜底提取 {@code "reply": true/false}。 */
	private static final Pattern REPLY_PATTERN = Pattern.compile("(?i)\"reply\"\\s*:\\s*(true|false)");

	/** 判定配置。 */
	public static final class Settings {
		/** 是否启用，默认关闭。 */
		public final boolean enabled;
		/** 定时判定间隔（毫秒）。 */
		public final long intervalMs;
		/** 消息条数阈值。 */
		public final int messageThreshold;
		/** 判定请求的预算倍率。 */
		public final double budget;

		private Settings(boolean enabled, long intervalMs, int messageThreshold, double budget) {
			this.enabled = enabled;
			this.intervalMs = intervalMs;
			this.messageThreshold = messageThreshold;
			this.budget = budget;
		}

		/**
		 * 从 agent 配置读取 {@code autoReply} 段；缺失或格式错误时使用默认值（关闭）。
		 *
		 * @param config agent 配置
		 * @return 配置对象，不会为 null
		 */
		public static Settings parse(JsonObject config) {
			boolean enabled = false;
			long interval = DEFAULT_INTERVAL_MS;
			int threshold = DEFAULT_MESSAGE_THRESHOLD;
			double budget = ModelBudget.MIMO;
			if (config != null && config.has("autoReply") && config.get("autoReply").isJsonObject()) {
				JsonObject jo = config.getAsJsonObject("autoReply");
				enabled = readBoolean(jo, "enabled", enabled);
				// 间隔太短会让判定过于频繁，最低 30 秒
				interval = Math.max(30_000L, readLong(jo, "intervalMs", interval));
				threshold = Math.max(1, (int) readLong(jo, "messageThreshold", threshold));
				budget = readDouble(jo, "budget", budget);
			}
			return new Settings(enabled, interval, threshold, budget);
		}

		private static boolean readBoolean(JsonObject jo, String key, boolean fallback) {
			try {
				if (jo.has(key) && jo.get(key).isJsonPrimitive()) {
					return jo.get(key).getAsBoolean();
				}
			} catch (RuntimeException e) {
				// 配置写错时用默认值
			}
			return fallback;
		}

		private static long readLong(JsonObject jo, String key, long fallback) {
			try {
				if (jo.has(key) && jo.get(key).isJsonPrimitive()) {
					return jo.get(key).getAsLong();
				}
			} catch (RuntimeException e) {
				// 配置写错时用默认值
			}
			return fallback;
		}

		private static double readDouble(JsonObject jo, String key, double fallback) {
			try {
				if (jo.has(key) && jo.get(key).isJsonPrimitive()) {
					return jo.get(key).getAsDouble();
				}
			} catch (RuntimeException e) {
				// 配置写错时用默认值
			}
			return fallback;
		}
	}

	/** 一条待判定的群消息。 */
	private static final class MessageLine {
		/** 递增序号，用于判定结束后只移除已判定的部分。 */
		final long sequence;
		final String text;

		MessageLine(long sequence, String text) {
			this.sequence = sequence;
			this.text = text;
		}
	}

	/** 一个会话的待判定状态。 */
	private static final class Pending {
		final AIGroupSession session;
		final Settings settings;
		/** 累积的群消息，按时间顺序。 */
		final List<MessageLine> lines = new ArrayList<>();
		/** 累积的群消息条数。 */
		int count;
		/** 消息序号，判定时用来区分判定期间新到的消息。 */
		long messageSequence;
		/** 回复代次：正常回复会递增，用于丢弃过期的判定结果。 */
		int generation;
		/** 是否正在判定，避免并发重复请求。 */
		boolean judging;
		/** 上一次判定的时间。 */
		long lastJudge = System.currentTimeMillis();

		Pending(AIGroupSession session, Settings settings) {
			this.session = session;
			this.settings = settings;
		}
	}

	private final Map<AIGroupSession, Pending> sessions = new ConcurrentHashMap<>();
	private final Consumer<AIGroupSession> replyTrigger;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "chat-auto-reply");
		thread.setDaemon(true);
		return thread;
	});
	/** 判定请求可能阻塞较久，单独用工作线程池，避免拖住定时检查。 */
	private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "chat-auto-reply-worker");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * @param replyTrigger 判定为需要回复时的触发器（通常是会话的 {@code submitMessage}）
	 */
	public AutoReplyJudge(Consumer<AIGroupSession> replyTrigger) {
		this.replyTrigger = replyTrigger;
		scheduler.scheduleWithFixedDelay(this::checkInterval, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS,
				TimeUnit.SECONDS);
	}

	/**
	 * 注册（或更新）一个会话的判定配置；未启用时移除已有状态。
	 *
	 * @param session 群聊会话
	 * @param config  agent 配置
	 */
	public void register(AIGroupSession session, JsonObject config) {
		if (session == null) {
			return;
		}
		Settings settings = Settings.parse(config);
		if (!settings.enabled) {
			sessions.remove(session);
			return;
		}
		sessions.put(session, new Pending(session, settings));
	}

	/**
	 * 记录一条群消息。
	 *
	 * @param session    会话
	 * @param senderName 发送者昵称
	 * @param senderId   发送者账号 id
	 * @param text       消息文本
	 */
	public void onMessage(AIGroupSession session, String senderName, String senderId, String text) {
		Pending pending = sessions.get(session);
		if (pending == null || text == null || text.trim().isEmpty()) {
			return;
		}
		boolean reachThreshold;
		synchronized (pending) {
			pending.count++;
			pending.lines.add(new MessageLine(++pending.messageSequence,
					formatLine(senderName, senderId, text)));
			while (pending.lines.size() > MAX_JUDGE_MESSAGES) {
				pending.lines.remove(0);
			}
			reachThreshold = pending.count >= pending.settings.messageThreshold;
		}
		if (reachThreshold) {
			judge(pending);
		}
	}

	/**
	 * 正常回复开始时调用：清空累计内容，避免把已被回复的消息再判定一次。
	 *
	 * @param session 会话
	 */
	public void onReply(AIGroupSession session) {
		Pending pending = sessions.get(session);
		if (pending == null) {
			return;
		}
		synchronized (pending) {
			pending.generation++;
			pending.count = 0;
			pending.lines.clear();
			pending.lastJudge = System.currentTimeMillis();
		}
	}

	/** 定时检查：到间隔且有累计消息时判定。 */
	private void checkInterval() {
		try {
			long now = System.currentTimeMillis();
			for (Pending pending : sessions.values()) {
				boolean due;
				synchronized (pending) {
					due = !pending.judging && !pending.lines.isEmpty()
							&& now - pending.lastJudge >= pending.settings.intervalMs;
				}
				if (due) {
					judge(pending);
				}
			}
		} catch (Throwable t) {
			// 定时任务抛异常会被调度器静默终止，这里兜住并记录
			System.err.println("[主动回复判定] 定时检查失败：" + t);
		}
	}

	/** 异步执行一次判定。 */
	private void judge(Pending pending) {
		final int generation;
		final long upTo;
		final List<String> lines;
		synchronized (pending) {
			if (pending.judging || pending.lines.isEmpty()) {
				return;
			}
			pending.judging = true;
			pending.lastJudge = System.currentTimeMillis();
			generation = pending.generation;
			upTo = pending.messageSequence;
			lines = new ArrayList<>(pending.lines.size());
			for (MessageLine line : pending.lines) {
				lines.add(line.text);
			}
		}
		workers.execute(() -> {
			boolean needReply;
			try {
				needReply = askForReply(pending.session, pending.settings.budget, lines);
			} catch (Throwable t) {
				System.err.println("[主动回复判定] 判定失败：" + t);
				needReply = false;
			}
			boolean trigger;
			synchronized (pending) {
				pending.judging = false;
				// 只移除本次判定覆盖的消息，判定期间新到的消息留给下一轮
				pending.lines.removeIf(line -> line.sequence <= upTo);
				pending.count = pending.lines.size();
				pending.lastJudge = System.currentTimeMillis();
				// 判定期间已经发生过正常回复，说明这批消息已被处理，丢弃本次结果
				trigger = needReply && pending.generation == generation;
			}
			if (trigger) {
				System.out.println("[主动回复判定] 角色「" + roleName(pending.session) + "」需要发言，触发回复");
				try {
					replyTrigger.accept(pending.session);
				} catch (Throwable t) {
					System.err.println("[主动回复判定] 触发回复失败：" + t);
				}
			}
		});
	}

	/**
	 * 请求模型判定是否需要回复。
	 *
	 * @param budget 判定请求的预算倍率
	 */
	private boolean askForReply(AIGroupSession session, double budget, List<String> lines) throws IOException {
		StringBuilder prompt = new StringBuilder();
		String lastReply = lastAssistantText(session);
		if (!lastReply.isEmpty()) {
			prompt.append("角色上一次的发言：\n").append(lastReply).append("\n\n");
		}
		prompt.append("群聊记录（按时间顺序）：\n");
		prompt.append(joinLines(lines));
		Builder builder = AIRequest.builder(session.user)
				.taskType(TaskType.ANALYSIS)
				.category(ModelCategory.NON_REASONING)
				.format(ResponseFormat.JSON)
				.temperature(0.2f)
				.maxTokens(256)
				.budget(budget);
		builder.addHistoryItem(Role.SYSTEM, String.format(JUDGE_PROMPT, roleName(session)));
		builder.addHistoryItem(Role.USER, prompt.toString());
		AIOutput output = LLMConnector.call(builder.build());
		output.addUsageListener(session::addUsage);
		return parseReply(output.getContentText());
	}

	/** 角色最近一条发言的纯文本，供判定时避免重复回应；取不到时返回空串。 */
	private static String lastAssistantText(AIGroupSession session) {
		try {
			HistoryItem last = session.getHistory() == null ? null : session.getHistory().peekLast();
			if (last == null || last.getRole() != Role.ASSISTANT || last.getDisplayContent() == null) {
				return "";
			}
			String text = last.getDisplayContent().getPlainText().replaceAll("\\s+", " ").trim();
			return text.length() > 500 ? text.substring(0, 500) : text;
		} catch (Throwable t) {
			return "";
		}
	}

	/** 拼接判定用的消息文本，限制总字符数。 */
	private static String joinLines(List<String> lines) {
		StringBuilder text = new StringBuilder();
		for (String line : lines) {
			if (text.length() + line.length() > MAX_JUDGE_CHARS) {
				break;
			}
			text.append(line).append('\n');
		}
		if (text.length() == 0 && !lines.isEmpty()) {
			String last = lines.get(lines.size() - 1);
			text.append(last, 0, Math.min(last.length(), MAX_JUDGE_CHARS)).append('\n');
		}
		return text.toString();
	}

	/** 格式化一条消息，压缩空白并限制单条长度。 */
	private static String formatLine(String senderName, String senderId, String text) {
		String name = senderName == null || senderName.trim().isEmpty() ? senderId : senderName.trim();
		String content = text.replaceAll("\\s+", " ").trim();
		if (content.length() > 500) {
			content = content.substring(0, 500);
		}
		return "[" + (name == null ? "" : name) + "(" + (senderId == null ? "" : senderId) + ")] " + content;
	}

	/** 角色名，缺失时用“机器人”占位。 */
	private static String roleName(AIGroupSession session) {
		try {
			String name = session.getRoleName(Role.ASSISTANT);
			return name == null || name.trim().isEmpty() ? "机器人" : name.trim();
		} catch (Throwable t) {
			return "机器人";
		}
	}

	/**
	 * 解析模型返回的判定结果。
	 * <p>
	 * 优先读取 JSON 里的 {@code reply} 字段；返回不是合法 JSON 时用正则兜底。
	 * 无法判断时一律返回 false（不主动发言）。
	 * </p>
	 */
	private static boolean parseReply(String text) {
		if (text == null) {
			return false;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		try {
			JsonElement parsed = JsonParser.parseString(trimmed);
			if (parsed.isJsonObject()) {
				JsonElement reply = parsed.getAsJsonObject().get("reply");
				if (reply != null && reply.isJsonPrimitive()) {
					if (reply.getAsJsonPrimitive().isBoolean()) {
						return reply.getAsBoolean();
					}
					return isAffirmative(reply.getAsString());
				}
			}
		} catch (RuntimeException e) {
			// 不是合法 JSON，走下面的正则兜底
		}
		Matcher matcher = REPLY_PATTERN.matcher(trimmed);
		if (matcher.find()) {
			return "true".equalsIgnoreCase(matcher.group(1));
		}
		return false;
	}

	/** 宽松判断肯定词。 */
	private static boolean isAffirmative(String value) {
		String word = value == null ? "" : value.trim().toLowerCase();
		return "true".equals(word) || "yes".equals(word) || "y".equals(word) || "是".equals(word)
				|| "需要".equals(word) || "应该".equals(word);
	}
}
