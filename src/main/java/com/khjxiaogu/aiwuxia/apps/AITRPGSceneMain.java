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
import java.util.UUID;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.RegenerateNeededException;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.state.status.AttributeSet;
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AITRPGSceneMain extends AIApplication {
	String charaname;
	String summary;
	String prelogue="";
	SceneSelector back;
	SceneSelector character;
	AttributeValidator validator;
	File basePath;


	public AITRPGSceneMain(File basePath,File modelFolder,String charaname,JsonObject meta) {
		super();
		this.charaname=charaname;
		
		try {
			this.basePath=basePath;
			File model=modelFolder;
			
			String role = readFile(new File(model, "role.txt"));
			String charaset=readFile(new File(model, "charaset.txt"));
			String rules=readFile(new File(model, "rules.txt"));
			system=role + "\n\n=== 角色设定 ===\n" + charaset +"\n" + rules;
			summary = readFile(new File(model, "summary.txt")) + "\n\n=== 角色设定 ===\n" + charaset;
			prelogue = readFile(new File(model, "prelogue.txt"));
			File validfile=new File(model, "validator.json");
			if(validfile.exists())
				validator=AttributeValidator.fromJson(readFile(validfile));
			if(new File(model,"chara.json").exists()) {
				character=gs.fromJson(readFile(new File(model,"chara.json")), SceneSelector.class);
				
			}
			if(new File(model,"back.json").exists()) {
				back=gs.fromJson(readFile(new File(model,"back.json")), SceneSelector.class);
				
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
			state.add(Role.USER, ret, true);
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.getLast().setLastState(airet);
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
					state.getExtra().put("name", ret);
					state.setStage(ApplicationStage.STARTED);
					state.add(Role.SYSTEM, "已输入姓名为"+ret+"，可以开始对话了！\n"+prelogue, false);
				}
			}
		});
	}
	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		//System.out.println(AIApplication.ppgs.toJson(req));
		AIOutput resp=null;
		int i=0;
		while(i<5) {//最多尝试5次，否则认为是提示词问题
			try {
				
				resp = LLMConnector.call(req);
				resp.addUsageListener(state::addUsage);
				return precessResponse(resp, state);
			}catch(RegenerateNeededException ex) {
				resp.interrupt();
				state.getState().set(ex.oldState);
				
			}
			i++;
		}
		if(state.getLast().getRole()==Role.USER) {
			HistoryItem hi=state.removeLast();
			state.refillChatBox(hi.getDisplayContent().toString());
		}
		state.minDialogRow();
		return state.getLast().getLastState();
		
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
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system+constructNameState(state.getExtra().get("name"))).end();

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		int i = 0;
		if (history != null && !history.isEmpty()) {
			
			int len=0;
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				if(hi.getRole()==Role.ASSISTANT) {
					i++;
				}
				len+=hi.getContextContent().length();
				
			}
			if(len>=60000) {//more than 100000 text:about 60k context,remove until 20000
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				it=history.validContextIterator();
				int removedSpeech=0;
				while(it.hasNext()) {
					HistoryItem hi=it.next();
					if(hi.isValidContext()) {
						len-=hi.getContextContent().length();
						
						if(hi.getRole()!=Role.SYSTEM) {
							if(hi.getRole()==Role.USER)
								summery.append("用户").append("：");
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
					
				}
				state.getExtra().put("lastSummary", makeSummaryrequest(state,summery.toString()));
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
		//头几次用思维链版本构建格式
		Builder builder=AIRequest.builder().taskType(TaskType.CODE).strength(ReasoningStrength.WEAK).streamed();
		return builder.build(b.end().add("temperature", 1.3).add("max_tokens", 4000).end());

	}
	public AIRequest constructSummaryrequest(AISession state,String summary) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
				.add("role", "system").add("content", this.summary).end();
			StringBuilder sumerize=new StringBuilder();
			if(state.getExtra().containsKey("lastSummary")) {
				sumerize.append("=== 前情提要 ===\\n");
				sumerize.append( state.getExtra().get("lastSummary"));
			}
			sumerize.append("=== 对话块 ===\n");
			sumerize.append(summary.trim());
			// if (status != null&&!status.isEmpty())
			b.object().add("role",Role.USER.getRoleName()).add("content", sumerize.toString()).end();


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return AIRequest.builder().taskType(TaskType.STORY).strength(ReasoningStrength.STRONG).build(b.end().add("temperature", 1.3).add("max_tokens", 8192).end());

	}
	public String makeSummaryrequest(AISession state,String summary) throws IOException {
		
			AIOutput resp=LLMConnector.call(constructSummaryrequest(state,summary));
			resp.addUsageListener(state::addUsage);
			printReasonerContent(resp);
			return FileUtil.readAll(resp.getContent());

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
	public String constructSummaryBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		Iterator<HistoryItem> it=state.getHistory().validContextIterator();
		if(state.getExtra().containsKey("lastSummary")) {
			sb.append( state.getExtra().get("lastSummary"));
		}
		while(it.hasNext()) {
			HistoryItem hs=it.next();
				sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getContextContent()).append("\n");
		}

		return sb.toString();

	}


	public void provideInitial(AISession state) {
		state.add(Role.SYSTEM, "请输入姓名", false);
		state.setStage(ApplicationStage.NAMING);
	}

	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;
		
		ApplicationState oldstate = new ApplicationState(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		String oldchara=state.getExtra().get("chara");
		String oldbg=state.getExtra().get("back");
		handleReasonerContent(resp,state);
		AttributeSet scene=state.getState().getOrCreateInterface("场景");
		AttributeSet characs=state.getState().getOrCreateInterface("角色");
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
			if (status == 0) {//开始
				/*if(last.startsWith("==对话==")) {
					status=1;
				}else*/
				
				if (last.startsWith("==场景==")) {
					
				}else if(!scene.isEmpty()){
					state.appendContextLine(Role.ASSISTANT, "==场景==");
					for(Entry<String, String> i:scene)
						state.appendContextLine(Role.ASSISTANT, i.getKey()+"="+i.getValue());
				}else
					throw new RegenerateNeededException(oldstate);//对话部分错误，督促AI重新生成一份
				status=1;

			}else if(status==1) {//处理场景
				if (last.startsWith("==对话==")) {
					if(!scene.isEmpty()){
						state.appendContextLine(Role.ASSISTANT, "==场景==");
						for(Entry<String, String> i:scene)
							state.appendContextLine(Role.ASSISTANT, i.getKey()+"="+i.getValue());
					}
					if(back!=null)
						bg=back.getSceneData(scene.getAsMap());
					if(bg==null)
						bg=oldbg;
					if(bg!=null)
						state.sendSceneContent("back", bg);
					else
						state.sendSceneContent("back", "");
					status = 2;
					state.appendContextLine(Role.ASSISTANT, last);
					int codePoint=0,codePoint2=0;
					while((codePoint=reader.read())!=-1) {
						if(codePoint!='=') {
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
						}else if((codePoint2=reader.read())=='=') {
							String read=reader.readLine();
							if(read.startsWith("角色==")) {
								state.appendContextLine(Role.ASSISTANT, "==角色==");
								status=3;
								break;
							}
							state.appendCh(Role.ASSISTANT, "==", true);
							state.appendCh(Role.ASSISTANT, read, true);
							
						}else {
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
							if(codePoint2!=-1) {
								ch=Character.toChars(codePoint2);
								state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
							}
						}
					}
					
					continue;
				} else {
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
				}
			} else if (status == 2) {	
				if (last.startsWith("==角色==")) {
					status=3;
					continue;
				}else
					state.appendLine(Role.ASSISTANT, last, false);
			} else if (status == 3) {	
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
			state.appendContextLine(Role.ASSISTANT, last);

		}
		if(status==1) {//没有对话和角色部分，重新生成
			throw new RegenerateNeededException(oldstate);
		}
		state.appendContextLine(Role.ASSISTANT, "==角色==");
		for(Entry<String, String> i:characs)
			state.appendContextLine(Role.ASSISTANT, i.getKey()+"="+i.getValue());
		
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
			state.getExtra().put("chara",chara);
		if(bg!=null)
			state.getExtra().put("back",bg);
		
		return oldstate;
	}

	public void prepareScene(AISession state) {
		String chara=state.getExtra().get("chara");
		String bg=state.getExtra().get("back");
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
