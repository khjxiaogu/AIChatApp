package com.khjxiaogu.aiwuxia.apps;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
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
import com.khjxiaogu.aiwuxia.utils.BlockingReader;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIArticleMain extends AIApplication {
	Pattern sxPattern = Pattern.compile("【([^】]+)】([^【]+)");
	Pattern intfPattern = Pattern.compile("【([^面]+)面板】");

	List<String> femalenames;
	List<String> malenames;
	{
		
		// naming
		handlers.add((state, ret) -> {

			if (state.getStage() == GameStage.NAMING) {
				state.setStage(GameStage.STARTED);
			}
			return ret;
		});
		// check interface
		handlers.add((state, ret) -> {
			if (state.getStage() == GameStage.STARTED) {
				if ("查看大纲".equals(ret)) {
					state.add(Role.USER, ret, false);
					state.add(Role.ASSISTANT, constructSystem(state.getState()), false);
					return null;
				} else if ("重新生成".equals(ret)) {
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
			StateIntf airet = sendAndProcessResultStreamed(state, constructAIrequest(state, constructSystem(state.getState())));
			state.getLast().lastState = airet;
			state.addRow();

			return null;
		});
	}

	public AIArticleMain(File path) {
		try {
			system = FileUtil.readString(new File(path, "promptwrite.txt")).replace("\r", "");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public StateIntf sendAndProcessResult(AISession state, JsonObject req) throws IOException {
		RespScheme resp = sendAIRequest(req);
		state.addUsage(resp.usage);
		return precessResponse(new Scanner(resp.choices.get(0).message.content), state);
	}

	public StateIntf sendAndProcessResultStreamed(AISession state, JsonObject req) throws IOException {
		BlockingReader resp = sendAIStreamedRequest(req, state::addUsage);
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

	public JsonObject constructAIrequest(AISession state, String status) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system).end();
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
						queue.add(0, new MessageAndRole(current.getRole().getRoleName(), current.getFullContent()));
					else
						queue.add(0, new MessageAndRole(current.getRole().getRoleName(), current.getFullContent()));
					i++;
				}
				if (i >= 4 && current.getRole().equals("user"))
					break;
			}
			for (MessageAndRole hs : queue)
				b.object().add("role", hs.role).add("content", hs.message.toString().trim()).end();
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.end().add("model", "deepseek-chat").add("temperature", 1.7).add("stream", false).add("max_tokens", 8192).add("presence_penalty", 1).end();

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
		state.add(Role.ASSISTANT,"请提供写作内容要求",false);
		state.setStage(GameStage.NAMING);
	}

	public StateIntf precessResponse(Scanner scan, AISession state) {
		boolean isWaiting = true;
		int status = 0;

		Interface intf = null;
		StateIntf oldstate = new StateIntf(state.getState());
		boolean nstateModified = false;
		while (scan.hasNextLine()) {
			String last = scan.nextLine();
			if (isWaiting && last.isEmpty()) {
				continue;
			}

			if (isWaiting) {
				//System.out.println("\n=================Content===============");
				isWaiting = false;
			}
			//System.out.println(last);
			if (status == 0) {
				if (last.startsWith("==大纲==")) {
					status = 1;
					state.appendInvisibleLine(Role.ASSISTANT, last);
				} else if (last.startsWith("==内容==")) {
					status = 2;
				} else {  
					state.appendLine(Role.ASSISTANT, last, true);
				}
			} else if (status == 1) {
				if (last.startsWith("==内容==")) {
					status = 2;
				} else {
					state.getState().extras.add(last);
					state.appendInvisibleLine(Role.ASSISTANT, last);
				}
			} else {
				state.appendLine(Role.ASSISTANT, last, true);
			}

		}
		state.appendLine(Role.ASSISTANT, "\n输入“查看大纲”查看此前的大纲，输入“重新生成”重新生成剧情，输入“撤回”删除上一次对话。", false);

		return nstateModified ? oldstate : null;
	}

	public String constructSystem(StateIntf state) {
		if (state == null || state.extras.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("==大纲==\n");
		for (String s:state.extras)
			sb.append(s).append("\n");
		return sb.toString();

	}

	@Override
	public String getName() {
		return "写作";
	}

	@Override
	public String getBrief(AISession state) {
		if(state.getHistory().size()<2)return null;
		CharSequence content=state.getHistory().get(1).getContent();
		return content.subSequence(1, Math.min(10, content.length())).toString();
	}
}
