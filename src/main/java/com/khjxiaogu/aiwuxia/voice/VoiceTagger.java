package com.khjxiaogu.aiwuxia.voice;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class VoiceTagger {
	File curPath;
	String sysprompt;
	HashSet<String> emotions=new HashSet<>();
	public VoiceTagger(File basePath) throws IOException {
		curPath=basePath;
		sysprompt=FileUtil.readString(new File(curPath,"voicetag.txt"));
		emotions.add("开心");
		emotions.add("吃惊");
		emotions.add("恐惧");
		emotions.add("难过");
		emotions.add("生气");
		emotions.add("中立");
		emotions.add("厌恶");
	}
	public CompletableFuture<JsonArray> extractTalkContent(String role,String text,Consumer<UsageIntf<?>> state) {
		//Iterator<HistoryItem> revit=hist.reverseIterator();
		String lastText=text;
		//ArrayList<String> foreWords=new ArrayList<>();
		/*if(revit.hasNext()) {
			HistoryItem hi=revit.next();
			if(hi.getRole()!=Role.ASSISTANT)return CompletableFuture.failedFuture(new IllegalArgumentException("Assistant word expected at last of history"));
			lastText=hi.getDisplayContent().toString();
		}else {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No history found"));
		}*/
		String unifiedLastText=lastText.replaceAll("（[^）]+）", "").trim();
		if(unifiedLastText.isEmpty())
			return CompletableFuture.completedFuture(new JsonArray());
		StringBuilder prompt=new StringBuilder();
		/*
		while(revit.hasNext()) {
			HistoryItem hi=revit.next();
			if(hi.getRole()!=Role.ASSISTANT&&hi.getRole()!=Role.USER)continue;
			foreWords.add(0,(hi.getRole()==Role.ASSISTANT?"角色：":"主角：")+hi.getDisplayContent().toString());
			if(foreWords.size()>=4)
				break;
		}
		
		prompt.append("**前情提要**\n");
		for(String s:foreWords)
			prompt.append(s);*/
		if(role!=null)
			prompt.append("**第一人称说话人**\n").append(role).append("\n");
		prompt.append("**待处理角色话语**\n");
		prompt.append(lastText);
		Builder b=AIRequest.builder("voiceTagger").modelHint("").taskType(TaskType.STORY).format(ResponseFormat.JSON).strength(ReasoningStrength.WEAK).temperature(0.2f).maxTokens(8192);
		b.addHistoryItem(Role.SYSTEM, sysprompt);

		b.addHistoryItem(Role.USER, prompt.toString());
			// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
			// true);
			//
		AIRequest request=b.build();
		return CompletableFuture.supplyAsync(()->{
			for(int i=0;i<5;i++) {
				try {
					AIOutput output=LLMConnector.call(request);
					output.addUsageListener(state);
					FileUtil.printAndCollectContent(output.getReasoner());
					//System.out.println(output.getReasonerText());
					String speech=output.getContentText();
					try {
						System.out.println(speech);
						JsonArray ja=JsonParser.parseString(speech).getAsJsonArray();
						for(JsonElement je:ja) {
							if(je.isJsonObject()) {
								JsonObject jo=je.getAsJsonObject();
								if(!jo.has("text"))
									throw new JsonSyntaxException("no text found.");
								if(!jo.has("emotion"))
									throw new JsonSyntaxException("no emotion found.");
								if(!emotions.contains(jo.get("emotion").getAsString()))
									throw new JsonSyntaxException("Illegal emotion "+jo.get("emotion").getAsString());
									
							}
						}
						if(ja.size()==0&&unifiedLastText.length()>3)
							continue;
						return ja;
					}catch(JsonSyntaxException ex) {
						System.out.println("output is not a json");
					}catch(IllegalStateException ex) {
						//not a json array
						System.out.println("output is not a json array");
					}catch(JsonIOException ex) {
						ex.printStackTrace();
					}
				} catch (ModelRouteException e) {
					e.printStackTrace();
					throw new RuntimeException(e);
				} catch (IOException e) {
					e.printStackTrace();
				} catch (Throwable t) {

					throw new RuntimeException(t);
				}
				
				
			}
			throw new RuntimeException("Could not generate response");
		});
	}
	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
		VoiceTagger vt=new VoiceTagger(new File("save"));
		LLMConnector.initDefault();

		System.out.println(vt.extractTalkContent("姚枫茜","（枫茜的脸瞬间涨得通红，连耳根都染上了粉色。她猛地转身，使劲瞪着你，却因为过于慌乱声音都在发抖）\n"
			+ "你、你你你胡说什么啊！谁可爱了！khj小骨你是不是脑子被门夹了！\n"
			+ "（她攥紧拳头作势要打你，但姐姐及时伸手拦住了她。枫茜气鼓鼓地背过身去，可连她自己都没发现——她的嘴角正不争气地翘得老高。）\n"
			+ "（姚枫怡：（轻笑）好了好了，到家了。khj小骨，谢谢你送我们回来，你也早点回去休息吧。）\n"
			+ "（枫茜闷声推开家门，一脚踏进去，又顿住，头也不回地丢下一句话，声音却软了许多）……明天早上，别忘了给姐姐带她喜欢的豆沙包。还有你那份早点钱，我帮你付了！算是……回礼！才不是因为什么可爱不可爱的！\n"
			+ "（她说完“砰”地关上门，门里隐约传来她跺脚的动静和一声懊恼的嘀咕：“呜哇好丢人我到底在说什么啊——！！！”）", e->{}).get());
	}
}
