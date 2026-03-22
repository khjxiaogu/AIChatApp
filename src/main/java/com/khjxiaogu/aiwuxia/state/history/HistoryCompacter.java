package com.khjxiaogu.aiwuxia.state.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class HistoryCompacter {
	String system;
	String charaset;
	String version;
	final Gson json=new GsonBuilder().setPrettyPrinting().create();
	public void compactHistory(Map<String,String> state,String dialog,Consumer<Usage> usage) throws ModelRouteException, IOException {
		StringBuilder summary=new StringBuilder();
		summary.append("==故事设定==\n").append(charaset).append("\n");
		
		if(state.containsKey("永久记忆"))
			summary.append("\n==永久记忆==\n").append(state.get("永久记忆").trim()).append("\n\n");
		if(state.containsKey("故事脉络"))
			summary.append("\n==故事脉络==\n").append(state.get("故事脉络").trim()).append("\n\n");
		if(state.containsKey("约定"))
			summary.append("\n==约定==\n").append(state.get("约定").trim());
		if(state.containsKey("角色状态"))
			summary.append("\n==角色状态==\n").append(state.get("角色状态").trim());
		if(state.containsKey("持有物品"))
			summary.append("\n==持有物品==\n").append(state.get("持有物品").trim());
		if(state.containsKey("前情提要"))
			summary.append("\n==前情提要==\n").append(state.get("前情提要").trim());
		summary.append("\n==对话==\n").append(dialog);
		Map<String,String> curSection=null;
		String str=summary.toString();
		System.out.println(str);
		int retries=10;
		while(curSection==null) {
			curSection=splitSections(LLMConnector.call(constructSummaryrequest(system,str)),usage);
			if(curSection==null)
				try {
					retries--;
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					e.printStackTrace();
					Thread.currentThread().interrupt();
				}
			if(retries<0)
				throw new IllegalStateException("Compact history failed");
		}
		if(curSection!=null) {
			StringBuilder permanent=new StringBuilder();
			if(state.containsKey("永久记忆"))
				permanent.append(state.get("永久记忆").trim()).append("\n");
			permanent.append(curSection.get("永久记忆").trim());
			state.put("永久记忆", permanent.toString());
			List<String> stories=new ArrayList<>();
			StringBuilder storyPerk=new StringBuilder();
			
			if(state.containsKey("故事脉络"))
				for(String s:state.get("故事脉络").split("\n")) {
					s=s.trim();
					if(!s.isEmpty()) {
						stories.add(s);
					}
				}
			for(String s:curSection.get("故事脉络").split("\n")) {
				s=s.trim();
				if(!s.isEmpty()) {
					stories.add(s);
				}
			}
			int minSection=Math.max(stories.size()-50, 0);
			for(int j=minSection;j<stories.size();j++)
				storyPerk.append(stories.get(j)).append("\n");
			state.put("故事脉络", storyPerk.toString());
			state.put("约定",curSection.get("约定").trim());
			state.put("角色状态",curSection.get("角色状态").trim());
			state.put("持有物品",curSection.get("持有物品").trim());
			state.put("前情提要",curSection.get("对话摘要").trim());
		}
		
	}
	public void clearHistoryState(Map<String,String> state) {
		state.remove("永久记忆");
		state.remove("故事脉络");
		state.remove("约定");
		state.remove("角色状态");
		state.remove("持有物品");
		state.remove("前情提要");
		
	}
	public String constructHistory(Map<String,String> state) {
		StringBuilder summary=new StringBuilder();
		if(state.containsKey("永久记忆"))
			summary.append("\n==永久记忆==\n").append(state.get("永久记忆").trim()).append("\n\n");
		if(state.containsKey("故事脉络"))
			summary.append("\n==故事脉络==\n").append(state.get("故事脉络").trim()).append("\n\n");
		if(state.containsKey("约定"))
			summary.append("\n==约定==\n").append(state.get("约定").trim());
		if(state.containsKey("角色状态"))
			summary.append("\n==角色状态==\n").append(state.get("角色状态").trim());
		if(state.containsKey("持有物品"))
			summary.append("\n==持有物品==\n").append(state.get("持有物品").trim());
		if(state.containsKey("前情提要"))
			summary.append("\n==前情提要==\n").append(state.get("前情提要").trim());
		return summary.toString();
	}
	public static String printAndCollectContent(Reader output) throws IOException {
		BufferedReader br=new BufferedReader(output);
		int read;
		char[] ch=new char[32];
		StringBuilder sb=new StringBuilder();
		while((read=br.read(ch,0,32))!=-1) {
			if(read>0) {
				String input=String.valueOf(ch,0,read);
				System.out.print(input);
				sb.append(input);
			}
		}
		System.out.println();
		return sb.toString();
	}
	public static Map<String,String> splitSections(AIOutput resp,Consumer<Usage> usage) throws IOException {
		boolean isWaiting = true;
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		Map<String,String> section=new LinkedHashMap<>();
		resp.addUsageListener(usage);
		printAndCollectContent(resp.getReasoner());
		String currentSection="";
		StringBuilder currentContent=null;
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
			System.out.println(last);
			if(last.startsWith("==")&&last.endsWith("==")) {
				if(currentContent!=null)
					section.put(currentSection, currentContent.toString().trim());
				currentSection=last.substring(2,last.length()-2);
				currentContent=new StringBuilder();
			}else
			if(currentContent!=null)
				currentContent.append(last.trim()).append("\n");
		}
		if(currentContent!=null)
			section.put(currentSection, currentContent.toString().trim());
		return section.isEmpty()?null:section;
	}
	public static AIRequest constructSummaryrequest(String prompt,String input) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
				.add("role", "system").add("content", prompt).end();
			// if (status != null&&!status.isEmpty())
			b.object().add("role",Role.USER.getRoleName()).add("content", input).end();


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return AIRequest.builder().taskType(TaskType.STORY).strength(ReasoningStrength.STRONG).build(b.end().add("temperature", 1.3).add("max_tokens", 8192).end());

	}
	public HistoryCompacter(String system, String charaset, String version) {
		super();
		this.system = system;
		this.charaset = charaset;
		this.version = version;
	}
}
