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
package com.khjxiaogu.aiwuxia.apps;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;
import com.khjxiaogu.aiwuxia.utils.MessageReader;

public class AIGroupApplication extends AIApplication {
	String name;
	String charaName;
	String system;
	@Override
	public void provideInitial(AISession state) {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String constructSystem(ApplicationState state) {
		return "";
	}

	@Override
	public String getBrief(AISession state) {
		return name;
	}
	public ApplicationState sendAndProcessResultStreamed(AISession state, HistoryWriteContext write, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		state.setCurrentOutput(resp);
		resp.addUsageListener(state::addUsage);
		ApplicationState oldstate = precessResponse(resp, state, write);
		write.setLastState(oldstate);
		return oldstate;
		
	}
	public ApplicationState precessResponse(AIOutput resp, AISession state, HistoryWriteContext write) throws IOException {
		ApplicationState oldstate = new ApplicationState(state.getState());
		// 思维链交给后台线程边读边写本轮记录：正文在这里按自己的节奏写，两者互不等待
		observeReasonerWrite(handleReasonerContent(resp, state, write));
		// 正文按内容段直接写进本轮记录：内容段到达就写，不再先拼成一整个字符串，
		// 前端因此能随着模型输出逐段看到回复
		MessageReader reader = resp.getContent();
		if (reader != null) {
			MessageContent current;
			boolean started = false;
			boolean wrote = false;
			while ((current = reader.read()) != null) {
				if (!started) {
					// 流式输出的开头常常是空行，与旧版逐行读取一致地跳过
					current = skipLeadingBlank(current);
					if (current == null)
						continue;
					started = true;
				}
				write.append(current, true);
				wrote = true;
			}
			if (!wrote) {
				logger.warning("本轮正文为空，没有回复内容");
			}
		}
		return oldstate;
	}
	public void compactHistory(AISession state) throws IOException {
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			HistoryCompactor.compact(history, 0, -1, h->h==Role.USER?"":(this.charaName+"："), s->{
				state.setLastSummary(makeSummaryrequest(state,s.toString()));
				state.setDialogRows((int) (history.getContextLimit()-5));
			});
		}
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {

		// 工具由会话持有的 ToolCallManager 提供（AIRequest.builder(state) 已取用）
		Builder builder=AIRequest.builder(state).taskType(TaskType.STORY)
			.strength(ReasoningStrength.WEAK).multimodal(MultimodalType.TEXT_ONLY);
		builder.addHistoryItem(Role.SYSTEM, system);

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			HistoryCompactor.compact(history, 100000, 20000, h->h==Role.USER?"":(this.charaName+"："), s->{
				state.setLastSummary(makeSummaryrequest(state,s.toString()));
				state.setDialogRows((int) (history.getContextLimit()-5));
			});
			String lastSummary=state.getLastSummary();
			if(lastSummary!=null) {
				builder.addHistoryItem(Role.SYSTEM, lastSummary);
			}
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				builder.addHistoryItem(hi);
			}
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		//头几次用思维链版本构建格式
		
		return builder.temperature(1.3f).maxTokens(384000).build();

	}
	public String makeSummaryrequest(AISession state,String summary) throws IOException {
		Builder builder=AIRequest.builder(state).taskType(TaskType.STORY).strength(ReasoningStrength.STRONG);
		builder.addHistoryItem(Role.SYSTEM, this.summary);
			StringBuilder sumerize=new StringBuilder();

			String lastSummary=state.getLastSummary();
			if(lastSummary!=null) {
				sumerize.append("=== 前情提要 ===\\n");
				sumerize.append(lastSummary);
			}
			sumerize.append("=== 对话块 ===\n");
			sumerize.append(summary.trim());
			// if (status != null&&!status.isEmpty())
			builder.addHistoryItem(Role.USER, sumerize.toString());


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		builder.maxTokens(8192).temperature(1.3f).build();
		AIOutput resp=LLMConnector.call(builder.build());
		resp.addUsageListener(state::addUsage);
		printReasonerContent(resp);
		//System.out.println(resp.choices.get(0).message.reasoning_content);
		return resp.getContentText();
		

	}
	@Override
	public String getRoleName(AISession state, Role role) {
		return role==Role.ASSISTANT?charaName:super.getRoleName(state, role);
	}

	@Override
	public String getMemory(AISession state) {
		return state.getLastSummary();
	}
	
	String summary;
	public AIGroupApplication(File base,File path,String name,JsonObject meta) throws IOException {
		super();
		this.name=name;

		system = 
			
			FileUtil.readString(new File(path, "role.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "rules.txt")).replace("\r", "");
		summary = FileUtil.readString(new File(path, "summary.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "");
		this.charaName=meta.get("charaName").getAsString();
		// AI response, always valid
		handlers.add((state, ret) -> {
			if(!ret.isEmpty()) {
				try (HistoryWriteContext write = state.beginWrite(Role.USER)) {
					write.append(ret, true);
				}
			}
			try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
				sendAndProcessResultStreamed(state, write, constructAIrequest(state));
			}
			state.addDialogRow();

			return null;
		});

	}
}
