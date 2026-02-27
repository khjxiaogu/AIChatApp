package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.AIApplication;
import com.khjxiaogu.aiwuxia.AISession;
import com.khjxiaogu.aiwuxia.Role;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.Interface;
import com.khjxiaogu.aiwuxia.state.StateIntf;
import com.khjxiaogu.aiwuxia.utils.BlockingReader;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AICharaTalkMain extends AIApplication {
	Pattern sxPattern = Pattern.compile("【([^】]+)】([^【]+)");
	Pattern intfPattern = Pattern.compile("【([^面]+)面板】");
	String charaname;
	String summary;
	SceneSelector back;
	SceneSelector character;
	File basePath;

	@Override
	public File getResource(String path) {
		
		return new File(basePath,path);
		
	}


	public AICharaTalkMain(File basePath,String modelFolder,String charaname) {
		super();
		this.charaname=charaname;
		this.basePath=basePath;
		try {
			File model=new File(basePath,modelFolder);
			system = FileUtil.readString(new File(model, "prompt.txt")).replace("\r", "");
			summary = FileUtil.readString(new File(model, "summary.txt")).replace("\r", "");
			if(new File(model,"chara.json").exists()) {
				character=gs.fromJson(FileUtil.readString(new File(model,"chara.json")), SceneSelector.class);
				
			}
			if(new File(model,"back.json").exists()) {
				back=gs.fromJson(FileUtil.readString(new File(model,"back.json")), SceneSelector.class);
				
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
					state.add(Role.SYSTEM, "已输入姓名为"+ret+"，可以开始对话了！", false);
					return null;
				}
				return null;
			}
			return ret;
		});
		// check interface
		handlers.add((state, ret) -> {
			if (state.getStage() == GameStage.STARTED) {
				if ("重新生成".equals(ret)) {
					HistoryItem hi = state.removeLast();
					HistoryItem userhi = state.removeLast();
					state.minRow();
					if (hi.lastState != null) {
						state.getState().set(hi.lastState);
					}
					return userhi.getContent().toString();
				} else if ("撤回".equals(ret)) {
					HistoryItem hi = state.removeLast();
					HistoryItem userhi = state.removeLast();
					state.minRow();
					if (hi.lastState != null) {
						state.getState().set(hi.lastState);
					}
					return null;
				}
			}
			return ret;
		});
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			StateIntf airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.getLast().lastState = airet;
			state.addRow();

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
		if (state.getStage() == GameStage.NAMING) {
			state.removeLast();
			// state.removeLast();
		} 


	}

	public StateIntf sendAndProcessResult(AISession state, JsonObject req) throws IOException {
		RespScheme resp = sendAIRequest(req);
		state.addUsage(resp.usage);
		return precessResponse(new StringReader(resp.choices.get(0).message.content), state);
	}

	public StateIntf sendAndProcessResultStreamed(AISession state, JsonObject req) throws IOException {
		//System.out.println(AIApplication.ppgs.toJson(req));
		BlockingReader resp = sendAIStreamedRequest(req, state::addUsage);
		
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
			List<MessageAndRole> queue = new ArrayList<>();
			
			int len=0;
			for(HistoryItem hi:history) {//calculate total dialog rows
				if(hi.shouldSend) {
					if(hi.getRole()==Role.ASSISTANT) {
						i++;
					}
					len+=hi.getFullContent().length();
				}
			}
			if(len>=100000) {//more than 100000 text:about 60k context,remove until 20000
				HistoryItem lasthi=null;
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				for(HistoryItem hi:history) {//calculate total dialog rows
					if(hi.shouldSend) {
						len-=hi.getFullContent().length();
						
						if(hi.getRole()!=Role.SYSTEM) {
							summery.append(getRoleName(state,hi.getRole())).append("：").append(hi.getFullContent()).append("\n");
						}
						his.add(hi);
						if(len<=20000&&hi.getRole()==Role.ASSISTANT) {
							lasthi=hi;
							break;
						}
					}
				}
				history.add(0, new HistoryItem(Role.SYSTEM,makeSummaryrequest(state,summery.toString()),true));
				his.forEach(t->t.shouldSend=false);
			}
			int size=history.size();
			int diff=0;
			for(int j=0;j<size;j++) {
				HistoryItem hi=history.get(j);
				if (hi.shouldSend) {
					diff++;
					b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getFullContent().toString().trim()).end();
					/*if(diff%30==0)
						b.object().add("role", "system").add("content", system).end();*/
				}
			}
				
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		//头几次用思维链版本构建格式
		return b.end().add("model",i<=2?"deepseek-reasoner":"deepseek-chat").add("temperature", 1.3).add("max_tokens", 500).add("stream", false).end();

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
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getContent())
				.append("\n");
		}

		return sb.toString();

	}
	public String constructSummaryBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			if(hs.shouldSend)
				sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getFullContent()).append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AISession state) {
		state.add(Role.SYSTEM, "请输入姓名", false);
		state.setStage(GameStage.NAMING);
	}

	public StateIntf precessResponse(Reader scan, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;

		StateIntf oldstate = new StateIntf(state.getState());
		boolean nstateModified = false;
		BufferedReader reader=new BufferedReader(scan);
		String last;
		StringBuilder sb=new StringBuilder();
		String oldchara=state.getExtra().get("chara");
		String oldbg=state.getExtra().get("back");
		if(back!=null)
		oldbg=back.getSceneData(state.getState().perks);
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
				if (last.startsWith("==场景==")) {
					status = 3;
					state.appendInvisibleLine(Role.ASSISTANT, last);
				} else {
					state.appendInvisibleLine(Role.ASSISTANT, last);
					if(!last.startsWith("==对话=="))
						sb.append(last).append("\n");
				}
			} else if (status == 3) {
				state.appendInvisibleLine(Role.ASSISTANT, last);
				if(last.contains("=")) {
					String[] lasts=last.split("=");
					state.getState().perks.put(lasts[0], lasts[1]);
					
				}
			}

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
				state.setScene("front", chara);
				state.setScene("side", "");
				break;
			case "侧":
				state.setScene("front", "");
				state.setScene("side", chara);
				break;
			default:
				state.setScene("front", "");
				state.setScene("side", "");
				break;
			}
		}else {
			state.setScene("front", "");
			state.setScene("side", "");
		}
		
		String bg=null;
		if(back!=null)
			bg=back.getSceneData(state.getState().perks);
		if(bg==null)
			bg=oldbg;
		if(bg!=null)
			state.setScene("back", bg);
		else
			state.setScene("back", "");
		state.getExtra().put("chara",chara);
		state.getExtra().put("back",bg);
		state.appendLine(Role.ASSISTANT, sb.toString(), false);
		return nstateModified ? oldstate : null;
	}
	public String constructSystem(StateIntf state) {
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
