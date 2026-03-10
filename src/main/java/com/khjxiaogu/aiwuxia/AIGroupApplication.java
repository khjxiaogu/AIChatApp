package com.khjxiaogu.aiwuxia;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.state.AIOutput;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.StateIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIGroupApplication extends AIApplication {
	String name;
	@Override
	public void provideInitial(AISession state) {
	}

	@Override
	public String getName() {
		return "枫怡群聊";
	}

	@Override
	public String constructSystem(StateIntf state) {
		return "";
	}

	@Override
	public String getBrief(AISession state) {
		return "";
	}
	public StateIntf sendAndProcessResultStreamed(AISession state, JsonObject req) throws IOException {
		AIOutput resp = sendAIStreamedRequest(req, state::addUsage);
		return precessResponse(resp, state);
		
	}

	public StateIntf precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		
		StateIntf oldstate = new StateIntf(state.getState());
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
	public JsonObject constructAIrequest(AISession state) throws IOException {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system).end();

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
				while(it.hasNext()) {//calculate total dialog rows
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
		return b.end().add("model",/*i<=10?*/"deepseek-reasoner"/*:"deepseek-chat"*/).add("temperature", 1.3).add("max_tokens", 500).add("stream", false).end();

	}
	public JsonObject constructSummaryrequest(AISession state,String summary) {
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
		return b.end().add("model", "deepseek-reasoner").add("temperature", 1.0).add("max_tokens", 8192).add("stream", false).end();

	}
	public String makeSummaryrequest(AISession state,String summary) throws IOException {
		
			RespScheme resp=super.sendAIRequest(constructSummaryrequest(state,summary));
			state.addUsage(resp.usage);
			
			//System.out.println(resp.choices.get(0).message.reasoning_content);
			return resp.choices.get(0).message.content;
		

	}
	String summary;
	public AIGroupApplication(File path) throws IOException {
		super();
		system = 
			
			FileUtil.readString(new File(path, "role.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "rules.txt")).replace("\r", "");
		summary = FileUtil.readString(new File(path, "summary.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "");
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			StateIntf airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.getLast().setLastState(airet);
			state.addRow();

			return null;
		});
	}
}
