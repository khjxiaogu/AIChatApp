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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.GsonHelper;
import com.khjxiaogu.aiwuxia.state.RegenerateNeededException;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.state.status.AttributeSet;
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;
import com.khjxiaogu.aiwuxia.utils.SequentialStateExecutor;

public class AITRPGSceneMain extends AIApplication {
	String charaname;
	String summary;
	String prelogue="";
	SceneSelector back;
	SceneSelector character;
	AttributeValidator validator;
	File basePath;
	String system;


	public AITRPGSceneMain(File basePath,File modelFolder,String charaname,JsonObject meta) {
		super();
		this.charaname=charaname;
		
		try {
			this.basePath=basePath;
			File model=modelFolder;
			
			String role = readFile(new File(model, "role.txt"));
			String charaset=readFile(new File(model, "charaset.txt"));
			String rules=readFile(new File(model, "rules.txt"));
			system=role + "\n\n【游戏设定】\n" + charaset +"\n" + rules;
			summary = readFile(new File(model, "summary.txt")) + "\n\n=== 角色设定 ===\n" + charaset;
			prelogue = readFile(new File(model, "prelogue.txt"));
			File validfile=new File(model, "validator.json");
			if(validfile.exists())
				validator=AttributeValidator.fromJson(readFile(validfile));
			if(new File(model,"chara.json").exists()) {
				character=GsonHelper.getStorageGson().fromJson(readFile(new File(model,"chara.json")), SceneSelector.class);
				
			}
			if(new File(model,"back.json").exists()) {
				back=GsonHelper.getStorageGson().fromJson(readFile(new File(model,"back.json")), SceneSelector.class);
				
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
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
			try (HistoryWriteContext write = state.beginWrite(Role.USER)) {
				write.append(ret, true);
			}
			try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
				write.setLastState(sendAndProcessResultStreamed(state, write, constructAIrequest(state)));
			}
			state.addDialogRow();

			return null;
		});
	}


	@Override
	public String getRoleName(AISession state,Role role) {
		switch(role) {
		case USER:String brief=getBrief(state);return brief==null?"我":brief ;
		case ASSISTANT:return null;
		default:return role.getName();
		}
	}


	public void provideNames(AISession state) {
		if (state.getStage() == ApplicationStage.NAMING) {
			state.removeLast();
			// state.removeLast();
		} 


	}
	public void sendNamingPrompt(AISession state) {
		state.requestUserInput("name", "请输入玩家姓名，名称不得多于6字符",(ret)->{
			if (state.getStage() == ApplicationStage.NAMING) {
				if(ret.length()>6) {
					sendNamingPrompt(state);
				}else {
					state.setUserName(ret);
					state.setStage(ApplicationStage.STARTED);
					try (HistoryWriteContext write = state.beginWrite(Role.SYSTEM)) {
						write.appendDisplayOnly("已输入姓名为"+ret+"，可以开始对话了！\n"+prelogue);
					}
				}
			}
		});
	}
	public ApplicationState sendAndProcessResultStreamed(AISession state, HistoryWriteContext write, AIRequest req) throws IOException {
		//System.out.println(AIApplication.ppgs.toJson(req));
		AIOutput resp=null;
		int i=0;
		while(i<5) {//最多尝试5次，否则认为是提示词问题
			try {
				
				resp = LLMConnector.call(req);
				resp.addUsageListener(state::addUsage);
				ApplicationState oldstate = precessResponse(resp, state, write);
				write.setLastState(oldstate);
				return oldstate;
			}catch(RegenerateNeededException ex) {
				resp.interrupt();
				state.getState().set(ex.oldState);
				
			}
			i++;
		}
		// 反复重试都没能得到可用的回复：撤回本次写入（只剩思维链，尚未提交进历史）
		write.rollback();
		if(state.getLast().getRole()==Role.USER) {
			HistoryItem hi=state.removeLast();
			state.refillChatBox(hi.getContextContent());
		}
		state.minDialogRow();
		// 上一次写入已经撤回，没有需要记录的状态快照
		return null;
		
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
		return "用户是主角，姓名为"+name+"，请直接用姓名称呼主角，不要用“主角”二字指代主角。";
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {

		Builder builder=AIRequest.builder(state).taskType(TaskType.CODE).strength(ReasoningStrength.WEAK).streamed();
		builder.addHistoryItem(Role.SYSTEM,system+constructNameState(state.getUserName()));
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			HistoryCompactor.compact(history, 60000, 10000, t->t==Role.USER?"用户：":"", s->{
				state.setLastSummary(makeSummaryrequest(state,s));
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
		builder.prefix("==场景==");
		return builder.temperature(1.6f).maxTokens(8192).build();

	}
	public AIRequest constructSummaryrequest(AISession state,String summary) {
		Builder b=AIRequest.builder(state).taskType(TaskType.STORY).strength(ReasoningStrength.STRONG);
		b.addHistoryItem(Role.SYSTEM, this.summary);
		StringBuilder sumerize=new StringBuilder();
		String lastSummary=state.getLastSummary();
		if(lastSummary!=null) {
			sumerize.append("=== 前情提要 ===\\n");
			sumerize.append(lastSummary);
		}
		sumerize.append("=== 对话块 ===\n");
		sumerize.append(summary.trim());
		// if (status != null&&!status.isEmpty())
		b.addHistoryItem(Role.USER, sumerize.toString());


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.temperature(1.3f).maxTokens(16384).build();

	}
	public String makeSummaryrequest(AISession state,String summary) throws IOException {
		
			AIOutput resp=LLMConnector.call(constructSummaryrequest(state,summary));
			resp.addUsageListener(state::addUsage);
			printReasonerContent(resp);
			return FileUtil.readAll(resp.getContentReader());

	}
	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			String roleName=getRoleName(state,hs.getRole());
			if(roleName!=null)
				sb.append(roleName).append("：");
			sb.append(hs.getDisplayContent())
				.append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AISession state) {
		if(state.getStage()!=ApplicationStage.STARTED) {
			try (HistoryWriteContext write = state.beginWrite(Role.SYSTEM)) {
				write.appendDisplayOnly("请输入姓名");
			}
			state.setStage(ApplicationStage.NAMING);
		}
	}

	public ApplicationState precessResponse(AIOutput resp, AISession state, HistoryWriteContext write) throws IOException {
		boolean isWaiting = true;
		
		ApplicationState oldstate = new ApplicationState(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContentReader());
		String last;
		String oldchara=state.getCharacter();
		String oldbg=state.getBackground();
		// 思维链改为后台异步读流写入本轮记录，正文可以立刻开始处理
		CompletableFuture<Void> reasoner=handleReasonerContent(resp,state,write);
		AttributeSet scene=state.getState().getOrCreateInterface("场景");
		AttributeSet characs=state.getState().getOrCreateInterface("角色");

		SequentialStateExecutor status=new SequentialStateExecutor(null,()->{
			if(!scene.isEmpty()){
				write.appendContext("==场景==\n");
				for(Entry<String, String> i:scene)
					write.appendContext(i.getKey()+"="+i.getValue()+"\n");
			}
		},null,()->{
			write.appendContext("==角色==\n");
			for(Entry<String, String> i:characs)
				write.appendContext(i.getKey()+"="+i.getValue()+"\n");
		});
		if(back!=null)
		oldbg=back.getSceneData(scene.getAsMap());
		String bg=null;	
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
			if (status.isValue(0)) {//开始
				/*if(last.startsWith("==对话==")) {
					status=1;
				}else*/
				
				if (last.startsWith("==场景==")) {
					
				}else if(scene.isEmpty())
					throw new RegenerateNeededException(oldstate);//对话部分错误，督促AI重新生成一份
				status.setValue(1);
				continue;
			}else if(status.isValue(1)) {//处理场景
				if (last.startsWith("==对话==")) {
					if(back!=null)
						bg=back.getSceneData(scene.getAsMap());
					if(bg==null)
						bg=oldbg;
					if(bg!=null)
						state.sendSceneContent("back", bg);
					else
						state.sendSceneContent("back", "");
					status.setValue(2);
					write.appendContext(last + "\n");
					int codePoint=0,codePoint2=0;
					while((codePoint=reader.read())!=-1) {
						if(codePoint!='=') {
							char[] ch=Character.toChars(codePoint);
							write.append(String.valueOf(ch), true);
						}else if((codePoint2=reader.read())=='=') {
							String read=reader.readLine();
							if(read.startsWith("角色==")) {
								write.appendContext("==角色==\n");
								status.setValue(3);
								break;
							}
							write.append("==", true);
							write.append(read, true);
							
						}else {
							char[] ch=Character.toChars(codePoint);
							write.append(String.valueOf(ch), true);
							if(codePoint2!=-1) {
								ch=Character.toChars(codePoint2);
								write.append(String.valueOf(ch), true);
							}
						}
					}
					
					continue;
				}
				if(last.contains("=")) {
					String[] lasts=last.split("=");
					if(lasts.length==2) {
						String key=lasts[0].trim();
						String value=lasts[1].trim();
						if(validator!=null) {
							if(!validator.validate(key, value)) {
								continue;
							}
						}
						scene.put(key,value);
					}
					continue;
				}
			} else if (status.isValue(2)) {	
				if (last.startsWith("==角色==")) {
					status.setValue(3);
					continue;
				}else
					write.appendLine(last, false);
			} else if (status.getValue() == 3) {	
				if(last.contains("=")) {
					String[] lasts=last.split("=");
					if(lasts.length==2) {
						String key=lasts[0].trim();
						String value=lasts[1].trim();
						if(validator!=null) {
							if(!validator.validate(key, value)) {
								continue;
							}
						}
						characs.put(key, value);
					}
				}
				continue;
			}
			write.appendContext(last + "\n");

		}
		status.setValue(4);
		
		
		String chara=null;
		
		if(character!=null) {
			List<String> charaList=new ArrayList<>();
			List<String> sideList=new ArrayList<>();
			for(Entry<String, String> s:characs) {
				if(s.getKey().endsWith("位置")&&!"不在".equals(s.getValue())) {
					
					Map<String,String> charaData=new HashMap<>();
					String name=s.getKey().substring(0,s.getKey().length()-2);
					charaData.put("姓名", name);
					charaData.put("表情", characs.get(name+"表情"));
					
					String cchara=character.getSceneData(charaData);
					
					if(cchara!=null) {
						if("前".equals(s.getValue())) {
							charaList.add(cchara);
						}else if("侧".equals(s.getValue())) {
							sideList.add(cchara);
						}
					}
				}
			}
			StringBuilder charaBuilder=new StringBuilder();
			for(String s:charaList) {
				charaBuilder.append(s).append(",");
			}
			for(String s:sideList) {
				charaBuilder.append(s).append(",");
			}
			chara=charaBuilder.toString();
		}
		if(chara==null)
			chara=oldchara;
		if(chara!=null) {
			state.sendSceneContent("front", chara);
		}else {
			state.sendSceneContent("front", "");
		}
		if(chara!=null)
			state.setCharacter(chara);
		if(bg!=null)
			state.setBackground(bg);
		// 等待后台思维链读完，读流失败在这里按原来的方式抛出
		reasoner.join();
		
		return oldstate;
	}

	public void prepareScene(AISession state) {
		String chara=state.getCharacter();
		String bg=state.getBackground();
		if(chara!=null) {
			state.sendSceneContent("front", chara);
		}else {
			state.sendSceneContent("front", "");
		}
		if(bg!=null)
			state.sendSceneContent("back", bg);
		else
			state.sendSceneContent("back", "");
	};
	public String constructSystem(ApplicationState state) {
		if (state == null || state.intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("");
		for (AttributeSet intf : state.intfs.values())
			sb.append(intf.toString());

		return sb.toString();

	}

	@Override
	public String getMemory(AISession state) {
		return state.getLastSummary();
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
