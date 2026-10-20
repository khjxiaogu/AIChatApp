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
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.vision.JimengImageGenerator;
import com.khjxiaogu.aiwuxia.vision.JimengVideoGenerator;

/**
 * 即梦图片/视频生成工具。
 * <p>
 * 两个工具各自独立注册，不建 Provider 类。两者都声明为异步：工具直接返回生成 future，
 * 由 {@code ToolCallManager} 创建任务并通知会话。
 * </p>
 */
public class SeedreamMcp {

	/**
	 * 创建即梦图片生成工具。
	 *
	 * @param config    即梦配置
	 * @param refImages 内置参考图
	 * @param tos       对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createImage(JsonObject config, Map<String, String> refImages, ObjectStorageProvider tos) {
		JimengImageGenerator jig = new JimengImageGenerator(config);
		return new ToolData.Builder("jimeng_image",
				"使用即梦AI生成图片，注意参考图的图片id或名称不会被传递给模型，因此只能用从1开始的顺序索引指代图片。")
				.putParam("reference", "参考图列表，以英文逗号,分隔后给出，参考图可以是图片id或者系统提供的内置参考图文本，同一个人仅使用一张参考图，参考图数量越少越好。")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”")
				.async()
				.tool((data, output, manager) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String ref = jo.get("reference").getAsString();
					String[] refs = ref.split(",");
					List<String> links = new ArrayList<>();
					List<String> errors = new ArrayList<>();
					try {
						for (String refImg : refs) {
							if (refImg.length() == 72) {
								if (tos.exists(refImg.trim(), output == null ? null : output::addUsage)) {
									links.add(tos.getPublicUrl(refImg.trim(), output == null ? null : output::addUsage));
								} else {
									errors.add("参考图" + refImg.trim() + "已清理或不存在；");
								}
							}
							String link = refImages.get(refImg.trim());
							if (link == null) {
								errors.add("参考图" + refImg.trim() + "不存在；");
							} else
								links.add(link);

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

					CompletableFuture<String> cf = jig.generateImage(links, jo.get("prompt").getAsString())
							.thenApply(t -> {
								try {
									return "图片生成成功，图片id："
											+ tos.uploadIfNotExists(t, output == null ? null : output::addUsage);
								} catch (IOException e) {
									e.printStackTrace();
									throw new RuntimeException(e);
								}
							});
					return cf.thenApply(ToolResult::text);
				}).build();
	}

	/**
	 * 创建即梦视频生成工具。
	 *
	 * @param config    即梦配置
	 * @param refImages 内置参考图
	 * @param tos       对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createVideo(JsonObject config, Map<String, String> refImages, ObjectStorageProvider tos) {
		JimengVideoGenerator jig = new JimengVideoGenerator(config);
		return new ToolData.Builder("jimeng_video",
				"使用即梦AI生成视频发送，注意参考图的图片id或名称不会被传递给模型，因此只能用从1开始的顺序索引指代图片。")
				.putParam("reference", "参考图列表，以英文逗号,分隔后给出，参考图可以是图片id或者系统提供的内置参考图文本，同一个人仅使用一张参考图，参考图数量越少越好。")
				.putParam("prompt", "提示词，使用中文自然语言描述视频内容，台词等信息，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”")
				.async()
				.tool((data, output, manager) -> {
					// 会话上下文（当前 QQ 消息）由 ToolCallManager 携带：工具本身不持有会话
					AIGroupSession.CallContext ctx = manager != null
							&& manager.getContext() instanceof AIGroupSession.CallContext
									? (AIGroupSession.CallContext) manager.getContext()
									: null;
					if (ctx == null || ctx.qq != 1905387052L) {
						return CompletableFuture.completedFuture(ToolResult.text("该回复禁止使用视频生成。"));
					}
					if (!ctx.text.contains("视频")) {
						return CompletableFuture.completedFuture(ToolResult.text("用户回复不包含“视频”，禁止使用。"));
					}

					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String ref = jo.get("reference").getAsString();
					String[] refs = ref.split(",");
					List<String> links = new ArrayList<>();
					List<String> errors = new ArrayList<>();
					try {
						for (String refImg : refs) {
							if (refImg.length() == 72) {
								if (tos.exists(refImg.trim(), output == null ? null : output::addUsage)) {
									links.add(tos.getPublicUrl(refImg.trim(), output == null ? null : output::addUsage));
								} else {
									errors.add("参考图" + refImg.trim() + "已清理或不存在；");
								}
							}
							String link = refImages.get(refImg.trim());
							if (link == null) {
								errors.add("参考图" + refImg.trim() + "不存在；");
							} else
								links.add(link);
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
					Builder b = AIRequest.builder("videoAgent").modelHint("").taskType(TaskType.STORY)
							.strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM,
							"你是一名演出设计师，请根据用户输入写一份15秒的AI视频剧本，使用“图一”“图二”等引用参考图，开头说明每个参考图的指代哪个人物，描述人物时请写全名或者图片编号，禁止使用一切其他代称。输出不含markdown格式，不包含具体时间。视频为日系萌系圆润画风视频，视频对话为全中文，无字幕。输出需要包含”视频风格“、”参考图说明“、”场景“、”剧本“。剧本需要扩写至500字左右，你需要发挥想象力。注意需要删除发型等人物特征，保留服饰等特征。");
					b.modelHint("deepseek/pro");
					b.addHistoryItem(Role.USER, jo.get("prompt").getAsString());
					AIRequest request = b.build();
					CompletableFuture<String> promptFuture = CompletableFuture.supplyAsync(() -> {
						try {
							AIOutput inner = LLMConnector.call(request);
							FileUtil.printAndCollectContent(inner.getReasoner());
							return FileUtil.printAndCollectContent(inner.getContent());
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							throw new RuntimeException("agent调用失败，请重试");
						}
					});
					CompletableFuture<String> videoFuture = promptFuture.thenCompose(
							lprompt -> jig.generateImage(links, "生成一段15秒的日系萌系圆润风格视频，视频内容全中文，无字幕，不改变人物形象。\n" + lprompt));
					return videoFuture.thenApply(url -> ToolResult.of("视频生成成功", new VideoContent(url)));
				}).build();
	}
}
