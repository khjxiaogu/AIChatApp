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
import com.khjxiaogu.aiwuxia.AIApplication;
import com.khjxiaogu.aiwuxia.AISession;
import com.khjxiaogu.aiwuxia.Role;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.state.AIOutput;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.Interface;
import com.khjxiaogu.aiwuxia.state.RegenerateNeededException;
import com.khjxiaogu.aiwuxia.state.StateIntf;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AITRPGSceneMain extends AIApplication {
	Pattern sxPattern = Pattern.compile("【([^】]+)】([^【]+)");
	Pattern intfPattern = Pattern.compile("【([^面]+)面板】");
	String charaname;
	String summary;
	String prelogue="";
	SceneSelector back;
	SceneSelector character;
	Map<String,String> replacements;
	File basePath;


	public AITRPGSceneMain(File basePath,String modelFolder,String charaname) {
		super();
		this.charaname=charaname;
		
		try {
			this.basePath=basePath;
			File model=new File(basePath,modelFolder);
			
			String role = readFile(new File(model, "role.txt"));
			String charaset=readFile(new File(model, "charaset.txt"));
			String rules=readFile(new File(model, "rules.txt"));
			system=role + "\n\n=== 角色设定 ===\n" + charaset +"\n" + rules;
			summary = readFile(new File(model, "summary.txt")) + "\n\n=== 角色设定 ===\n" + charaset;
			prelogue = readFile(new File(model, "prelogue.txt"));
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

			if (state.getStage() == GameStage.NAMING) {
				if(ret.length()>6) {
					state.add(Role.SYSTEM, "名称不得多于6字符", false);
				}else {
					
					/*if(state.extraData.containsKey("name")) {
						state.extraData.put("nick", ret);
						
						return "开始游戏";
					}*/
					state.getExtra().put("name", ret);
					state.add(Role.USER, ret, false);
					state.setStage(GameStage.STARTED);
					state.add(Role.SYSTEM, "已输入姓名为"+ret+"，可以开始对话了！\n"+prelogue, false);
					return null;
				}
				return null;
			}
			return ret;
		});
		
		// check interface
		handlers.add(revertAndRegen);
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			StateIntf airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.getLast().setLastState(airet);
			state.addRow();

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
		if (state.getStage() == GameStage.NAMING) {
			state.removeLast();
			// state.removeLast();
		} 


	}

	public StateIntf sendAndProcessResult(AISession state, JsonObject req) throws IOException {
		RespScheme resp = sendAIRequest(req);
		state.addUsage(resp.usage);
		return precessResponse(new AIOutput.FilledAIOutput(resp), state);
	}

	public StateIntf sendAndProcessResultStreamed(AISession state, JsonObject req) throws IOException {
		//System.out.println(AIApplication.ppgs.toJson(req));
		AIOutput resp=null;
		int i=0;
		while(i<5) {//最多尝试5次，否则认为是提示词问题
			try {
				resp = sendAIStreamedRequest(req, state::addUsage);
				return precessResponse(resp, state);
			}catch(RegenerateNeededException ex) {
				state.getState().set(ex.oldState);
				i++;
			}
		}
		if(state.getLast().getRole()==Role.USER)
			state.removeLast();
		state.minRow();
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
	public JsonObject constructAIrequest(AISession state) throws IOException {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system+constructNameState(state.getExtra().get("name"))).end();

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		int i = 0;
		if (history != null && !history.isEmpty()) {
			
			int len=0;
			Iterator<HistoryItem> it=history.sendableIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				if(hi.getRole()==Role.ASSISTANT) {
					i++;
				}
				len+=hi.getFullContent().length();
				
			}
			if(len>=100000) {//more than 100000 text:about 60k context,remove until 20000
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				it=history.sendableIterator();
				while(it.hasNext()) {
					HistoryItem hi=it.next();
					len-=hi.getFullContent().length();
					
					if(hi.getRole()!=Role.SYSTEM) {
						summery.append(getRoleName(state,hi.getRole())).append("：").append(hi.getFullContent()).append("\n");
					}
					his.add(hi);
					if(len<=20000&&hi.getRole()==Role.ASSISTANT) {
						break;
					}
					
				}
				state.getExtra().put("lastSummary", makeSummaryrequest(state,summery.toString()));
				his.forEach(t->t.setSendable(false));
				state.minRows(his.size());
			}
			if(state.getExtra().containsKey("lastSummary")) {
				b.object().add("role", "system").add("content", state.getExtra().get("lastSummary")).end();
			}
			it=history.sendableIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getFullContent().toString().trim()).end();
			}
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		//头几次用思维链版本构建格式
		return b.end().add("model",/*i<=10?*/"deepseek-reasoner"/*:"deepseek-chat"*/).add("temperature", 1.3).add("max_tokens", 4000).add("stream", false).end();

	}
	public JsonObject constructSummaryrequest(AISession state,String summary) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", this.summary).end();

		// if (status != null&&!status.isEmpty())
		b.object().add("role",Role.USER.getRoleName()).add("content", "=== 对话块 ===\n"+summary.trim()).end();


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.end().add("model", "deepseek-reasoner").add("temperature", 1.0).add("max_tokens", 8192).add("stream", false).end();

	}
	public String makeSummaryrequest(AISession state,String summary) throws IOException {
		
			RespScheme resp=super.sendAIRequest(constructSummaryrequest(state,summary));
			state.addUsage(resp.usage);
			
			//System.out.println(resp.choices.get(0).message.reasoning_content);
			return resp.choices.get(0).message.content;
		

	}
	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			String roleName=getRoleName(state,hs.getRole());
			if(roleName!=null)
				sb.append(roleName).append("：");
			sb.append(hs.getContent())
				.append("\n");
		}

		return sb.toString();

	}
	public String constructSummaryBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		Iterator<HistoryItem> it=state.getHistory().sendableIterator();
		if(state.getExtra().containsKey("lastSummary")) {
			sb.append( state.getExtra().get("lastSummary"));
		}
		while(it.hasNext()) {
			HistoryItem hs=it.next();
				sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getFullContent()).append("\n");
		}

		return sb.toString();

	}


	public void provideInitial(AISession state) {
		state.add(Role.SYSTEM, "请输入姓名", false);
		state.setStage(GameStage.NAMING);
	}

	public StateIntf precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;
		
		StateIntf oldstate = new StateIntf(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		String oldchara=state.getExtra().get("chara");
		String oldbg=state.getExtra().get("back");
		String audioId=null;
		handleReasonerContent(resp,state);
		Interface scene=state.getState().getOrCreateInterface("场景");
		Interface characs=state.getState().getOrCreateInterface("角色");
		if(back!=null)
		oldbg=back.getSceneData(scene.values);
		String bg=null;	
		boolean isContentSent=false;
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
					state.appendInvisibleLine(Role.ASSISTANT, "==场景==");
					for(Entry<String, String> i:scene)
					state.appendInvisibleLine(Role.ASSISTANT, i.getKey()+"="+i.getValue());
				}else
					throw new RegenerateNeededException(oldstate);//对话部分错误，督促AI重新生成一份
				status=1;

			}else if(status==1) {//处理场景
				if (last.startsWith("==对话==")) {
					
					if(back!=null)
						bg=back.getSceneData(scene.values);
					if(bg==null)
						bg=oldbg;
					if(bg!=null)
						state.setScene("back", bg);
					else
						state.setScene("back", "");
					status = 2;
					state.appendInvisibleLine(Role.ASSISTANT, last);
					int codePoint=0,codePoint2=0;
					while((codePoint=reader.read())!=-1) {
						if(codePoint!='=') {
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), true);
						}else if((codePoint2=reader.read())=='=') {
							String read=reader.readLine();
							if(read.startsWith("角色==")) {
								state.appendInvisibleLine(Role.ASSISTANT, "==角色==");
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
						scene.put(lasts[0].trim(), lasts[1].trim());
					}
				}
			} else if (status == 2) {	
				if (last.startsWith("==角色==")) {
					status=3;
				}else
					state.appendLine(Role.ASSISTANT, last, false);
			} else if (status == 3) {	
				if(last.contains("=")) {
					
					String[] lasts=last.split("=");
					characs.put(lasts[0].trim(), lasts[1].trim());
				}
			}
			state.appendInvisibleLine(Role.ASSISTANT, last);

		}
		if(status==1) {//没有对话和角色部分，重新生成
			throw new RegenerateNeededException(oldstate);
		}else if(status!=3){//没有生成角色部分
			state.appendInvisibleLine(Role.ASSISTANT, "==角色==");
			for(Entry<String, String> i:characs)
			state.appendInvisibleLine(Role.ASSISTANT, i.getKey()+"="+i.getValue());
		}
		String chara=null;
		
		if(character!=null) {
			List<String> charaList=new ArrayList<>();
			for(Entry<String, String> s:characs.values.entrySet()) {
				if(s.getKey().endsWith("位置")&&"前".equals(s.getValue())) {
					Map<String,String> charaData=new HashMap<>();
					String name=s.getKey().substring(0,s.getKey().length()-2);
					charaData.put("姓名", name);
					charaData.put("表情", characs.get(name+"表情"));
					String cchara=character.getSceneData(charaData);
					if(cchara!=null)
						charaList.add(cchara);
				}
			}
			StringBuilder charaBuilder=new StringBuilder();
			for(String s:charaList) {
				charaBuilder.append(s).append(",");
			}
			chara=charaBuilder.toString();
		}
		if(chara==null)
			chara=oldchara;
		if(chara!=null) {
			state.setScene("front", chara);
		}else {
			state.setScene("front", "");
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
			state.setScene("front", chara);
		}else {
			state.setScene("front", "");
		}
		if(bg!=null)
			state.setScene("back", bg);
		else
			state.setScene("back", "");
	};
	public String constructSystem(StateIntf state) {
		if (state == null || state.intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("");
		for (Interface intf : state.intfs.values())
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
