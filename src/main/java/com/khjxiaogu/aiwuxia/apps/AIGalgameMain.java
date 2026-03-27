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
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryCompacter;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.state.status.AttributeSet;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIGalgameMain extends AIApplication {
	String charaname;
	HistoryCompacter compactor;
	String initSelection;
	String defaultBg;
	public AIGalgameMain(File basePath,String charaname,JsonObject meta) throws IOException {
		super();
		this.charaname=charaname;
		String charaset= FileUtil.readString(new File(basePath, "charaset.txt")).replace("\r", "");
		system = FileUtil.readString(new File(basePath, "prompt.txt")).replace("\r", "")+"\n=== 人物设定 ===\n"+charaset;
		compactor=new HistoryCompacter(FileUtil.readString(new File(basePath, "summary.txt")).replace("\r", ""), charaset, "1");
		initSelection =FileUtil.readString(new File(basePath, "init.txt")).replace("\r", "");
		if(meta.has("background"))
			defaultBg=meta.get("background").getAsString();
		// naming
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.NAMING) {
				this.sendNamingPrompt(state);
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
					state.refillChatBox(hi.getDisplayContent().toString());
				}
			}
			state.getLast().setLastState(airet);
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
		if(state.getExtra().containsKey("lastSummary")&&!state.getExtra().containsKey("永久记忆")) {
			state.onGenerateStart();
			state.postMessage(-1, Role.APPLICATION,"系统正在修复历史记录中，请稍候。");
			try {
				runFullCompact(state);
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
			}
			state.onGenComplete();
		}
	}


	@Override
	public void runFullCompact(AISession state) throws ModelRouteException, IOException {
		Iterator<HistoryItem> it=state.getHistory().iterator();
		int len=0;
		StringBuilder summery=new StringBuilder();
		compactor.clearHistoryState(state.getExtra());
		while(it.hasNext()) {
			HistoryItem hi=it.next();
			len+=hi.getContextContent().length();
			if(hi.getRole()!=Role.SYSTEM) {
				if(hi.getRole()==Role.USER)
					summery.append("【"+getRoleName(state,hi.getRole())+"】").append("：");
				summery.append(hi.getDisplayContent()).append("\n");
			}
			if(len>80000) {
				len=0;
				String str=summery.toString();
				summery=new StringBuilder();
				compactor.compactHistory(state.getExtra(), str,state::addUsage);
			}
		}
		state.getExtra().put("lastSummary", compactor.constructHistory(state.getExtra()));
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system+constructNameState(state.getExtra().get("name"))).end();

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			int len=0;
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				len+=hi.getContextContent().length();
				
			}
			
			if(len>=90000) {//more than 100000 text:about 60k context,remove until 20000
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				it=history.validContextIterator();
				int removedSpeech=0;
				while(it.hasNext()) {
					HistoryItem hi=it.next();
					
					len-=hi.getContextContent().length();
					
					if(hi.getRole()!=Role.SYSTEM) {
						if(hi.getRole()==Role.USER)
							summery.append("【"+getRoleName(state,hi.getRole())+"】").append("：");
						summery.append(hi.getDisplayContent()).append("\n");
					}
					his.add(hi);
					
					if(hi.getRole()==Role.ASSISTANT) {
						removedSpeech++;
						if(len<=10000) {
							break;
						}
					}
					
					
				}
				compactor.compactHistory(state.getExtra(), summery.toString(),state::addUsage);
				state.getExtra().put("lastSummary", compactor.constructHistory(state.getExtra()));
				his.forEach(t->t.setValidContext(false));
				state.minDialogRows(removedSpeech);//generally half speech is ai
			}
			if(state.getExtra().containsKey("lastSummary")) {
				b.object().add("role", "system").add("content", state.getExtra().get("lastSummary")).end();
			}
			it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
					
				b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getContextContent().toString().trim()).end();
				
			}
				
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return AIRequest.builder().taskType(TaskType.STORY).streamed().build(b.end().add("temperature", 1.3).add("max_tokens", 8192).end());

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
					state.getExtra().put("name", ret);
					state.setStage(ApplicationStage.STARTED);
					this.handleSpeech(state, initSelection);
				}
			}
		});
	}
	public void provideInitial(AISession state) {
		state.setStage(ApplicationStage.NAMING);
		this.sendNamingPrompt(state);

	}
	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;

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
			if (status == 0) {//处理主要剧情
				if (last.startsWith("==操作==")) {
					status = 2;
					state.appendContextLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "选择你的操作（数字或行动）：", false);
				} else {
					if(last.startsWith("==情景剧本=="))
						state.appendContextLine(Role.ASSISTANT, last);
					else
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
				state.appendLine(Role.ASSISTANT, last, true);
			}

		}

		return nstateModified ? oldstate : null;
	}
	private String buildAttrStr(AISession state) {
		if (state == null || state.getState().intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("");
		for (AttributeSet intf : state.getState().intfs.values())
			sb.append(intf.toString());
		return sb.toString();
	}
	public String constructSystem(ApplicationState state) {
		return "";

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
		if(state.getExtra().isEmpty())
			return null;
		return state.getExtra().get("name");
	}
}
