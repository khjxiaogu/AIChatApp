package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIGroupApplication extends AIApplication {
	String name;
	String charaName;
	@Override
	public void provideInitial(AISession state) {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String constructSystem(ApplicationState state) {
		return "";
	}

	@Override
	public String getBrief(AISession state) {
		return name;
	}
	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
		return precessResponse(resp, state);
		
	}
	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		
		ApplicationState oldstate = new ApplicationState(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		StringBuilder sendContent=new StringBuilder();
		handleReasonerContent(resp,state);
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
			sendContent.append(last).append("\n");
		}
	
		state.add(Role.ASSISTANT, sendContent.toString().trim(), sendContent.toString().trim());
		
		return oldstate;
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system).end();

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
			if(len>=100000) {//more than 100000 text:about 60k context,remove until 20000
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				it=history.validContextIterator();
				while(it.hasNext()) {//calculate total dialog rows
					HistoryItem hi=it.next();
					len-=hi.getContextContent().length();
					
					if(hi.getRole()!=Role.SYSTEM) {
						summery.append(getRoleName(state,hi.getRole())).append("：").append(hi.getContextContent()).append("\n");
					}
					his.add(hi);
					if(len<=20000&&hi.getRole()==Role.ASSISTANT) {
						break;
					}
					
				}
				state.getExtra().put("lastSummary", makeSummaryrequest(state,summery.toString()));
				his.forEach(t->t.setValidContext(false));
				state.minDialogRows(his.size());
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
		//头几次用思维链版本构建格式
		Builder builder=AIRequest.builder().taskType(TaskType.STORY).strength(ReasoningStrength.WEAK);
		
		return builder.build(b.end().add("temperature", 1.3).add("max_tokens", 500).end());

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
			//System.out.println(resp.choices.get(0).message.reasoning_content);
			return resp.getContentText();
		

	}
	@Override
	public String getRoleName(AISession state, Role role) {
		return role==Role.ASSISTANT?charaName:super.getRoleName(state, role);
	}
	String summary;
	public AIGroupApplication(File path,String name,JsonObject meta) throws IOException {
		super();
		this.name=name;
		system = 
			
			FileUtil.readString(new File(path, "role.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "rules.txt")).replace("\r", "");
		summary = FileUtil.readString(new File(path, "summary.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "");
		this.charaName=meta.get("charaName").getAsString();
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.getLast().setLastState(airet);
			state.addDialogRow();

			return null;
		});
	}
}
