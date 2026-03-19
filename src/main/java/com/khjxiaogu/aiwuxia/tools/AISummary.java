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
import java.util.Iterator;

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
		String system=FileUtil.readString(new File(dataFolder,"summaryprompt.txt"));
		LLMConnector.initDefault();
		File saveData = new File(new File(dataFolder,"saveData"), "save+"+name+idx+".json");
		MemoryHistory his=AIApplication.historyFromJson(saveData);
		Iterator<HistoryItem> it=his.iterator();
		StringBuilder summary=new StringBuilder();
		String lastSummary=null;
		int len=0;
		while(it.hasNext()) {
			HistoryItem hi=it.next();
		
			len+=hi.getContextContent().length();
			
			if(hi.getRole()!=Role.SYSTEM) {
				if(hi.getRole()==Role.USER)
					summary.append("【用户】：");
				summary.append(hi.getDisplayContent()).append("\n");
			}
			if(len>60000) {
				len=0;
				String str=summary.toString();
				summary=new StringBuilder();
				lastSummary=makeSummaryrequest(system,lastSummary,str);
			}
			
			
		}
		System.out.println("=============最终结果================");
		System.out.println(lastSummary);
	}
	public static AIRequest constructSummaryrequest(String prompt, String lastASummary, String summary) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
				.add("role", "system").add("content", prompt).end();
			StringBuilder sumerize=new StringBuilder();
			if(lastASummary!=null) {
				sumerize.append("=== 前情提要 ===\\n");
				sumerize.append(lastASummary);
			}
			sumerize.append("=== 对话块 ===\n");
			sumerize.append(summary.trim());
			// if (status != null&&!status.isEmpty())
			b.object().add("role",Role.USER.getRoleName()).add("content", sumerize.toString()).end();


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return AIRequest.builder().taskType(TaskType.STORY).strength(ReasoningStrength.STRONG).build(b.end().add("temperature", 1.3).add("max_tokens", 8192).end());

	}
	public static String makeSummaryrequest(String prompt, String lastSummary, String summary) throws IOException {
		
			AIOutput resp=LLMConnector.call(constructSummaryrequest(prompt, lastSummary,summary));
			System.out.println("=============思维链================");
			printAndCollectContent(resp.getReasoner());
			System.out.println("=============输出================");
			return printAndCollectContent(resp.getContent());

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
		return sb.toString();
	}
}
