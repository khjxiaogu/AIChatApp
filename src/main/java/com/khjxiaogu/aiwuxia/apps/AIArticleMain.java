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
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIArticleMain extends AIApplication {

	List<String> femalenames;
	List<String> malenames;
	{
		
		// check interface
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.STARTED) {
				if ("查看大纲".equals(ret)) {
					state.add(Role.USER, ret, false);
					state.add(Role.ASSISTANT, constructSystem(state.getState()), false);
					return null;
				}
			}
			return ret;
		});
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state, constructSystem(state.getState())));
			state.getLast().setLastState(airet);
			state.addDialogRow();

			return null;
		});
	}

	public AIArticleMain(File path) {
		try {
			system = FileUtil.readString(new File(path, "prompt.txt")).replace("\r", "");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public ApplicationState sendAndProcessResult(AISession state, JsonObject req) throws IOException {
		AIOutput resp=LLMConnector.call(AIRequest.builder().taskType(TaskType.STORY).build(req));
		resp.addUsageListener(state::addUsage);
		try (BufferedReader sc = new BufferedReader(resp.getContent())) {
			return precessResponse(sc, state);
		}
	}

	public ApplicationState sendAndProcessResultStreamed(AISession state, JsonObject req) throws IOException {
		AIOutput resp=LLMConnector.call(AIRequest.builder().taskType(TaskType.STORY).streamed().build(req));
		resp.addUsageListener(state::addUsage);
		try (BufferedReader sc = new BufferedReader(resp.getContent())) {
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
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getContextContent().toString().trim()).end();
				
			}
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.end().add("model", "deepseek-chat").add("temperature", 1.7).add("stream", false).add("max_tokens", 8192).add("presence_penalty", 1).end();

	}

	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getDisplayContent())
				.append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AISession state) {
		state.add(Role.ASSISTANT,"请提供写作内容要求",false);
		state.setStage(ApplicationStage.STARTED);
	}

	public ApplicationState precessResponse(BufferedReader scan, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;

		ApplicationState oldstate = new ApplicationState(state.getState());
		boolean nstateModified = false;
		while (true) {
			String last = scan.readLine();
			if(last==null)break;
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
					state.appendContextLine(Role.ASSISTANT, last);
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
					state.appendContextLine(Role.ASSISTANT, last);
				}
			} else {
				state.appendLine(Role.ASSISTANT, last, true);
			}

		}
		state.appendLine(Role.ASSISTANT, "\n输入“查看大纲”查看此前的大纲，输入“重新生成”重新生成剧情，输入“撤回”删除上一次对话。", false);

		return nstateModified ? oldstate : null;
	}

	public String constructSystem(ApplicationState state) {
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
