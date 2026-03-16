package com.khjxiaogu.aiwuxia.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class InteractiveConclusionHandler {

	public static void main(String[] args) throws IOException {
		File workingPath=FileUtil.choose();
		if(workingPath==null)return;
		LLMConnector.initDefault();
		String system=FileUtil.readString(new File("save","conclusionprompt.txt"));
		File output=new File("contentOutput.txt");
		try(PrintStream fos=new PrintStream(output,"UTF-8")){
			for(File f:workingPath.listFiles()) {
				String in=new String(FileUtil.readAll(f),"UTF-16LE");
				AIRequest ar=AIRequest.builder().enableDeepThink().taskType(TaskType.ANALYSIS).strength(ReasoningStrength.STRONG).streamed()
				.build(conclusionRequest(system,in));
				AIOutput ao=LLMConnector.call(ar);
				printAndCollectContent(ao.getReasoner());
				System.out.println();
				System.out.println("==========begin content==========");
				String last=printAndCollectContent(ao.getContent());
				fos.println();
				fos.println("=========="+f.getName()+"==========");
				fos.println(last);
			}
			
		}
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
	public static JsonObject conclusionRequest(String system,String text) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system).end();
			b.object().add("role", "user").add("content", text).end();
		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return b.end().add("model", "deepseek-chat").add("temperature", 1.0).add("stream", true).add("max_tokens", 8192).add("presence_penalty", 1).end();

	}
}
