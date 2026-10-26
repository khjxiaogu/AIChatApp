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
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 出图子 agent 工具。
 * <p>
 * 本工具只提交一次后台出图任务：工具直接返回子 agent 的 future，由工具管理器统一登记为
 * 异步任务并在完成时通知会话。子 agent 的工具由独立的管理器注册，不依赖 {@code MCPTools}。
 * </p>
 */
public class SDXLAgentMcp {
	/** 子 agent 自身允许的工具调用轮次上限。 */
	private static final int SUBAGENT_MAX_ROUNDS = 6;
	/** 子 agent 内单次生成最多出图次数。 */
	private static final int SUBAGENT_IMAGE_LIMIT = 3;
	/** 子 agent 内图生图/分区出图的次数上限。 */
	private static final int SUBAGENT_IMAGE_EDIT_LIMIT = 2;

	/**
	 * 创建出图子 agent 工具。
	 *
	 * @param path   子 agent 配置目录
	 * @param tos    对象存储
	 * @param lora   Lora 配置
	 * @param charas 角色提示词
	 * @param isNsfw 是否允许不宜内容
	 * @param lock   资源锁
	 * @return 独立的工具定义
	 * @throws IOException 读取子 agent 配置失败
	 */
	public static ToolData createImage(File path, ObjectStorageProvider tos, Map<String, LoraConfigurations> lora,
			List<String> charas, boolean isNsfw, ResourceLock lock) throws IOException {
		SDXLMcp sdxl = SDXLMcp.createLocal(tos, lora, isNsfw, lock);

		String system = FileUtil.readString(new File(path, "role.txt")).replace("\r", "")
				+ FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")
				+ FileUtil.readString(new File(path, "rules.txt")).replace("\r", "")
				+ (isNsfw ? ""
						: "\n3.禁止返回被判定为NSFW的图片，如果遇到类似情况你可以多尝试能否生成正常图片，如果两次均不能生成则输出失败和原因。");

		return new ToolData.Builder("sdxl_agent",
				"调用多模态模型生成图片，同一张图片只能有0-2个角色。提示词只需描述画面大致内容即可，不要详细描述画面内容，涉及角色也只需描述角色名字，不要描述角色特征。调用后图片在后台生成，完成后结果会自动返回到对话中。")
				.putParam("reference", "参考图列表，包含多个图片id以英文逗号,分隔，只允许包含相关图片。", "参考图")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”", "提示词")
				.async()
				.tool((data, output, manager) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String promptText = jo.has("prompt") && !jo.get("prompt").isJsonNull()
							? jo.get("prompt").getAsString() : "";
					if (promptText.trim().isEmpty())
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少提示词prompt。"));
					String ref = "";
					if (jo.has("reference") && !jo.get("reference").isJsonNull())
						ref = jo.get("reference").getAsString();
					String[] refs = ref.split(",");
					List<String> links = new ArrayList<>();
					List<String> errors = new ArrayList<>();
					String refss = "";
					if (ref.length() > 0) {
						try {
							int i = 0;
							for (String refImg : refs) {
								if (refImg.length() == 72) {
									if (tos.exists(refImg.trim(), output == null ? null : output::addUsage)) {
										links.add(tos.getPublicUrl(refImg.trim(),
												output == null ? null : output::addUsage));
										refss += "图" + (++i) + "id:" + refImg.trim() + "\n";
									} else {
										errors.add("参考图" + refImg.trim() + "已清理或不存在；");
									}
								} else {
									errors.add("参考图id" + refImg.trim() + "长度不为72；");
								}

							}
						} catch (IOException e) {
							e.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.text("参考图处理失败"));
						}
						if (errors.size() > 0) {
							StringBuilder sb = new StringBuilder();
							for (String err : errors) {
								sb.append(err);
							}
							return CompletableFuture.completedFuture(ToolResult.text("参数错误：" + sb.toString()));
						}
					}
					Builder b = AIRequest.builder("sdxlAgent").modelHint("deepseek/pro").taskType(TaskType.STORY)
							.strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM, system);
					b.addHistoryItem(Role.USER, refss + promptText);
					// 子 agent 的工具由独立管理器提供，图片工具等仍可设置自己的次数上限
					ToolCallManager subManager = new ToolCallManager()
							.registerTools(sdxl)
							.setMaxRounds(SUBAGENT_MAX_ROUNDS)
							.setToolCallLimit("sdxl_gen_image", SUBAGENT_IMAGE_LIMIT)
							.setToolCallLimit("image_to_image", SUBAGENT_IMAGE_EDIT_LIMIT)
							.setToolCallLimit("regional_image", SUBAGENT_IMAGE_EDIT_LIMIT);
					subManager.registerTool(FetchMcp.createWebView(tos));
					subManager.registerTool(FetchMcp.createMoegirlSearch(tos));
					ToolData webSearch = DeepseekSearchMcp.createWebSearch();
					if (webSearch != null) {
						subManager.registerTool(webSearch);
					}
					subManager.registerTool(MultiModalMcp.createImageRecognition(tos));
					if (charas != null) {
						subManager.registerTool(SDXLMcp.createSearchCharacter(charas));
					}
					b.toolCallManager(subManager);
					AIRequest request = b.build();
					return CompletableFuture.supplyAsync(() -> {
						try {
							AIOutput inner = LLMConnector.call(request);
							FileUtil.printAndCollectContent(inner.getReasoner());
							return ToolResult.text(FileUtil.printAndCollectContent(inner.getContent()).toText());
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							return ToolResult.text("agent调用失败");
						}
					});
				}).build();
	}
}
