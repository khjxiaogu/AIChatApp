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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * 公屏聊天工具需要的能力：发到群、询问用户。
 * <p>
 * 普通聊天机器人（{@code NapCatAIConnector}）只实现这一层，因此只能注册 {@link GroupChatMcp}；
 * 私聊相关能力见 {@link PrivateChatHost}。
 * </p>
 */
public interface GroupChatHost {
	/**
	 * 发一条群消息。
	 *
	 * @param state   发起会话
	 * @param groupId 目标群 id
	 * @param text    正文
	 * @return 给模型看的结果
	 * @throws Exception 发送失败
	 */
	String sendExplicitGroupTo(AIGroupSession state, String groupId, String text) throws Exception;

	/**
	 * 向当前会话的用户提问，结果通过异步工具任务返回。
	 *
	 * @param state         发起会话
	 * @param question      问题
	 * @param options       可选项，可为空
	 * @param allowFreeText 是否允许自由回复
	 * @param multiSelect   是否多选
	 * @return 异步结果
	 */
	CompletableFuture<ToolResult> askUser(AIGroupSession state, String question, List<String> options,
			boolean allowFreeText, boolean multiSelect);
}
