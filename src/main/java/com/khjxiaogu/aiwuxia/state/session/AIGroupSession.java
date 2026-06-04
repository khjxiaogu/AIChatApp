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
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.message.MessageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.ISaveData;

public class AIGroupSession extends AISession {
	public static class CallContext{
		public final long qq;
		public final String text;
		public final boolean important;
		public final List<Supplier<MessageContent>> getter;
		CallContext(long qq, String text, List<Supplier<MessageContent>> getter,boolean important) {
			super();
			this.qq = qq;
			this.text = text;
			this.getter = getter;
			this.important=important;
		}
		@Override
		public String toString() {
			return "CallContext [qq=" + qq + ", text=" + text + "]";
		}

	}
	public static class CurrentContext{
		public final CallContext ctx;
		public final MessageContents content;
		CurrentContext(CallContext ctx, MessageContents content) {
			super();
			this.ctx = ctx;
			this.content = content;
		}
		@Override
		public String toString() {
			return "CurrentContext [ctx=" + ctx + "]";
		}
		
	}
	public JsonObject config;
	public long groupId;
	public long botId;
	public CallContext currentCtx;
	List<CallContext> important=new ArrayList<>();
	ArrayDeque<CallContext> messageQueue=new ArrayDeque<>();
	public List<ToolData> tools=new ArrayList<>();
	private StringBuilder lastState=new StringBuilder();
	public File saveData;
	public AIGroupSession(String user, ISaveData data,File saveData, AIApplication aiapp,JsonObject config) {
		super(user, data, aiapp);
		this.groupId=config.get("groupId").getAsLong();
		this.botId=config.get("botId").getAsLong();
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
	public void addMessage(long qq,String text,List<Supplier<MessageContent>> msg,boolean important) {
		synchronized(queueLock) {
			messageQueue.add(new CallContext(qq,text,msg,important));
			if(messageQueue.size()>10) {
				CallContext ctx=messageQueue.pollFirst();
				if(ctx.important)
					this.important.add(ctx);
			}
		}
	}
	public CurrentContext getPrompt() {
		MessageContents content;
		CallContext last=null;
		synchronized(queueLock) {
	        DateFormat formatter = DateFormat.getDateTimeInstance();
			content=new MessageContents("当前时间："+formatter.format(new Date())+"\n");
			for(CallContext cctx:important) {
				for(Supplier<MessageContent> cmsg:cctx.getter)
					content.add(cmsg.get());
			}
			important.clear();
			while(true) {
				CallContext msg=messageQueue.pollFirst();
				if(msg==null)break;
				last=msg;
				for(Supplier<MessageContent> cmsg:msg.getter)
					content.add(cmsg.get());
			}
		}
		return new CurrentContext(last,content);
	}
	public void addTool(ToolData tool) {
		tools.add(tool);
	}
	public List<ToolData> getAvailableTools(){
		return tools;
	}
}
