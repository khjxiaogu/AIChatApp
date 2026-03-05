package com.khjxiaogu.aiwuxia.apps;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.AIApplication;
import com.khjxiaogu.aiwuxia.AISession;
import com.khjxiaogu.aiwuxia.Role;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.state.AIOutput;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.StateIntf;
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
				}
			}
			return ret;
		});

		handlers.add(revertAndRegen);
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			StateIntf airet = sendAndProcessResultStreamed(state, constructAIrequest(state, constructSystem(state.getState())));
			state.getLast().setLastState(airet);
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
		AIOutput resp = sendAIStreamedRequest(req, state::addUsage);
		try (Scanner sc = new Scanner(resp.getContent())) {
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
			Iterator<HistoryItem> it=history.sendableIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getFullContent().toString().trim()).end();
				
			}
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
		CharSequence content=state.getState().extras.get(0);
		return content.subSequence(1, Math.min(10, content.length())).toString();
	}
}
