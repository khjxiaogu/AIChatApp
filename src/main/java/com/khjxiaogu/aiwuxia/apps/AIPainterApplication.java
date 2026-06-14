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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;
import com.khjxiaogu.aiwuxia.utils.MessageReader;

public class AIPainterApplication extends AIApplication {
	String name;
	String charaName;
	String system;
	List<ToolData> tools=new ArrayList<>();
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
	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
		return precessResponse(resp, state);
		
	}
	public void handleReasonerContent(AIOutput output,AISession state) throws IOException {
		MessageReader br=output.getReasoner();
		state.resetReasoner();
		while(!br.isEnded()) {
			MessageContent current=br.read();
			if(current==null)
				break;
			if(current instanceof ToolCallContent) {
				state.onToolcall((ToolCallContent) current);
			}
			state.appendReasoner(current);
			
		}
	}
	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		
		ApplicationState oldstate = new ApplicationState(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		StringBuilder sendContent=new StringBuilder();
		handleReasonerContent(resp,state);
		while (true) {
			last=reader.readLine();
			if(last==null) {
				break;
			}
			if (isWaiting && last.isEmpty()) {
				continue;
			}
			if (isWaiting) {
				isWaiting = false;
			}
			sendContent.append(last).append("\n");
		}
		String msg=sendContent.toString().trim();
		if(msg.isEmpty()) {
			MessageContents content=state.getReasonerContent();
			if(content!=null) {
				PlainText lastText=null;
				for(MessageContent msgBody:content) {
					if(msgBody instanceof PlainText)
						lastText=(PlainText) msgBody;
				}
				String lastTextStr=lastText.toText();
				int idx=lastTextStr.lastIndexOf("response");
				if(idx!=-1) {
					msg=lastTextStr.substring(idx+8);
				}
			}
		}
		state.add(Role.ASSISTANT, msg,msg);
		
		return oldstate;
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {

		Builder builder=AIRequest.builder(state).taskType(TaskType.STORY)
			.strength(ReasoningStrength.WEAK).multimodal(MultimodalType.TEXT_ONLY)
			.addTools(tools);
		builder.addHistoryItem(Role.SYSTEM, system);

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			HistoryCompactor.compact(history, 100000, 20000, h->getRoleName(state,h)+"：", s->{
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
	public AIPainterApplication(File base,File path,String name,JsonObject meta) throws IOException {
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
			state.add(Role.USER, ret, true);
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.setLastState(airet);
			state.addDialogRow();

			return null;
		});

	}
}
