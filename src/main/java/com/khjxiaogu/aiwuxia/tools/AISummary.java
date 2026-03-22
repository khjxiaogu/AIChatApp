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
package com.khjxiaogu.aiwuxia.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;


public class AISummary {

	public static void main(String[] args) throws IOException {
		String name="fengyitalk";
		int idx=0;
		File dataFolder=new File("save");
		String system=FileUtil.readString(new File(dataFolder,"summaryprompt2.txt"));
		String charaset=FileUtil.readString(new File(dataFolder,"charaset.txt"));
		LLMConnector.initDefault();
		//File saveData = new File(new File(dataFolder,"saveData"), "save+"+name+idx+".json");
		File saveData = new File(new File(dataFolder,"saveData"), "a718f2955f2a4f7fbebafca629f3c3b1.json");
		
		MemoryHistory his=AIApplication.historyFromJson(saveData);
		Iterator<HistoryItem> it=his.iterator();
		StringBuilder summary=new StringBuilder("==故事设定==\n");
		summary.append(charaset).append("\n");
		summary.append("==对话==");
		
		String str=null;
		Gson json=new GsonBuilder().setPrettyPrinting().create();
		int len=0;
		int i=0;
		Scanner scan=new Scanner(System.in);
		while(it.hasNext()) {
			
			HistoryItem hi=it.next();
		
			len+=hi.getContextContent().length();
			
			if(hi.getRole()!=Role.SYSTEM) {
				if(hi.getRole()==Role.USER)
					summary.append("【主角】：");
				summary.append(hi.getDisplayContent()).append("\n");
			}
			if(len>60000) {
				len=0;
				str=summary.toString();
			
				
				File curSecFile=new File(dataFolder,"summary-gen-"+(i+1)+".json");
				Map<String,String> curSection=null;
				boolean isLoaded=false;
				if(curSecFile.exists()) {
					System.out.println("recover state from file");
					isLoaded=true;
					curSection=json.fromJson(FileUtil.readString(curSecFile), Map.class);
				}else {
					System.out.println("=============================");
					System.out.println("=============================");
					System.out.println(str);
					System.out.println("=============================");
					System.out.println("=============================");
					while(curSection==null)
						curSection=splitSections(LLMConnector.call(constructSummaryrequest(system,str)));
					FileUtil.transfer(json.toJson(curSection), curSecFile);
				}
				Map<String,String> lastSection=null;
				File lastSecFile=new File(dataFolder,"summary-gen-"+i+".json");
				if(lastSecFile.exists()) {
					lastSection=json.fromJson(FileUtil.readString(lastSecFile), Map.class);
				}
				summary=new StringBuilder("==故事设定==\n");
				summary.append(charaset).append("\n");
				summary.append("\n==永久记忆==\n");
				if(lastSection!=null) 
					summary.append(lastSection.get("永久记忆").trim()).append("\n");
				summary.append(curSection.get("永久记忆").trim()).append("\n\n");
				List<String> stories=new ArrayList<>();
				if(lastSection!=null) 
					for(String s:lastSection.get("故事脉络").split("\n")) {
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
				summary.append("\n==故事脉络==\n");
				for(int j=minSection;j<stories.size();j++)
					summary.append(stories.get(j)).append("\n");
				
				summary.append("\n==约定==\n").append(curSection.get("约定"));
				summary.append("\n==角色状态==\n").append(curSection.get("角色状态"));
				summary.append("\n==持有物品==\n").append(curSection.get("持有物品"));
				summary.append("\n==前情提要==\n").append(curSection.get("对话摘要"));
					
				
				summary.append("\n==对话==\n");
				++i;
				System.out.println("gen"+i);
				if(!isLoaded)
				scan.nextLine();
				//if(++i>=8)
				//break;
				//lastSummary=makeSummaryrequest(system,lastSummary,str);
			}
			
			
		}
		//Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(str), null);
		//System.out.println("=============最终结果================");
		//System.out.println(lastSummary);
	}
	public static Map<String,String> splitSections(AIOutput resp) throws IOException {
		boolean isWaiting = true;
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		Map<String,String> section=new LinkedHashMap<>();
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
}
