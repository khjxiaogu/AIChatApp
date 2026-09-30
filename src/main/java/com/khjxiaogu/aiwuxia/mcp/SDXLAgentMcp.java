package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.AgentMessager;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class SDXLAgentMcp {
	public static MCPTools createImage(File path,AISession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,List<String> charas,AgentMessager callback,boolean isNsfw,ResourceLock lock)  throws IOException {
		MCPTools tools=new MCPTools();
		MCPTools sdxl=SDXLMcp.createLocal(state, tos, lora, charas, isNsfw, lock);
		FetchMcp.create(state, tos).addTool(sdxl);
		MultiModalMcp.create(tos, state::addUsage).addTool(sdxl);
		
		String system = 
				
				FileUtil.readString(new File(path, "role.txt")).replace("\r", "")+
				FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")+
				FileUtil.readString(new File(path, "rules.txt")).replace("\r", "")+
				(isNsfw?"":"\n3.禁止返回被判定为NSFW的图片，如果遇到类似情况你可以多尝试能否生成正常图片，如果两次均不能生成则输出失败和原因。");
		tools.register(new ToolData.Builder("sdxl_agent", "调用多模态模型生成图片，同一张图片只能有0-2个角色，每次调用该工具都会创建一个新的无状态subagent。")
				.putParam("reference", "参考图列表，包含多个图片id以英文逗号,分隔，只允许包含相关图片。")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String ref=jo.get("reference").getAsString();
					String[] refs=ref.split(",");
					List<String> links=new ArrayList<>();
					List<String> errors=new ArrayList<>();
					String refss="";
					if(ref.length()>0) {
						try {
							int i=0;
							for(String refImg:refs) {
								if(refImg.length()==72) {
									if(tos.exists(refImg.trim(),state::addUsage)) {
										links.add(tos.getPublicUrl(refImg.trim(),state::addUsage));
										refss+="图"+(++i)+"id:"+refImg+"\n";
									}else {
										errors.add("参考图"+refImg.trim()+"已清理或不存在；");
									}
								} else {
									errors.add("参考图id"+refImg.trim()+"长度不为72；");
								}
								
							}
						} catch (IOException e) {
							e.printStackTrace();
							return "参考图处理失败";
						}
						if(errors.size()>0) {
							StringBuilder sb=new StringBuilder();
							for(String err:errors) {
								sb.append(err);
							}
							return "参数错误："+sb.toString();
						}
					}
					Builder b=AIRequest.builder("sdxlAgent").modelHint("deepseek/pro").taskType(TaskType.STORY).strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM, system);
					b.modelHint("deepseek/pro");
					b.addHistoryItem(Role.USER, refss+jo.get("prompt").getAsString());
						// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
						// true);
						//
					sdxl.addTool(b);
					AIRequest request=b.build();
					callback.sendAsyncTool("create_sxdl_agent", CompletableFuture.supplyAsync(()->{
						try {
							AIOutput output = LLMConnector.call(request);
							FileUtil.printAndCollectContent(output.getReasoner());
							return FileUtil.printAndCollectContent(output.getContent());
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							return "agent调用失败";
						}
						
					}));
					return "agent已创建，请等待完成。";
				}).build());
		return tools;
	}
}
