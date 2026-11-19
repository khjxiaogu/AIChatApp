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

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 「显式发送」模式下的私聊聊天工具：只用于多 agent 架构（每个私聊用户一个 agent）。
 * <p>
 * AI 的正文不会自动发出，必须调用这些工具才会说话：
 * </p>
 * <ul>
 *   <li>{@link #privateOnly(PrivateChatHost, AIGroupSession)}：私聊 + 指定群公屏 + 移交；</li>
 *   <li>{@link #privateAndGroup(PrivateChatHost, AIGroupSession)}：私聊 + 公屏 + 移交；</li>
 *   <li>{@link #groupOnly(PrivateChatHost, AIGroupSession)}：公屏 + 列举/移交（不直接发私聊）。</li>
 * </ul>
 * <p>
 * 公屏与询问工具复用 {@link GroupChatMcp}；私聊发送会把对方临时加入「agent 私聊白名单」
 * （带过期时间、落盘保存），之后对方的私聊才会触发对应 agent。移交工具只把摘要交给目标 agent，
 * 不传聊天记录。
 * </p>
 */
public final class PrivateChatMcp {

	private PrivateChatMcp() {
	}

	/** 私聊 + 指定群公屏 + agent 任务。 */
	public static ToolProvider privateOnly(final PrivateChatHost connector, final AIGroupSession state) {
		return ToolProvider.of(sendPrivate(connector, state), GroupChatMcp.sendGroup(connector, state),
				listAgents(connector, state), groupHistory(connector, state),
				GroupChatMcp.askUser(connector, state), sendToAgent(connector, state), handover(connector, state));
	}

	/** 私聊 + 公屏 + agent 任务。 */
	public static ToolProvider privateAndGroup(final PrivateChatHost connector, final AIGroupSession state) {
		return ToolProvider.of(sendPrivate(connector, state), GroupChatMcp.sendGroup(connector, state),
				listAgents(connector, state), groupHistory(connector, state),
				GroupChatMcp.askUser(connector, state), sendToAgent(connector, state), handover(connector, state));
	}

	/** 公屏；仍保留列举/移交/任务能力。 */
	public static ToolProvider groupOnly(final PrivateChatHost connector, final AIGroupSession state) {
		return ToolProvider.of(GroupChatMcp.sendGroup(connector, state), listAgents(connector, state),
				groupHistory(connector, state), GroupChatMcp.askUser(connector, state),
				sendToAgent(connector, state), handover(connector, state));
	}

	/** 给指定 QQ 发私聊。 */
	private static ToolData sendPrivate(final PrivateChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("send_private",
				"给用户发一条 QQ 私聊。**你的正文不会自动发出，必须调用本工具**。"
						+ "user 省略时发给当前正在私聊你的用户；对方会因此被临时加入私聊白名单。")
				.putParam("text", "要发送的私聊正文。", "正文")
				.putOptionalParam("user", "目标账号 id（QQ 号，或 QQ 官方的 user_openid）；省略时发给当前私聊对象。", "账号")
				.orderSensitive()
				.tool((params, output, manager) -> {
					JsonObject json = GroupChatMcp.json(params);
					String text = JsonConfig.getNonBlankString(json, "text");
					if (text == null || text.trim().isEmpty()) {
						return GroupChatMcp.completed("缺少私聊内容 text");
					}
					String user = JsonConfig.getNonBlankString(json, "user");
					if (user == null) {
						user = connector.currentPrivatePeer(state);
					}
					if (user == null || user.isEmpty()) {
						return GroupChatMcp.completed("缺少目标 user（账号 id）");
					}
					try {
						return GroupChatMcp.completed(connector.sendExplicitPrivate(state, user, text));
					} catch (Throwable t) {
						return GroupChatMcp.completed("私聊发送失败：" + GroupChatMcp.describe(t));
					}
				})
				.build();
	}

	/** 给另一个 agent 发任务，并触发对方处理。 */
	private static ToolData sendToAgent(final PrivateChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("send_to_agent",
				"把任务/信息交给另一个 agent（公屏或私聊），对方会收到并开始一轮处理。"
						+ "私聊 agent 需要先 list_agents 看允许的目标。")
				.putParam("agent", "目标 agent 名称（list_agents 返回的名字）。", "目标 agent")
				.putParam("task", "要交给对方的任务/摘要，写清背景、目标和期望结果。", "任务")
				.orderSensitive()
				.tool((params, output, manager) -> {
					JsonObject json = GroupChatMcp.json(params);
					String agent = JsonConfig.getNonBlankString(json, "agent");
					String task = JsonConfig.getNonBlankString(json, "task");
					if (agent == null || task == null) {
						return GroupChatMcp.completed("缺少 agent 或 task");
					}
					return GroupChatMcp.completed(connector.sendTaskToAgent(state, agent, task));
				})
				.build();
	}

	/**
	 * 按需查询某个群最近的历史消息（最多 200 条）。私聊里除非必要不要调用。
	 */
	private static ToolData groupHistory(final PrivateChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("group_history",
				"查询某个群最近的历史消息（每个群最多保留 200 条）。公屏默认上下文只有最近 10 条，"
						+ "需要更早/更多信息时才调用。私聊里除非必要不要查询群上下文。")
				.putParam("group_id", "要查询的群 id。", "群 id")
				.putOptionalParam("limit", "最多返回多少条，默认 20，最多 200。", "条数")
				.putOptionalParam("keyword", "只返回包含该关键词的消息。", "关键词")
				.tool((params, output, manager) -> {
					JsonObject json = GroupChatMcp.json(params);
					String groupId = JsonConfig.getNonBlankString(json, "group_id");
					if (groupId == null) {
						return GroupChatMcp.completed("缺少群 id（group_id）");
					}
					int limit = JsonConfig.getInt(json, "limit", 20);
					return GroupChatMcp.completed(connector.groupHistory(state, groupId, limit,
							JsonConfig.getNonBlankString(json, "keyword")));
				})
				.build();
	}

	/**
	 * 列举当前可联络/可接手的 agent，并附带对应用户在群里的群名片。
	 */
	private static ToolData listAgents(final PrivateChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("list_agents",
				"列出当前可联络/可接手的 agent，并附带该用户在来源群里的群名片。"
						+ "不确定把手上的私聊交给谁时先调用它。")
				.putOptionalParam("user", "要展示群名片的用户 id；省略时用当前私聊对象。", "用户")
				.tool((params, output, manager) -> {
					JsonObject json = GroupChatMcp.json(params);
					try {
						return GroupChatMcp
								.completed(connector.listContactableAgents(state, JsonConfig.getNonBlankString(json, "user")));
					} catch (Throwable t) {
						return GroupChatMcp.completed("列举 agent 失败：" + GroupChatMcp.describe(t));
					}
				})
				.build();
	}

	/**
	 * 把当前私聊移交给可接手的另一个 agent（同群，或公屏 agent → 私人 agent）。
	 * <p>
	 * 只传摘要，不传聊天记录；移交后该用户的私聊由目标 agent 接手，目标 agent 会收到一条
	 * 「私聊移交」通知（含用户账号与摘要）。
	 * </p>
	 */
	private static ToolData handover(final PrivateChatHost connector, final AIGroupSession state) {
		return new ToolData.Builder("handover_private",
				"把某个用户（或当前私聊）移交给可接手的另一个 agent。公屏 agent 可用 user 指定要把谁交给私聊 agent。"
						+ "只把 summary 交给对方（不传聊天记录），之后该用户的私聊由对方接手。"
						+ "不确定对方名字时先用 list_agents 看可用列表（会带该用户的群名片）。")
				.putParam("agent", "目标 agent 名称（同群其它 agent 的应用名/配置名）。", "目标 agent")
				.putParam("summary", "给目标 agent 的摘要：用户是谁、聊到哪、接下来要做什么。", "摘要")
				.putOptionalParam("user", "要移交的账号 id（QQ 号 / QQ 官方 openid）；省略时用当前私聊对象。", "账号")
				.orderSensitive()
				.tool((params, output, manager) -> {
					JsonObject json = GroupChatMcp.json(params);
					String agent = JsonConfig.getNonBlankString(json, "agent");
					String summary = JsonConfig.getNonBlankString(json, "summary");
					if (agent == null || agent.trim().isEmpty()) {
						return GroupChatMcp.completed("缺少目标 agent 名称");
					}
					if (summary == null || summary.trim().isEmpty()) {
						return GroupChatMcp.completed("缺少移交摘要 summary");
					}
					String user = JsonConfig.getNonBlankString(json, "user");
					if (user == null) {
						user = connector.currentPrivatePeer(state);
					}
					if (user == null || user.isEmpty()) {
						return GroupChatMcp.completed("缺少要移交的 user（账号 id）");
					}
					try {
						return GroupChatMcp.completed(connector.handoverPrivate(state, agent, user, summary));
					} catch (Throwable t) {
						return GroupChatMcp.completed("移交失败：" + GroupChatMcp.describe(t));
					}
				})
				.build();
	}
}
