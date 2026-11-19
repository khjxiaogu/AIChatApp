/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.mcp.chat;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 「显式发送」模式下的公屏聊天工具：正文不会自动发出，必须调用这些工具才会说话。
 * <p>
 * 只有群发与询问用户；私聊相关工具（send_private / list_agents / handover_private 等）
 * 属于多 agent 模式，见 {@link PrivateChatMcp}。
 * </p>
 */
public final class GroupChatMcp {

	private GroupChatMcp() {
	}

	/** 公屏发送 + 询问用户。 */
	public static ToolProvider create(final GroupChatHost connector, final AIGroupSession state) {
		return ToolProvider.of(sendGroup(connector, state), askUser(connector, state));
	}

	/** 发到当前群，或私聊 agent 有权限的指定群。 */
	static ToolData sendGroup(final GroupChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("send_group",
				"发一条 QQ 群公屏消息。**你的正文不会自动发出，必须调用本工具**。"
						+ "公屏 agent 省略 group_id 即当前群；私聊 agent 必须指定自己有上下文权限的 group_id。")
				.putParam("text", "要发送到群里的正文。", "正文")
				.putOptionalParam("group_id", "目标群 id；公屏 agent 省略时用当前群。", "群 id")
				.orderSensitive()
				.tool((params, output, manager) -> {
					JsonObject json = json(params);
					String text = JsonConfig.getNonBlankString(json, "text");
					if (text == null || text.trim().isEmpty()) {
						return completed("缺少群消息内容 text");
					}
					String groupId = JsonConfig.getNonBlankString(json, "group_id");
					if (groupId == null) {
						groupId = state.groupId;
					}
					if (groupId == null || groupId.startsWith("private:")) {
						return completed("私聊会话发群消息需要指定 group_id");
					}
					try {
						return completed(connector.sendExplicitGroupTo(state, groupId, text));
					} catch (Throwable t) {
						return completed("群消息发送失败：" + describe(t));
					}
				})
				.build();
	}

	/**
	 * 询问用户：发送带选项按钮的询问卡片；用户点按钮或直接回复后，异步任务完成并触发下一轮 AI。
	 */
	static ToolData askUser(final GroupChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("ask_user",
				"向当前会话的用户提问，并让用户从可选项中选择或自由回复。会发送适配器的询问卡片"
						+ "（不支持卡片时退回文字）；用户回答后你会收到异步任务结果。"
						+ "需要确认、二选一、收集信息时使用。")
				.putParam("question", "要问用户的问题。", "问题")
				.putOptionalParam("options", "可选项，用 | 分隔；为空则只支持自由回复。", "选项")
				.putOptionalParam("allow_free_text", "是否允许自由文字回复，true/false，默认 false。", "自由回复")
				.async()
				.orderSensitive()
				.tool((params, output, manager) -> {
					JsonObject json = json(params);
					String question = JsonConfig.getNonBlankString(json, "question");
					if (question == null) {
						return completed("缺少问题 question");
					}
					java.util.List<String> options = new java.util.ArrayList<>();
					String raw = JsonConfig.getNonBlankString(json, "options");
					if (raw != null) {
						for (String part : raw.split("\\s*\\|\\s*")) {
							if (!part.trim().isEmpty()) {
								options.add(part.trim());
							}
						}
					}
					boolean allowFree = booleanValue(json, "allow_free_text", false);
					return connector.askUser(state, question, options, allowFree, false);
				})
				.build();
	}

	// ==================== 工具方法 ====================

	static CompletableFuture<ToolResult> completed(String text) {
		return CompletableFuture.completedFuture(ToolResult.text(text));
	}

	static JsonObject json(String params) {
		if (params == null || params.trim().isEmpty()) {
			return new JsonObject();
		}
		try {
			JsonElement element = JsonParser.parseString(params);
			return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException ex) {
			return new JsonObject();
		}
	}

	static boolean booleanValue(JsonObject json, String key, boolean def) {
		String value = JsonConfig.getNonBlankString(json, key);
		if (value == null) {
			return def;
		}
		return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)
				|| "是".equals(value);
	}

	static String describe(Throwable ex) {
		if (ex == null) {
			return "未知错误";
		}
		Throwable cause = ex;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}
