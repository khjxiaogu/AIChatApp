package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
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

public class AISQLMain extends AIApplication {

	public AISQLMain(File folder){
		try {
			system = FileUtil.readString(new File(folder, "prompt.txt")).replace("\r", "");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// naming
		handlers.add((state, ret) -> {

			if (state.getStage() == ApplicationStage.NAMING) {
				state.setStage(ApplicationStage.STARTED);
			}
			return ret;
		});
		handlers.add((state, ret) -> {

			
				if (ret.startsWith("定义")) {
					state.getState().extras.add(ret.substring(2).trim());

					return null;
				}
			
			return ret;
		});
		// check interface
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.STARTED) {
				if ("查看定义".equals(ret)) {
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
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state, constructSystem(state.getState())));
			state.getLast().setLastState(airet);
			state.addDialogRow();

			return null;
		});
	}

	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
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

	public AIRequest constructAIrequest(AISession state, String status) {
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
		Builder builder=AIRequest.builder().taskType(TaskType.CODE).strength(ReasoningStrength.WEAK).streamed();
		return builder.build(b.end().add("temperature", 1.7).add("max_tokens", 8192).add("presence_penalty", 1).end());

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
		state.add(Role.ASSISTANT,"请提供表定义，定义必须开始以“定义”二字",false);
		state.setStage(ApplicationStage.NAMING);
	}

	public ApplicationState precessResponse(AIOutput op, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;
		
		try(BufferedReader scan=new BufferedReader(op.getContent())){
			ApplicationState oldstate = new ApplicationState(state.getState());
			handleReasonerContent(op,state);
			boolean nstateModified = false;
			while (true) {
				String last = scan.readLine();
				if(last==null)
					break;
				if (isWaiting && last.isEmpty()) {
					continue;
				}
	
				if (isWaiting) {
					//System.out.println("\n=================Content===============");
					isWaiting = false;
				}
				//System.out.println(last);
				if (status == 0) {
					if (last.startsWith("==SQL==")) {
						status = 2;
						state.appendContextLine(Role.ASSISTANT, last);
					} else {  
						state.appendLine(Role.ASSISTANT, last, true);
					}
				}  else {
					state.appendLine(Role.ASSISTANT, last, true);
				}
	
			}
			state.appendLine(Role.ASSISTANT, "\n输入“重新生成”重新生成，输入“撤回”删除上一次对话。", false);
	
			return nstateModified ? oldstate : null;
		}
	}

	public String constructSystem(ApplicationState state) {
		if (state == null || state.extras.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("=====定义=====\n");
		for (String s:state.extras)
			sb.append(s).append("\n");
		return sb.toString();

	}

	@Override
	public String getName() {
		return "sql";
	}

	@Override
	public String getBrief(AISession state) {
		if(state.getHistory().size()<2)return null;
		CharSequence content="SQL";
		return content.subSequence(1, Math.min(10, content.length())).toString();
	}
}
