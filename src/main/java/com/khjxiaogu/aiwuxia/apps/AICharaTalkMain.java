package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
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
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceModel;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VolcanoVoiceApi;

public class AICharaTalkMain extends AIApplication {
	String charaname;
	String summary;
	String prelogue="";
	SceneSelector back;
	SceneSelector character;
	Map<String,String> replacements;
	File basePath;
	String volcappid;
	String localChara;
	AttributeValidator validator;
	Map<String,String> emote2emote;

	public AICharaTalkMain(File basePath,File modelFolder,String charaname,JsonObject meta) {
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
			if(meta.has("volcappid"))
				volcappid = meta.get("volcappid").getAsString();
			if(meta.has("localChara")) {
				localChara = meta.get("localChara").getAsString();
				emote2emote = gs.fromJson(meta.get("localEmote"), Map.class);
			}
			File validfile=new File(model, "validator.json");
			if(validfile.exists())
				validator=AttributeValidator.fromJson(readFile(validfile));
			File adrp=new File(model, "replacements.json");
			if(adrp.exists())
				replacements = gs.fromJson(readFile(adrp), Map.class);
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
				if(ret.length()>6) {
					state.add(Role.SYSTEM, "名称不得多于6字符", false);
				}else {
					
					/*if(state.extraData.containsKey("name")) {
						state.extraData.put("nick", ret);
						
						return "开始游戏";
					}*/
					state.getExtra().put("name", ret);
					state.add(Role.USER, ret, false);
					state.setStage(ApplicationStage.STARTED);
					state.add(Role.SYSTEM, "已输入姓名为"+ret+"，可以开始对话了！\n"+prelogue, false);
					return null;
				}
				return null;
			}
			return ret;
		});
		
		// check interface
		handlers.add(revertAndRegen);
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.STARTED) {
				if ("重读".equals(ret)) {
					HistoryItem last=state.getLast();
					if(last.getRole()==Role.ASSISTANT) {
						if(last.getAudioId()==null) {
							String audioId=UUID.randomUUID().toString();
							CompletableFuture<Boolean> cf=this.generateVoice(state, last.getDisplayContent().toString(), audioId);
							if(cf.get()) {
								last.setAudioId(audioId);
								state.postAudioComplete(last.getIdentifier(),audioId);
							}
						}
					}
					return null;
				}
			}
			return ret;
		});
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
		if(state.getLast().getRole()==Role.USER)
			state.removeLast();
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
			if(len>=60000) {//more than 100000 text:about 60k context,remove until 10000
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				it=history.validContextIterator();
				int removedSpeech=0;
				while(it.hasNext()) {//calculate total dialog rows
					HistoryItem hi=it.next();
					len-=hi.getContextContent().length();
					
					if(hi.getRole()!=Role.SYSTEM) {
						summery.append(getRoleName(state,hi.getRole())).append("：").append(hi.getContextContent()).append("\n");
					}
					his.add(hi);
					if(hi.getRole()==Role.ASSISTANT) {
						removedSpeech++;
						if(len<=10000) {
							break;
						}
					}
					
				}
				state.getExtra().put("lastSummary", makeSummaryrequest(state,summery.toString()));
				his.forEach(t->t.setValidContext(false));
				state.minDialogRows(removedSpeech);
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
		
		return AIRequest.builder().taskType(TaskType.STORY).strength(ReasoningStrength.WEAK).build(b.end().add("temperature", 1.3).add("max_tokens", 1000).end());

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
			//System.out.println(resp.choices.get(0).message.reasoning_content);

		

	}
	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getDisplayContent())
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
		StringBuilder sendContent=new StringBuilder();
		StringBuilder content=new StringBuilder();
		String oldchara=state.getExtra().get("chara");
		String oldbg=state.getExtra().get("back");
		String audioId=null;
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
			
			if (status == 0) {//处理主要剧情
				if(last.startsWith("==对话==")) {
					status=1;
				}else{//对话部分错误，督促AI重新生成一份
					logger.info("retry because header error");
					throw new RegenerateNeededException(oldstate);
				}

			}else if(status==1) {//处理演出
				if (last.startsWith("==场景==")) {
					status = 3;
					if(state.isAudioSession()&&audioId==null) {
						audioId=UUID.randomUUID().toString();
						cf=this.generateVoice(state, content.toString(), audioId);
					}
					continue;
				} else {
					content.append(last).append("\n");
				}
				
			} else if (status == 3) {
				
					
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
		if(status==0) {//truncated
			logger.info("regenerate as truncated");
			throw new RegenerateNeededException(oldstate);
		}
		if(status!=3)
			if(state.isAudioSession()&&audioId==null) {
				audioId=UUID.randomUUID().toString();
				cf=this.generateVoice(state, content.toString(), audioId);
			}
		if(!state.getState().perks.isEmpty()){
			sendContent.append("==场景==\n");
			for(Entry<String, String> i:state.getState().perks.entrySet())
				sendContent.append(i.getKey()).append("=").append(i.getValue()).append("\n");
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
		state.getExtra().put("chara",chara);
		state.getExtra().put("back",bg);
		state.add(Role.ASSISTANT, content.toString().trim(), sendContent.toString().trim());
		
		if(cf!=null) {
			try {
				if(cf.get()) {
					state.getLast().setAudioId(audioId);	
					state.postAudioComplete(state.getLast().getIdentifier(), audioId);
				}
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}
		return oldstate;
	}
	public CompletableFuture<Boolean> generateVoice(AISession state,String orgText,String audioId){
		
		//
		if(replacements!=null)
			for(Entry<String, String> ent:replacements.entrySet()) {
				orgText=orgText.replaceAll(ent.getKey(), ent.getValue());
			}
		final String faudioId=audioId;
		final String ftext=orgText;
		if(localChara!=null&&LocalVoiceModel.hasOnlineService()) {
			return CompletableFuture.supplyAsync
					(()->{
						try {
							System.out.println("trying local model");
							CompletableFuture<byte[]> dataFuture=LocalVoiceModel.requireAudio(localChara, emote2emote.get(state.getState().perks.get("表情")), faudioId, ftext);
							byte[] data=dataFuture.get();
							if(data!=null) {
								File aud=new File(basePath,"voice");
								aud.mkdirs();
								try(FileOutputStream fos=new FileOutputStream(new File(aud,faudioId+".mp3"))){
									fos.write(data);
								};
								return true;
							}
						} catch (IOException | InterruptedException | ExecutionException e) {
							e.printStackTrace();
						}
						return false;
					});
			
		}
		if(volcappid!=null&&state.getData().isAudioSession)
			return CompletableFuture.supplyAsync
			(()->{
				try {
					state.appendVoiceToken(ftext.length());
					byte[] data=VoiceModelHandler.getAudioData(volcappid,state.user, ftext, faudioId);
					File aud=new File(basePath,"voice");
					aud.mkdirs();
					try(FileOutputStream fos=new FileOutputStream(new File(aud,faudioId+".mp3"))){
						fos.write(data);
					};
					return true;
				} catch (IOException e) {
					e.printStackTrace();
				}
				return false;
			});
		return CompletableFuture.completedFuture(false);
	}
	
	@Override
	public boolean isLocalVoiceSupported() {
		return localChara!=null;
	}


	public void prepareScene(AISession state) {
		String chara=state.getExtra().get("chara");
		String bg=state.getExtra().get("back");
		String pos=state.getState().perks.get("位置");
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
	public String getBrief(AISession state) {
		if(state.getExtra().isEmpty())
			return null;
		return state.getExtra().get("name");
	}
}
