package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

/**
 * 出图子 agent 的 MCP 工具。
 * <p>
 * 本模块只提供一个 {@code sdxl_agent} 工具：把画面描述交给一个无状态的出图子 agent，
 * 由子 agent 自己组合 SDXL 相关工具（搜索角色、文生图、图生图、分区出图等）完成绘制。
 * </p>
 * <p>
 * 子 agent 的整个生成过程在后台进行，完成后通过 {@link AgentMessager#sendAsyncTool} 把结果送回会话，
 * 因此工具本身只负责“提交任务”。为防止模型把“提交成功”误判为“没有效果”而反复调用，
 * 本模块做了三层限制：
 * </p>
 * <ul>
 *   <li>工具描述与返回值都明确告知“一次请求只需调用一次”；</li>
 *   <li>注册时把父会话中本工具的单次生成调用次数上限设为
 *       {@value #MAX_CALLS_PER_GENERATION}，从调用规则层面兜底；</li>
 *   <li>子 agent 在跑时再次调用直接返回“仍在生成中”，不会新建第二个子 agent。</li>
 * </ul>
 */
public class SDXLAgentMcp {
	/** 本工具在父会话每次生成中的调用次数上限。 */
	private static final int MAX_CALLS_PER_GENERATION = 1;
	/** 子 agent 自身允许的工具调用轮次上限。 */
	private static final int SUBAGENT_MAX_ROUNDS = 6;
	/** 子 agent 内单次生成最多出图次数，避免子 agent 自己也连续出图。 */
	private static final int SUBAGENT_IMAGE_LIMIT = 3;
	/** 子 agent 内图生图/分区出图的次数上限。 */
	private static final int SUBAGENT_IMAGE_EDIT_LIMIT = 2;

	public static MCPTools createImage(File path,AISession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,List<String> charas,AgentMessager callback,boolean isNsfw,ResourceLock lock)  throws IOException {
		MCPTools tools=new MCPTools();
		MCPTools sdxl=SDXLMcp.createLocal(state, tos, lora, charas, isNsfw, lock);
		FetchMcp.create(state, tos).mergeInto(sdxl);
		DeepseekSearchMcp.create(state).mergeInto(sdxl);
		MultiModalMcp.create(tos, state::addUsage).mergeInto(sdxl);

		/*
		 * 是否有出图子 agent 正在后台运行。
		 * 每次调用该工具都会新建一个无状态子 agent 并真的跑完一轮出图，模型看不到中间过程，
		 * 很容易在同一轮生成里反复调用同一条提示词。这里让同一会话同时只跑一个子 agent：
		 * 重复调用直接回“仍在生成中”，结果由正在跑的那个子 agent 送回。
		 */
		final AtomicBoolean inFlight=new AtomicBoolean(false);

		// 调用规则兜底：每次生成最多只允许调用一次本工具
		state.getToolCallManager().setToolCallLimit("sdxl_agent", MAX_CALLS_PER_GENERATION);

		String system = 
				
				FileUtil.readString(new File(path, "role.txt")).replace("\r", "")+
				FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")+
				FileUtil.readString(new File(path, "rules.txt")).replace("\r", "")+
				(isNsfw?"":"\n3.禁止返回被判定为NSFW的图片，如果遇到类似情况你可以多尝试能否生成正常图片，如果两次均不能生成则输出失败和原因。");
		tools.register(new ToolData.Builder("sdxl_agent", "调用多模态模型生成图片，同一张图片只能有0-2个角色。提示词只需描述画面大致内容即可，不要详细描述画面内容，涉及角色也只需描述角色名字，不要描述角色特征。一次请求只需调用一次本工具：调用后图片在后台生成，完成后结果会自动返回到对话中，不要重复调用等待。")
				.putParam("reference", "参考图列表，包含多个图片id以英文逗号,分隔，只允许包含相关图片。")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String promptText=jo.has("prompt")&&!jo.get("prompt").isJsonNull()?jo.get("prompt").getAsString():"";
					if(promptText.trim().isEmpty())
						return "参数错误：缺少提示词prompt。";
					String ref="";
					if(jo.has("reference")&&!jo.get("reference").isJsonNull())
						ref=jo.get("reference").getAsString();
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
										refss+="图"+(++i)+"id:"+refImg.trim()+"\n";
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
					// 已有子 agent 在跑：不再新建，直接告知模型等待（此处已无提前返回，占用后必然会有 future 释放）
					if(!inFlight.compareAndSet(false, true))
						return "上一张图片仍在生成中，请勿重复调用本工具；完成后结果会自动返回到对话中。";
					Builder b=AIRequest.builder("sdxlAgent").modelHint("deepseek/pro").taskType(TaskType.STORY).strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM, system);
					b.addHistoryItem(Role.USER, refss+promptText);
					// 子 agent 的工具由统一的管理器提供：工具表沿用 SDXL 模块，
					// 但轮次与单工具次数都要设上限，避免子 agent 自己也无限出图
					b.toolCallManager(new ToolCallManager()
							.registerTools(sdxl.getTools())
							.setMaxRounds(SUBAGENT_MAX_ROUNDS)
							.setToolCallLimit("sdxl_gen_image", SUBAGENT_IMAGE_LIMIT)
							.setToolCallLimit("image_to_image", SUBAGENT_IMAGE_EDIT_LIMIT)
							.setToolCallLimit("regional_image", SUBAGENT_IMAGE_EDIT_LIMIT));
					AIRequest request=b.build();
					CompletableFuture<String> task=CompletableFuture.supplyAsync(()->{
						try {
							AIOutput output = LLMConnector.call(request);
							FileUtil.printAndCollectContent(output.getReasoner());
							return FileUtil.printAndCollectContent(output.getContent());
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							return "agent调用失败";
						} finally {
							// 无论成功失败都要放开占用，否则一次失败会让本工具此后一直返回“仍在生成中”
							inFlight.set(false);
						}
						
					});
					callback.sendAsyncTool("create_sxdl_agent", task);
					return "已提交生成任务，请勿重复调用本工具；完成后结果会自动返回到对话中。";
				}).build());
		return tools;
	}
}
