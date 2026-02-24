package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.scheme.RespScheme;

public class AIGalgameMain extends AIApplication {
	Pattern sxPattern = Pattern.compile("【([^】]+)】([^【]+)");
	Pattern intfPattern = Pattern.compile("【([^面]+)面板】");


	public AIGalgameMain(String promptName) {
		super();
		try {
			system = FileUtil.readString(new File("save", promptName)).replace("\r", "");

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// naming
		handlers.add((state, ret) -> {

			if (state.getStage() == GameStage.NAMING) {
				if(ret.length()>6) {
					state.add(Role.ASSISTANT, "名称不得多于6字符", false);
				}else {
					
					if(state.extraData.containsKey("name")) {
						state.extraData.put("nick", ret);
						state.setStage(GameStage.STARTED);
						state.history.clear();
						return "开始游戏";
					}
					state.extraData.put("name", ret);
					state.add(Role.USER, ret, false);
					state.add(Role.ASSISTANT, "请输入昵称", false);
					
					
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
			StateIntf airet = sendAndProcessResultStreamed(state, constructAIrequest(state, constructSystem(state)));
			state.getLast().lastState = airet;
			state.addRow();

			return null;
		});
	}


	public void provideNames(AIState state) {
		if (state.getStage() == GameStage.NAMING) {
			state.removeLast();
			// state.removeLast();
		} 


	}

	public StateIntf sendAndProcessResult(AIState state, JsonObject req) throws IOException {
		RespScheme resp = sendAIRequest(req);
		state.addUsage(resp.usage);
		return precessResponse(new Scanner(resp.choices.get(0).message.content), state);
	}

	public StateIntf sendAndProcessResultStreamed(AIState state, JsonObject req) throws IOException {
		FilledReadable resp = sendAIStreamedRequest(req, state::addUsage);
		try (Scanner sc = new Scanner(resp)) {
			return precessResponse(sc, state);
		}
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
	public String constructNameState(String name,String nick){
		return "主角姓名为"+name+"，昵称为"+nick+"，你需要用该名称称呼主角。";
	}
	public JsonObject constructAIrequest(AIState state, String status) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system+constructNameState(state.extraData.get("name"),state.extraData.get("nick"))).end();
		if (status != null && !status.isEmpty())
			b.object().add("role", "system").add("content", status).end();
		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			Iterator<HistoryItem> it = history.reverseIterator();
			List<MessageAndRole> queue = new ArrayList<>();
			int i = 0;
			while (it.hasNext()) {
				HistoryItem current = it.next();
				if (current.shouldSend) {

					if (i == 0)
						queue.add(0, new MessageAndRole(current.role.getRoleName(), current.getFullContent()));
					else
						queue.add(0, new MessageAndRole(current.role.getRoleName(), current.getFullContent()));
					i++;
				}
				if (i >= 4 && current.role.equals("user"))
					break;
			}
			for (MessageAndRole hs : queue)
				b.object().add("role", hs.role).add("content", hs.message.toString().trim()).end();
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.end().add("model", "deepseek-reasoner").add("temperature", 1.3).add("max_tokens", 8192).add("stream", false).end();

	}

	public String constructBackLog(AIState state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(hs.role.getName()).append("：").append(hs.getContent())
				.append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AIState state) {
		state.add(Role.ASSISTANT, "请输入姓名", false);
		state.setStage(GameStage.NAMING);
	}

	public StateIntf precessResponse(Scanner scan, AIState state) {
		boolean isWaiting = true;
		int status = 0;

		Interface intf = null;
		StateIntf oldstate = new StateIntf(state.getState());
		boolean nstateModified = false;
		boolean isDraft=false;
		while (scan.hasNextLine()) {
			String last = scan.nextLine();
			if (isWaiting && last.isEmpty()) {
				continue;
			}

			if (isWaiting) {
				System.out.println("\n=================Content===============");
				isWaiting = false;
			}
			System.out.println(last);
			if(last.startsWith("==故事大纲==")) {
				state.appendInvisibleLine(Role.ASSISTANT, last);
				isDraft=true;
				continue;
			}
			if(isDraft) {
				if(last.startsWith("==属性面板==")||last.startsWith("==操作==")||last.startsWith("==情景剧本==")) {
					isDraft=false;
				}else {
					state.appendInvisibleLine(Role.ASSISTANT, last);
					continue;
				}
			}
			
			if (status == 0) {
				if (last.startsWith("==属性面板==")) {
					status = 1;
				} else if (last.startsWith("==操作==")) {
					status = 3;
					state.appendInvisibleLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
				} else {
					state.appendLine(Role.ASSISTANT, last, true);
				}
			} else if (status == 1) {
				if (last.startsWith("==操作==")) {
					status = 3;
					state.appendInvisibleLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
				} else if (last.startsWith("【叙事轨迹】")) {
					status = 2;
				} else {
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
		state.appendLine(Role.ASSISTANT, "\n输入“重新生成”重新生成剧情，输入“撤回”删除上一次对话。", false);

		return nstateModified ? oldstate : null;
	}

	public String constructSystem(AIState state) {
		if (state == null || state.getState().intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("==属性面板==\n");
		for (Interface intf : state.getState().intfs.values())
			sb.append(intf.toString());
		sb.append("【叙事轨迹】\n");
		for (Entry<String, String> p : state.getState().perks.entrySet()) {
			sb.append(p.getKey()).append(". ").append(p.getValue()).append("\n");
		}

		return sb.toString();

	}

	@Override
	public String getName() {
		return "galgame模拟器";
	}

	@Override
	public String getBrief(AIState state) {
		if(state.getState().intfs.isEmpty())
			return null;
		return state.getState().intfs.values().iterator().next().values.get("姓名");
	}
}
