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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator;
import com.khjxiaogu.aiwuxia.subagent.HistoryCompacter;
import com.khjxiaogu.aiwuxia.utils.HistoryCompactor;
import com.khjxiaogu.aiwuxia.utils.SequentialStateExecutor;
import com.khjxiaogu.aiwuxia.voice.VoiceGenerationResult;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VoiceTagger;

public class AICharaTalkMain extends AIApplication {
	String charaname;
	String prelogue="";
	SceneSelector back;
	SceneSelector character;
	Map<String,String> replacements;
	File basePath;
	String volcappid;
	String localChara;
	AttributeValidator validator;
	HistoryCompacter compactor;
	String charaset;
	String system;
	VoiceTagger vtg;
	@SuppressWarnings("unchecked")
	public AICharaTalkMain(File basePath,File modelFolder,String charaname,JsonObject meta) {
		super();
		
		this.charaname=charaname;
		try {
			vtg=new VoiceTagger(basePath);
			this.basePath=basePath;
			File model=modelFolder;
			
			String role = readFile(new File(model, "role.txt"));
			charaset=readFile(new File(model, "charaset.txt"));
			String rules=readFile(new File(model, "rules.txt"));
			system=role + "\n\n=== 角色设定 ===\n" + charaset +"\n" + rules;
			compactor=new HistoryCompacter(readFile(new File(model, "summary.txt")), "1");
			//summary = readFile(new File(model, "summary.txt")) + "\n\n=== 角色设定 ===\n" + charaset;
			prelogue = readFile(new File(model, "prelogue.txt"));
			if(meta.has("volcappid"))
				volcappid = meta.get("volcappid").getAsString();
			if(meta.has("localChara")) {
				localChara = meta.get("localChara").getAsString();
			}
			File validfile=new File(model, "validator.json");
			if(validfile.exists())
				validator=AttributeValidator.fromJson(readFile(validfile));
			File adrp=new File(model, "replacements.json");
			if(adrp.exists())
				replacements = GsonHelper.getStorageGson().fromJson(readFile(adrp), Map.class);
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
		
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.setLastState(airet);
			state.addDialogRow();

			return null;
		});
	}


	@Override
	public String getRoleName(AISession state,Role role) {
		switch(role) {
		case USER:String brief=getBrief(state);return brief==null?"我":brief ;
		case ASSISTANT:return charaname;
		default:return role.getName();
		}
	}


	public void provideNames(AISession state) {
		if (state.getStage() == ApplicationStage.NAMING) {
			state.removeLast();
			// state.removeLast();
		} 


	}


	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		//System.out.println(AIApplication.ppgs.toJson(req));
		AIOutput resp=null;
		int i=0;
		while(i<8) {//最多尝试5次，否则认为是提示词问题
			try {
				resp = LLMConnector.call(req);
				resp.addUsageListener(state::addUsage);
				return precessResponse(resp, state);
			}catch(RegenerateNeededException ex) {
				resp.interrupt();
				state.getState().set(ex.oldState);
				i++;
				if(i==4)
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						Thread.currentThread().interrupt();
						break;
					}
			}
		}
		if(state.getLast().getRole()==Role.USER) {
			HistoryItem hi=state.removeLast();
			state.refillChatBox(hi.getContextContent());
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
		Builder b=AIRequest.builder(state).taskType(TaskType.STORY).strength(ReasoningStrength.WEAK).temperature(1.3f).maxTokens(1000);
		b.addHistoryItem(Role.SYSTEM, system+constructNameState(state.getUserName()));

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			HistoryCompactor.compact(history, 150000, 20000, t->getRoleName(state,t)+"：", s->{
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
		b.prefix("==对话==");
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
					state.add(Role.SYSTEM, "已输入姓名为"+ret+"，可以开始对话了！\n"+prelogue, false);
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
		
		ApplicationState oldstate = new ApplicationState(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		StringBuilder sendContent=new StringBuilder();
		StringBuilder content=new StringBuilder();
		String oldchara=state.getCharacter();
		String oldbg=state.getBackground();
		String audioId=null;

		SequentialStateExecutor status=new SequentialStateExecutor(null,null,null,()->{

			if(!state.getState().perks.isEmpty()){
				sendContent.append("==场景==\n");
				for(Entry<String, String> i:state.getState().perks.entrySet())
					sendContent.append(i.getKey()).append("=").append(i.getValue()).append("\n");
			}
		});
		CompletableFuture<Boolean> cf=null;
		handleReasonerContent(resp,state);
		if(back!=null)
		oldbg=back.getSceneData(state.getState().perks);
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
			
			if (status.isValue(0)) {//处理主要剧情
				if(last.startsWith("==对话==")) {
					status.setValue(1);
				}else{//对话部分错误，督促AI重新生成一份
					System.out.println(last);
					logger.info("retry because header error");
					throw new RegenerateNeededException(oldstate);
				}

			}else if(status.isValue(1)) {//处理演出
				if (last.startsWith("==场景==")) {
					status.setValue(3);
					if(state.isAudioSession()&&audioId==null) {
						audioId=UUID.randomUUID().toString();
						cf=this.generateVoice(state, content.toString(), audioId);
					}
					continue;
				} else {
					content.append(last).append("\n");
				}
				
			} else if (status.isValue(3)) {
				
					
				if(last.contains("=")) {
					String[] lasts=last.split("=");
					if(lasts.length==2) {
						String key=lasts[0].trim();
						String value=lasts[1].trim();
						if(validator!=null&&!validator.validate(key, value))
							continue;
						state.getState().perks.put(key,value);
					}
					continue;
				}
				continue;
			}
			sendContent.append(last).append("\n");
		}
		if(status.isValue(0)) {//truncated
			logger.info("regenerate as truncated");
			throw new RegenerateNeededException(oldstate);
		}
		status.setValue(4);
		if(state.isAudioSession()&&audioId==null) {
			audioId=UUID.randomUUID().toString();
			cf=this.generateVoice(state, content.toString(), audioId);
		}
		String pos=state.getState().perks.get("位置");
		String chara=null;
		if(character!=null)
			chara=character.getSceneData(state.getState().perks);
		if(chara==null)
			chara=oldchara;
		if(chara!=null) {
			switch(pos) {
			case "前":
				state.sendSceneContent("front", chara);
				state.sendSceneContent("side", "");
				break;
			case "侧":
				state.sendSceneContent("front", "");
				state.sendSceneContent("side", chara);
				break;
			default:
				state.sendSceneContent("front", "");
				state.sendSceneContent("side", "");
				break;
			}
		}else {
			state.sendSceneContent("front", "");
			state.sendSceneContent("side", "");
		}
		
		String bg=null;
		if(back!=null)
			bg=back.getSceneData(state.getState().perks);
		if(bg==null)
			bg=oldbg;
		if(bg!=null)
			state.sendSceneContent("back", bg);
		else
			state.sendSceneContent("back", "");
		state.setCharacter(chara);
		state.setBackground(bg);
		state.add(Role.ASSISTANT, content.toString().trim(), sendContent.toString().trim());
		
		if(cf!=null) {
			try {
				if(cf.get()) {
					state.setAudioId(audioId);	
					state.postAudioComplete(state.getLast().getIdentifier(), audioId);
				}
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}
		return oldstate;
	}
	@Override
	public CompletableFuture<Boolean> generateVoice(AISession state,String orgText,String audioId){
		
		//
		if(replacements!=null)
			for(Entry<String, String> ent:replacements.entrySet()) {
				orgText=orgText.replaceAll(ent.getKey(), ent.getValue());
			}
		orgText=orgText.trim();
		if(orgText.isEmpty())
			return CompletableFuture.completedFuture(false);
		final String faudioId=audioId;
		final String ftext=orgText;

		return CompletableFuture.supplyAsync
		(()->{
			try {
				logger.info("正在生成语音："+ftext);
				File aud=new File(basePath,"voice");
				aud.mkdirs();
				CompletableFuture<VoiceGenerationResult> data=VoiceModelHandler.getAudioData(state.getExtraData().voiceModel,getRoleName(state, Role.ASSISTANT),state.user, ftext, faudioId, state::addUsage);
				
				VoiceGenerationResult rslt=data.get();
				try(FileOutputStream fos=new FileOutputStream(new File(aud,faudioId+".mp3"))){
					fos.write(rslt.audioData);
				};
				return true;
			} catch (Exception e) {
				e.printStackTrace();
			}
			return false;
		});
		
	}


	public void prepareScene(AISession state) {
		String chara=state.getCharacter();
		String bg=state.getBackground();
		String pos=state.getState().perks.get("位置");
		if(chara!=null&&pos!=null
				) {
			switch(pos) {
			case "前":
				state.sendSceneContent("front", chara);
				state.sendSceneContent("side", "");
				break;
			case "侧":
				state.sendSceneContent("front", "");
				state.sendSceneContent("side", chara);
				break;
			default:
				state.sendSceneContent("front", "");
				state.sendSceneContent("side", "");
				break;
			}
		}else {
			state.sendSceneContent("front", "");
			state.sendSceneContent("side", "");
		}
		if(bg!=null)
			state.sendSceneContent("back", bg);
		else
			state.sendSceneContent("back", "");
	};
	public String constructSystem(ApplicationState state) {
		if (state == null || state.perks.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("==场景==\n");
		for (Entry<String, String> intf : state.perks.entrySet())
			sb.append(intf.getKey()).append("=").append(intf.getValue()).append("\n");
		//sb.append("【叙事轨迹】\n");
		/*for (Entry<String, String> p : state.getState().perks.entrySet()) {
			sb.append(p.getKey()).append(". ").append(p.getValue()).append("\n");
		}*/

		return sb.toString();

	}

	@Override
	public String getName() {
		return charaname;
	}
	@Override
	public String getMemory(AISession state) {
		return state.getLastSummary();
	}
	@Override
	public String getBrief(AISession state) {
		return state.getUserName();
	}
}
