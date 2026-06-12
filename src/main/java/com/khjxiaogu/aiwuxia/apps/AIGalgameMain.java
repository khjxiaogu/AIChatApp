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
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.subagent.HistoryCompacter;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;
import com.khjxiaogu.aiwuxia.utils.SequentialStateExecutor;

public class AIGalgameMain extends AIApplication {
	String charaname;
	HistoryCompacter compactor;
	String initSelection;
	String defaultBg;
	String charaset;
	String system;
	public AIGalgameMain(File basePath,String charaname,JsonObject meta) throws IOException {
		super();
		this.charaname=charaname;
		charaset= FileUtil.readString(new File(basePath, "charaset.txt")).replace("\r", "");
		
		system ="【核心规则】\n"+FileUtil.readString(new File(basePath, "prompt.txt")).replace("\r", "")+"\n【游戏设定】\n"+ charaset;
		compactor=new HistoryCompacter(FileUtil.readString(new File(basePath, "summary.txt")).replace("\r", ""), "1");
		initSelection =FileUtil.readString(new File(basePath, "init.txt")).replace("\r", "");
		if(meta.has("background"))
			defaultBg=meta.get("background").getAsString();
		// naming
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.NAMING) {
				this.sendNamingPrompt(state);
				state.refillChatBox(ret);
				return null;
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


	@Override
	public void prepareScene(AISession state) {
		super.prepareScene(state);
		if(defaultBg!=null)
		state.sendSceneContent("back", defaultBg);
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
	public String constructNameState(String name){
		return "主角姓名为"+name+"，你需要用该名称称呼主角。";
	}
	
	@Override
	public void onload(AISession state) {
		super.onload(state);
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {
		Builder b = AIRequest.builder(state).taskType(TaskType.STORY).streamed().temperature(1.3f).maxTokens(8192).strength(ReasoningStrength.WEAK);
		b.addHistoryItem(Role.SYSTEM, system+constructNameState(state.getUserName()));
		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			HistoryCompactor.compact(history, 90000, 10000, h->h==Role.USER?"【"+getRoleName(state,h)+"】：":"", s->{
				compactor.compactHistory(state ,state.getHistoryState(), s,charaset,state::addUsage);
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

		b.prefix("你选择：");
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

	public void sendNamingPrompt(AISession state) {
		state.requestUserInput("name", "请输入玩家姓名，名称不得多于6字符",(ret)->{
			if (state.getStage() == ApplicationStage.NAMING) {
				if(ret.length()>6) {
					sendNamingPrompt(state);
				}else {
					state.setUserName(ret);
					state.setStage(ApplicationStage.STARTED);
					this.handleSpeech(state, new MutableMessageContents(initSelection));
				}
			}
		});
	}
	public void provideInitial(AISession state) {
		if(state.getStage()!=ApplicationStage.STARTED) {
			state.setStage(ApplicationStage.NAMING);
			this.sendNamingPrompt(state);
		}

	}
	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		SequentialStateExecutor status=new SequentialStateExecutor(null,()->{

			
			state.appendLine(Role.ASSISTANT, "选择你的操作（数字或行动）：", false);
		});

		ApplicationState oldstate = new ApplicationState(state.getState());
		boolean nstateModified = false;
		handleReasonerContent(resp,state);
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		int orderIndex=1;
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
			if (status.isValue(0)) {//处理主要剧情
				if (last.startsWith("==操作==")) {
					state.appendContextLine(Role.ASSISTANT, last);
					status.setValue(2);
				} else {
					if(last.startsWith("==情景剧本==")) {
						state.appendContextLine(Role.ASSISTANT, last);
						status.setValue(1);
					}else
						state.appendLine(Role.ASSISTANT, last, true);
					int codePoint=0,codePoint2=0;
					reader.mark(16);
					while((codePoint=reader.read())!=-1) {
						if(codePoint!='=') {
							reader.mark(16);
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
						}else if((codePoint2=reader.read())=='=') {
							reader.reset();
							break;
						}else {
							reader.mark(16);
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
							if(codePoint2!=-1) {
								ch=Character.toChars(codePoint2);
								state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
							}
						}
					}
				}
			} else {
				if(status.isValue(2)) {
					if(!Character.isDigit(last.charAt(0))) {
						last=orderIndex+". "+last;
					}	
					orderIndex++;
				}
					
				state.appendLine(Role.ASSISTANT, last, true);
			}
			

		}
		status.setValue(3);

		return nstateModified ? oldstate : null;
	}
	public String constructSystem(ApplicationState state) {
		return "";

	}

	@Override
	public String getMemory(AISession state) {
		return state.getLastSummary();
	}
	@Override
	public String getRoleName(AISession state,Role role) {
		switch(role) {
		case USER:String brief=getBrief(state);return brief==null?"我":brief ;
		case ASSISTANT:return "";
		default:return role.getName();
		}
	}
	@Override
	public String getName() {
		return charaname;
	}

	@Override
	public String getBrief(AISession state) {
		return state.getUserName();
	}
}
