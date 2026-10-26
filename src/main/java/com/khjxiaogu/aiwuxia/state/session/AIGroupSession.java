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
package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolTask;
import com.khjxiaogu.aiwuxia.mcp.AgentPingMcp;
import com.khjxiaogu.aiwuxia.mcp.CrontabMcp;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;

public class AIGroupSession extends AISession {
	/** 待写入对话历史的一条群消息批次。 */
	private static class QueuedMessage {
		final boolean important;
		final List<Supplier<MessageContent>> getter;
		QueuedMessage(boolean important, List<Supplier<MessageContent>> getter) {
			this.important = important;
			this.getter = getter;
		}
	}
	public JsonObject config;
	public long groupId;
	public long botId;
	List<QueuedMessage> important=new ArrayList<>();
	ArrayDeque<QueuedMessage> messageQueue=new ArrayDeque<>();
	public StringBuilder lastReasoner;
	public Consumer<StringBuilder> reasonerConsumer;
	private StringBuilder lastState=new StringBuilder();
	public File saveData;
	public boolean outputReaonser;
	public boolean outputPrice;
	/** 最近一条有效群消息的发送者 QQ，用于工具审核时告诉管理员是谁触发的。 */
	public volatile long lastSpeakerId;
	/** 最近一条有效群消息的发送者昵称/群名片，可能为空。 */
	public volatile String lastSpeakerName = "";
	/** 会话处理工具附件的入口，由 QQ 连接器注入；工具本身不直接发送消息。 */
	private volatile Consumer<MessageContent> toolAttachmentHandler;
	/** 异步工具任务完成后的会话入口，由 QQ 连接器注入，用于写入通知并触发下一轮。 */
	private volatile Consumer<ToolTask> toolTaskCompletionHandler;
	public AIGroupSession(String user, ISaveData data,File saveData, AIApplication aiapp,JsonObject config) {
		super(user, data, aiapp);
		this.groupId=config.get("groupId").getAsLong();
		this.botId=config.get("botId").getAsLong();
		if(config.has("hasReaonser"))
		this.outputReaonser=config.get("hasReaonser").getAsBoolean();
		if(config.has("hasPrice"))
		this.outputPrice=config.get("hasPrice").getAsBoolean();
		this.config=config;
		this.saveData=saveData;
	}
	Object queueLock=new Object();
	Object statusLock=new Object();
	public void addStatus(String msg) {
		synchronized(statusLock) {
			lastState.append(msg);
		}
	}
	public String getAndClearStatus() {
		synchronized(statusLock) {
			String ret=lastState.toString();
			lastState=new StringBuilder();
			return ret;
		}
	}

	/**
	 * 设置工具附件处理器（由 QQ 连接器注入）。
	 *
	 * @param handler 附件处理器，null 表示不发送附件
	 */
	public void setToolAttachmentHandler(Consumer<MessageContent> handler) {
		this.toolAttachmentHandler = handler;
	}

	/**
	 * 设置异步工具任务完成处理器（由 QQ 连接器注入）。
	 *
	 * @param handler 任务完成处理器，null 表示使用默认状态通知
	 */
	public void setToolTaskCompletionHandler(Consumer<ToolTask> handler) {
		this.toolTaskCompletionHandler = handler;
	}

	@Override
	protected void handleToolAttachment(MessageContent content) {
		Consumer<MessageContent> handler = toolAttachmentHandler;
		if (handler != null) {
			handler.accept(content);
		}
	}

	@Override
	protected void notifyToolStatus(String tool, String message, boolean success) {
		addStatus("<tool name=\"" + tool + "\" status=\"" + (success ? "success" : "failed") + "\">" + message
				+ "</tool>");
	}

	@Override
	protected void handleToolTaskCompleted(ToolTask task) {
		Consumer<ToolTask> handler = toolTaskCompletionHandler;
		if (handler != null) {
			handler.accept(task);
			return;
		}
		super.handleToolTaskCompleted(task);
	}
    /**
     * 思维链写入钩子。
     * <p>
     * 内容由 {@link HistoryWriteContext} 写进本轮记录；需要向群聊输出思维链时在这里
     * 累积到 {@link #lastReasoner}，供 {@link #printReasoning()} 下发。
     * </p>
     */
    @Override
    protected void onReasonerAppend(MessageContent current) {
        if(outputReaonser) {
        	if(lastReasoner==null)
	        	lastReasoner = new StringBuilder();
        	if(current instanceof PlainText)
        		lastReasoner.append(current.toText());
        }
    }
	public void addMessage(List<Supplier<MessageContent>> msg,boolean important) {
		synchronized(queueLock) {
			messageQueue.add(new QueuedMessage(important,msg));
			if(messageQueue.size()>10) {
				QueuedMessage ctx=messageQueue.pollFirst();
				if(ctx.important)
					this.important.add(ctx);
			}
		}
	}
	public MessageContents getPrompt() {
		MutableMessageContents content;
		synchronized(queueLock) {
	        DateFormat formatter = DateFormat.getDateTimeInstance();
			content=new MutableMessageContents("<system>当前时间："+formatter.format(new Date())+"，注意遵守系统提示词和系统规则，不要声称自己关机了或者累了，全程使用中文对话。</system>\n");
			for(QueuedMessage cctx:important) {
				for(Supplier<MessageContent> cmsg:cctx.getter)
					content.add(cmsg.get());
			}
			important.clear();
			while(true) {
				QueuedMessage msg=messageQueue.pollFirst();
				if(msg==null)break;
				for(Supplier<MessageContent> cmsg:msg.getter)
					content.add(cmsg.get());
			}
		}
		return content;
	}
	public void printReasoning() {
		if(lastReasoner!=null&&outputReaonser&&reasonerConsumer!=null)
			reasonerConsumer.accept(lastReasoner);

		lastReasoner=null;
	}
	@Override
	public void onToolcall(ToolCallContent content) {
		printReasoning();
	}

	@Override
	public void close() {
		// 注销角色呼叫与定时触发绑定，避免静态表继续持有已关闭的会话
		AgentPingMcp.unregister(getRoleName(Role.ASSISTANT), this);
		CrontabMcp.unregister(botId, this);
		super.close();
	}
}
