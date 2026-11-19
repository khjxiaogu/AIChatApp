package com.khjxiaogu.aiwuxia.mcp.chat;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.NapCatAIConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * 消息管理工具：撤回机器人自己发出的消息。
 * <p>
 * 只允许撤回当前会话自己发送、且适配器仍能处理的消息（QQ 官方限制发出后 2 分钟内）。
 * 不传 {@code message_id} 时撤回本会话最近一条；{@code count} 可一次撤回最近多条。
 * </p>
 */
public final class MessageMcp {

	private static final int MAX_RECALL_COUNT = 5;

	private MessageMcp() {
	}

	public static ToolData recallMessage(final NapCatAIConnector connector, final AIGroupSession state) {
		return new ToolData.Builder("recall_message",
				"撤回机器人自己发送的消息。不传 message_id 时撤回最近一条；QQ 官方只允许撤回发出后 2 分钟内的消息。")
				.putOptionalParam("message_id", "要撤回的消息 id；省略时撤回本会话最近一条。", "消息ID")
				.putOptionalParam("count", "撤回最近几条，默认 1，最多 5；指定 message_id 时忽略。", "数量")
				.toolText((params, output, manager) -> {
					JsonObject json = parse(params);
					String messageId = JsonConfig.getString(json, "message_id");
					try {
						if (messageId != null && !messageId.trim().isEmpty()) {
							return connector.recallMessage(state, messageId.trim());
						}
						int count = JsonConfig.getInt(json, "count", 1);
						if (count < 1) {
							count = 1;
						}
						if (count > MAX_RECALL_COUNT) {
							count = MAX_RECALL_COUNT;
						}
						List<String> recent = connector.recentSentMessages(state);
						if (recent.isEmpty()) {
							return "没有可撤回的消息";
						}
						if (count > recent.size()) {
							count = recent.size();
						}
						StringBuilder result = new StringBuilder();
						int success = 0;
						for (int i = 0; i < count; i++) {
							String mid = recent.get(i);
							try {
								String one = connector.recallMessage(state, mid);
								if (!one.startsWith("已撤回")) {
									result.append("\n撤回 ").append(mid).append(" 失败：").append(one);
									break;
								}
								success++;
							} catch (Throwable t) {
								result.append("\n撤回 ").append(mid).append(" 失败：").append(describe(t));
								break;
							}
						}
						return "已撤回 " + success + " 条消息" + result;
					} catch (Throwable t) {
						return "撤回失败：" + describe(t);
					}
				}).build();
	}

	private static JsonObject parse(String params) {
		try {
			return JsonParser.parseString(params).getAsJsonObject();
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}

	private static String describe(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}
