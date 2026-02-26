package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.AIApplication;
import com.khjxiaogu.aiwuxia.AISession;
import com.khjxiaogu.aiwuxia.Role;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.Interface;
import com.khjxiaogu.aiwuxia.state.StateIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.BlockingReader;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIGalgameMain extends AIApplication {
	Pattern sxPattern = Pattern.compile("【([^】]+)】([^【]+)");
	Pattern intfPattern = Pattern.compile("【([^面]+)面板】");
	String charaname;

	public AIGalgameMain(File basePath,String promptName,String charaname) {
		super();
		this.charaname=charaname;
		try {
			system = FileUtil.readString(new File(basePath, promptName)).replace("\r", "");

		} catch (IOException e) {
			e.printStackTrace();
		}
		// naming
		handlers.add((state, ret) -> {

			if (state.getStage() == GameStage.NAMING) {
				if(ret.length()>6) {
					state.add(Role.ASSISTANT, "名称不得多于6字符", false);
				}else {
					
					/*if(state.extraData.containsKey("name")) {
						state.extraData.put("nick", ret);
						
						return "开始游戏";
					}*/
					state.getExtra().put("name", ret);
					state.add(Role.USER, ret, false);
					state.setStage(GameStage.STARTED);
					return "开始游戏";
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
		return "主角姓名为"+name+"，你需要用该名称称呼主角。";
	}
	public JsonObject constructAIrequest(AISession state) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system+constructNameState(state.getExtra().get("name"))).end();

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			List<MessageAndRole> queue = new ArrayList<>();
			int i = 0;
			int len=0;
			for(HistoryItem hi:history) {//calculate total dialog rows
				if(hi.shouldSend) {
					if(hi.getRole()==Role.ASSISTANT) {
						i++;
					}
					len+=hi.getFullContent().length();
				}
			}
			
			if(len>=140000) {//more than 140000 text:about 100k context,remove until 60000
				HistoryItem lasthi=null;
				for(HistoryItem hi:history) {//calculate total dialog rows
					if(hi.shouldSend) {
						len-=hi.getFullContent().length();
						hi.shouldSend=false;
						if(len<=60000&&hi.getRole()==Role.ASSISTANT) {
							lasthi=hi;
							break;
						}
					}
				}
				if(lasthi!=null)
				history.add(0, new HistoryItem(Role.SYSTEM,constructSystem(lasthi.lastState),true));
			}
			int size=history.size();
			for(int j=0;j<size;j++) {
				HistoryItem hi=history.get(j);
				if (hi.shouldSend) {
					
					b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getFullContent().toString().trim()).end();
				}
			}
				
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.end().add("model", "deepseek-chat").add("temperature", 1.3).add("max_tokens", 8192).add("stream", false).end();

	}

	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getContent())
				.append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AISession state) {
		state.add(Role.ASSISTANT, "请输入姓名", false);
		state.setStage(GameStage.NAMING);
	}

	public StateIntf precessResponse(Reader scan, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;

		Interface intf = null;
		StateIntf oldstate = new StateIntf(state.getState());
		boolean nstateModified = false;
		boolean isDraft=false;
		BufferedReader reader=new BufferedReader(scan);
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
			if(last.startsWith("==故事大纲==")) {
				state.appendInvisibleLine(Role.ASSISTANT, last);
				isDraft=true;
				continue;
			}
			if(isDraft) {
				if(last.startsWith("==属性面板==")||last.startsWith("==操作==")||last.startsWith("==情景剧本==")) {
					isDraft=false;
					//no append because later
				}else {
					
					state.appendInvisibleLine(Role.ASSISTANT, last);
					state.getState().extras.add(last);
					continue;
				}
			}
			
			if (status == 0) {//处理主要剧情
				if (last.startsWith("==属性面板==")) {
					status = 1;
					state.appendInvisibleLine(Role.ASSISTANT, last);
				} else if (last.startsWith("==操作==")) {
					status = 3;
					state.appendInvisibleLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
				} else {
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
			} else if (status == 1) {//处理属性面板
				if (last.startsWith("==操作==")) {
					state.appendInvisibleLine(Role.ASSISTANT, buildAttrStr(state));
					status = 3;
					state.appendInvisibleLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
					
				}else {
					//state.appendInvisibleLine(Role.ASSISTANT, last);
					Matcher m1 = intfPattern.matcher(last);
					if (m1.find()) {
						intf = state.getState().intfs.computeIfAbsent(m1.group(1), Interface::new);
						continue;
					}
					if (intf == null)
						continue;
					Matcher m2 = sxPattern.matcher(last);
					int index = 0;
					while (m2.find(index)) {
						intf.values.put(m2.group(1), m2.group(2));
						index = m2.end();
					}
					nstateModified = true;
				}
			} else if (status == 2) {
				if (last.startsWith("==操作==")) {
					status = 3;
					state.appendInvisibleLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
				} else {
					int pos = last.indexOf('.');
					if (pos > 0) {
						String cont = last.substring(pos + 1).trim();
						String num = last.substring(0, pos).trim();
						if (cont.startsWith("【删除】"))
							state.getState().perks.remove(num);
						else
							state.getState().perks.put(num, cont);
						nstateModified = true;
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
		for (Interface intf : state.getState().intfs.values())
			sb.append(intf.toString());
		return sb.toString();
	}
	public String constructSystem(StateIntf state) {
		if (state == null || state.intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("==属性面板==\n");
		for (Interface intf : state.intfs.values())
			sb.append(intf.toString());
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
		if(state.getState().intfs.isEmpty())
			return null;
		return state.getState().intfs.values().iterator().next().values.get("姓名");
	}
}
