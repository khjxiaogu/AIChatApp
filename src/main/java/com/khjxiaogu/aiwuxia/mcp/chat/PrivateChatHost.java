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

import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * 私聊聊天工具需要的能力：发私聊、移交私聊、列举可联络 agent、查询群历史。
 * <p>
 * 只有多 agent 连接器（每个私聊用户一个 agent）实现这一层，因此 {@link PrivateChatMcp}
 * 只在多 agent 模式下注册；普通聊天机器人只实现 {@link GroupChatHost}。
 * </p>
 */
public interface PrivateChatHost extends GroupChatHost {
	/**
	 * 当前会话正在私聊的对象；没有时返回空串。
	 *
	 * @param state 会话
	 * @return 账号 id
	 */
	String currentPrivatePeer(AIGroupSession state);

	/**
	 * 发一条私聊。
	 *
	 * @param state 会话
	 * @param user  账号 id
	 * @param text  正文
	 * @return 给模型看的结果
	 * @throws Exception 发送失败
	 */
	String sendExplicitPrivate(AIGroupSession state, String user, String text) throws Exception;

	/**
	 * 把任务交给另一个 agent，并触发对方一轮处理。
	 *
	 * @param from        发起会话
	 * @param targetAgent 目标 agent 名
	 * @param task        任务内容
	 * @return 给模型看的结果
	 */
	String sendTaskToAgent(AIGroupSession from, String targetAgent, String task);

	/**
	 * 查询某个群的历史消息。
	 *
	 * @param from    发起会话
	 * @param groupId 群 id
	 * @param limit   条数
	 * @param keyword 关键词
	 * @return 可读结果
	 */
	String groupHistory(AIGroupSession from, String groupId, int limit, String keyword);

	/**
	 * 列举可联络/可接手的 agent。
	 *
	 * @param from 发起会话
	 * @param user 展示群名片的账号，可为空
	 * @return 可读列表
	 */
	String listContactableAgents(AIGroupSession from, String user);

	/**
	 * 把当前私聊移交给另一个 agent。
	 *
	 * @param from        发起会话
	 * @param targetAgent 目标 agent 名
	 * @param user        账号 id
	 * @param summary     摘要
	 * @return 给模型看的结果
	 * @throws Exception 移交失败
	 */
	String handoverPrivate(AIGroupSession from, String targetAgent, String user, String summary) throws Exception;
}
