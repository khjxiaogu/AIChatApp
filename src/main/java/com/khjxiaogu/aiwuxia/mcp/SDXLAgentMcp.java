package com.khjxiaogu.aiwuxia.mcp;

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
	public static MCPTools createImage(AISession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,List<String> charas,AgentMessager callback,boolean isNsfw,ResourceLock lock) {
		MCPTools tools=new MCPTools();
		MCPTools sdxl=SDXLMcp.createLocal(state, tos, lora, charas, isNsfw, lock);
		FetchMcp.create(state, tos).addTool(sdxl);
		MultiModalMcp.create(tos, state::addUsage).addTool(sdxl);
		tools.register(new ToolData.Builder("create_sxdl_agent", "调用多模态模型生成图片，每次调用该工具都会创建一个新的无状态subagent。")
				.putParam("reference", "参考图列表，包含多个图片id以英文逗号,分隔，只允许包含相关图片。")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String ref=jo.get("reference").getAsString();
					String[] refs=ref.split(",");
					List<String> links=new ArrayList<>();
					List<String> errors=new ArrayList<>();
					try {
						for(String refImg:refs) {
							if(refImg.length()==72) {
								if(tos.exists(refImg.trim(),state::addUsage)) {
									links.add(tos.getPublicUrl(refImg.trim(),state::addUsage));
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
					Builder b=AIRequest.builder("sdxlAgent").modelHint("deepseek/pro").taskType(TaskType.STORY).strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM, "你是一名演出设计师，请根据用户输入写一份15秒的AI视频剧本，使用“图一”“图二”等引用参考图，开头说明每个参考图的指代哪个人物，描述人物时请写全名或者图片编号，禁止使用一切其他代称。输出不含markdown格式，不包含具体时间。视频为日系萌系圆润画风视频，视频对话为全中文，无字幕。输出需要包含”视频风格“、”参考图说明“、”场景“、”剧本“。剧本需要扩写至500字左右，你需要发挥想象力。注意需要删除发型等人物特征，保留服饰等特征。把产生的图片id和图片描述写在输出。");
					b.modelHint("deepseek/pro");
					b.addHistoryItem(Role.USER, jo.get("prompt").getAsString());
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
