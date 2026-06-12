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
import java.util.Iterator;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HistoryCompacter;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;

public class AICustomMain extends AIApplication {
	HistoryCompacter compactor;
	String defaultText;
	public AICustomMain(File basePath,JsonObject meta) throws IOException {
		super();
		compactor=new HistoryCompacter(FileUtil.readString(new File(basePath, "summary.txt")).replace("\r", ""), "1");
		handlers.add((state, ret) -> {
			if (state.getStage() != ApplicationStage.STARTED) {
				state.setStage(ApplicationStage.STARTED);
			}
			return ret;
		});
		// check interface
		// AI response, always valid
		handlers.add((state, ret) -> {
			ApplicationState airet;
			try {
				state.add(Role.USER, ret, true);
				airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			}finally{
				if(state.getLast().getRole()==Role.USER) {
					HistoryItem hi=state.removeLast();
					state.refillChatBox(hi.getContextContent());
				}
			}
			state.setLastState(airet);
			state.addDialogRow();

			return null;
		});
	}

	public String constructSystem(AISession state) {
		return "【核心规则】\n"+state.getExtra().get("rules")+"\n【游戏设定】\n"+ state.getExtra().get("charaset");
	}
	@Override
	public void prepareScene(AISession state) {
		super.prepareScene(state);
	}

	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
		return precessResponse(resp, state);
		
	}

	public class MessageAndRole {
		String role;
		CharSequence message;

		public MessageAndRole(String role, CharSequence charSequence) {
			super();
			this.role = role;
			this.message = charSequence;
		}

	}

	public AIRequest constructAIrequest(AISession state) throws IOException {
		Builder b = AIRequest.builder(state).taskType(TaskType.STORY).streamed().temperature(1.3f).maxTokens(8192);
		b.addHistoryItem(Role.SYSTEM,constructSystem(state));

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {

			int num=60000;
			try {
				num=Integer.parseInt(state.getExtra().get("limit"))+10000;
			}catch(Throwable t) {
				
			}
			HistoryCompactor.compact(history, num, 10000, h->h==Role.USER?"【"+getRoleName(state,h)+"】：":"", s->{
				compactor.compactHistory(state, state.getHistoryState(), s,state.getExtra().get("charaset"),state::addUsage);
				String summary=compactor.constructHistory(state.getHistoryState());
				state.setLastSummary(summary);
				state.setDialogRows((int) (history.getContextLimit()-5));
			});
			String lastSummary=state.getLastSummary();
			if(lastSummary!=null) {
				b.addHistoryItem(Role.SYSTEM, lastSummary);
			}
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				b.addHistoryItem(hi);
			}
				
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.build();

	}

	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getDisplayContent())
				.append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AISession state) {
		state.setStage(ApplicationStage.STARTED);

	}
	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;

		ApplicationState oldstate = new ApplicationState(state.getState());
		boolean nstateModified = false;
		handleReasonerContent(resp,state);
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		while (true) {
			last=reader.readLine();
			if(last==null) {
				//System.out.println("\nEOF Read");
				break;
			}
			if (isWaiting && last.isEmpty()) {
				//System.out.println("\nWaiting");
				continue;
			}

			if (isWaiting) {
				//System.out.println("\n=================Content===============");
				isWaiting = false;
			}
			state.appendLine(Role.ASSISTANT,last, true);
			int codePoint=0;
			while((codePoint=reader.read())!=-1) {
				char[] ch=Character.toChars(codePoint);
				state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
				
			}

		}

		return nstateModified ? oldstate : null;
	}
	public String constructSystem(ApplicationState state) {
		return "";

	}

	@Override
	public String getRoleName(AISession state,Role role) {
		switch(role) {
		case USER:return "你" ;
		case ASSISTANT:return "系统";
		default:return role.getName();
		}
	}
	@Override
	public String getName() {
		return "自定义智能体";
	}
	@Override
	public String getMemory(AISession state) {
		return state.getLastSummary();
	}
	@Override
	public String getBrief(AISession state) {
		if(state.getExtra().isEmpty())
			return null;
		return state.getExtra().get("brief");
	}
}
