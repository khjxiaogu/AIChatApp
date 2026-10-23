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
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
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
	/** 即梦视频接口允许的参考图数量上限。 */
	private static final int MAX_VIDEO_REFERENCE_IMAGES = 14;
	/** 视频脚本 agent 的工具调用轮次上限。 */
	private static final int VIDEO_AGENT_MAX_ROUNDS = 8;
	/** 未配置即梦视频提示词（{@code config/jimeng-video.txt}）时使用的兜底系统提示词。 */
	private static final String DEFAULT_VIDEO_SYSTEM_PROMPT = "你是一名演出设计师，请根据用户输入写一份15秒的AI视频剧本，使用“图一”“图二”等引用参考图，开头说明每个参考图的指代哪个人物，描述人物时请写全名或者图片编号，禁止使用一切其他代称。输出不含markdown格式，不包含具体时间。视频为日系萌系圆润画风视频，视频对话为全中文，无字幕。输出需要包含”视频风格“、”参考图说明“、”场景“、”剧本“。剧本需要扩写至500字左右，你需要发挥想象力。注意需要删除发型等人物特征，保留服饰等特征。";

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
	 * <p>
	 * 工具接收用户的想法，可选接收非内置的72位图片id；视频脚本交给一个带工具的脚本 agent 产出：
	 * </p>
	 * <ul>
	 *   <li>系统提示词取 {@code config/jimeng-video.txt}（即梦视频提示词），末尾追加内置参考图清单；</li>
	 *   <li>调用者传入的72位图片id先被直接附加，占据图1开始的编号；</li>
	 *   <li>脚本 agent 从清单里自行挑选内置参考图，用附加参考图工具（内置名字或72位图片id）继续往后附加，
	 *       工具返回参考图编号或错误；内置参考图不需要调用者指定；</li>
	 *   <li>脚本 agent 还可以用图片识别工具查看图片id对应的图片；</li>
	 *   <li>脚本 agent 输出整段脚本后，脚本本身作为视频生成提示词，连同全部已附加的参考图一起提交。</li>
	 * </ul>
	 *
	 * @param config       即梦配置
	 * @param refImages    内置参考图
	 * @param tos          对象存储
	 * @param systemPrompt 即梦视频提示词，为空时使用内置兜底提示词
	 * @return 独立的工具定义
	 */
	public static ToolData createVideo(JsonObject config, Map<String, String> refImages, ObjectStorageProvider tos,
			String systemPrompt) {
		JimengVideoGenerator jig = new JimengVideoGenerator(config);
		return createVideo(refImages, tos, systemPrompt, SeedreamMcp::callScriptAgent, jig::generateImage);
	}

	/**
	 * 可替换脚本生成与视频生成的可测重载。
	 * <p>
	 * 除两个网络边界（模型接口、即梦视频接口）由调用方注入外，行为与
	 * {@link #createVideo(JsonObject, Map, ObjectStorageProvider, String)} 完全一致：
	 * 输入解析、外部参考图附加、脚本 agent 请求构造、编号与最终提交都不变。
	 * </p>
	 *
	 * @param refImages       内置参考图
	 * @param tos             对象存储
	 * @param systemPrompt    即梦视频提示词，为空时使用内置兜底提示词
	 * @param scriptGenerator 脚本生成器
	 * @param videoGenerator  视频生成器
	 * @return 独立的工具定义
	 */
	static ToolData createVideo(Map<String, String> refImages, ObjectStorageProvider tos, String systemPrompt,
			ScriptGenerator scriptGenerator, VideoGenerator videoGenerator) {
		String system = buildVideoSystemPrompt(systemPrompt, refImages);
		return new ToolData.Builder("jimeng_video",
				"使用即梦AI生成视频发送。工具会把视频想法交给视频脚本 agent，由它自行挑选并附加内置参考图、写即梦视频脚本并据此生成视频；调用者只需给想法，最多再补充非内置的72位图片id参考图。")
				.putParam("prompt", "用简略清晰的语言描述整个视频的内容梗概，无需详细描述动作场景和角色特征，也不要写参考图。")
				.putParam("reference",
						"可选：对话里出现、且不在内置参考图中的非内置参考图，只填72位图片id，以英文逗号,分隔。这些图会直接附加为图1开始的参考图；内置参考图不用填，由脚本 agent 自行选用。")
				.async()
				.tool((data, output, manager) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String promptText = jo.has("prompt") && !jo.get("prompt").isJsonNull()
							? jo.get("prompt").getAsString().trim()
							: "";
					if (promptText.isEmpty())
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少提示词prompt。"));
					// 本次请求已附加的参考图，顺序即脚本里的图1、图2：外部参考图在前，脚本 agent 附加的内置参考图在后
					List<String> links = new ArrayList<>();
					String externalRefs = jo.has("reference") && !jo.get("reference").isJsonNull()
							? jo.get("reference").getAsString()
							: null;
					String referenceError = attachExternalReferences(externalRefs, tos, links, output);
					if (referenceError != null)
						return CompletableFuture.completedFuture(ToolResult.text(referenceError));
					StringBuilder user = new StringBuilder();
					if (!links.isEmpty()) {
						user.append("调用者已附加").append(links.size()).append("张外部参考图，编号为@图片1到@图片").append(links.size())
								.append("，它们已经在参考图列表里，不需要重复附加，可以用 image_recognition 查看：\n");
						int index = 0;
						for (String refImg : externalRefs.split(",")) {
							String id = refImg.trim();
							if (!id.isEmpty())
								user.append("- @图片").append(++index).append(" 图片id：").append(id).append("\n");
						}
					}
					if (!refImages.isEmpty())
						user.append("内置参考图在系统提示词里，出现里面的人物时先用 attach_reference 附加，附加成功后才能用“@图片1”“@图片2”引用。\n");
					user.append("用户想法：\n").append(promptText);
					ToolCallManager subManager = new ToolCallManager().setMaxRounds(VIDEO_AGENT_MAX_ROUNDS);
					subManager.registerTool(MultiModalMcp.createImageRecognition(tos));
					subManager.registerTool(createAttachReference(refImages, tos, links));
					Builder b = AIRequest.builder("videoAgent").modelHint("deepseek/pro").taskType(TaskType.STORY)
							.strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM, system);
					b.addHistoryItem(Role.USER, user.toString());
					b.toolCallManager(subManager);
					AIRequest request = b.build();
					return scriptGenerator.generate(request).thenCompose(script -> {
						List<String> requestLinks;
						synchronized (links) {
							requestLinks = new ArrayList<>(links);
						}
						// 整段脚本就是视频生成提示词，已附加的参考图按附加顺序提交
						return videoGenerator.generate(requestLinks, script)
								.thenApply(url -> ToolResult.of("视频生成成功", new VideoContent(url)));
					});
				}).build();
	}

	/**
	 * 解析调用者传入的外部参考图（72位图片id），按顺序加进本次请求的参考图列表。
	 * <p>
	 * 内置参考图不由调用者指定，出现非72位的内容直接返回参数错误。
	 * </p>
	 *
	 * @param raw    调用者传入的参考图参数，可为 null
	 * @param tos    对象存储
	 * @param links  本次请求的参考图列表，外部参考图排在最前面
	 * @param output 调用输出，用于记录用量，可为 null
	 * @return 错误文本，成功时返回 null
	 */
	static String attachExternalReferences(String raw, ObjectStorageProvider tos, List<String> links, AIOutput output) {
		if (raw == null)
			return null;
		for (String refImg : raw.split(",")) {
			String id = refImg.trim();
			if (id.isEmpty())
				continue;
			if (id.length() != 72)
				return "参数错误：外部参考图只接受72位图片id（内置参考图由脚本 agent 自行选用），无法识别：" + id;
			if (links.size() >= MAX_VIDEO_REFERENCE_IMAGES)
				return "参数错误：外部参考图超过上限" + MAX_VIDEO_REFERENCE_IMAGES + "张。";
			try {
				if (!tos.exists(id, output == null ? null : output::addUsage))
					return "参数错误：参考图" + id + "已清理或不存在。";
				links.add(tos.getPublicUrl(id, output == null ? null : output::addUsage));
			} catch (IOException e) {
				e.printStackTrace();
				return "参考图处理失败";
			}
		}
		return null;
	}

	/**
	 * 组装视频脚本 agent 的系统提示词。
	 *
	 * @param systemPrompt 即梦视频提示词，为空时使用兜底提示词
	 * @param refImages    内置参考图，其名字追加在提示词末尾供脚本 agent 自行挑选
	 * @return 完整的系统提示词
	 */
	static String buildVideoSystemPrompt(String systemPrompt, Map<String, String> refImages) {
		StringBuilder sb = new StringBuilder();
		if (systemPrompt == null || systemPrompt.trim().isEmpty())
			sb.append(DEFAULT_VIDEO_SYSTEM_PROMPT);
		else
			sb.append(systemPrompt.trim());
		// 视频工具是一次性调用：没有人会回答脚本 agent 的追问，必须直接给出脚本
		sb.append("\n\n本次调用是单向的：没有人会回答你的追问，请直接输出最终脚本，不要提问。");
		if (!refImages.isEmpty()) {
			sb.append("\n\n## 可用的内置参考图（使用前必须先用 attach_reference 附加，图片id也可以用 image_recognition 查看）\n");
			for (String name : refImages.keySet()) {
				sb.append("- ").append(name).append("\n");
			}
		}
		return sb.toString();
	}

	/**
	 * 创建附加参考图工具。
	 * <p>
	 * 工具把一张图片（72位图片id或内置参考图名字）解析成链接加进本次视频请求的参考图列表，
	 * 返回从 1 开始的参考图编号，失败时返回原因。
	 * </p>
	 *
	 * @param refImages 内置参考图
	 * @param tos       对象存储
	 * @param links     本次请求的参考图列表，按附加顺序保存
	 * @return 独立的工具定义
	 */
	static ToolData createAttachReference(Map<String, String> refImages, ObjectStorageProvider tos,
			List<String> links) {
		return new ToolData.Builder("attach_reference",
				"把参考图加入本次视频生成的参考图列表。成功返回参考图编号（从1开始，脚本里用“图1”“图2”引用），失败返回原因。已附加的参考图会随视频请求一起发送。")
				.putParam("reference", "要附加的图片：系统提示词里列出的内置参考图名字，或者72位图片id")
				.maxCalls(MAX_VIDEO_REFERENCE_IMAGES)
				.toolText((data, output, manager) -> {
					String name = readReference(data);
					if (name.isEmpty())
						return "参数错误：缺少reference。";
					String link = refImages.get(name);
					if (link == null) {
						if (name.length() != 72)
							return "参数错误：参考图“" + name + "”不是内置参考图，也不是72位图片id。";
						try {
							if (!tos.exists(name, output == null ? null : output::addUsage))
								return "参考图" + name + "已清理或不存在。";
							link = tos.getPublicUrl(name, output == null ? null : output::addUsage);
						} catch (IOException e) {
							e.printStackTrace();
							return "参考图处理失败";
						}
					}
					synchronized (links) {
						if (links.size() >= MAX_VIDEO_REFERENCE_IMAGES)
							return "参考图数量已达上限" + MAX_VIDEO_REFERENCE_IMAGES + "张，无法继续附加。";
						links.add(link);
						return "已附加为图" + links.size() + "。";
					}
				}).build();
	}

	/** 视频脚本生成器：执行脚本 agent 的请求并返回脚本正文。 */
	@FunctionalInterface
	interface ScriptGenerator {
		CompletableFuture<String> generate(AIRequest request);
	}

	/** 视频生成器：用最终提示词和已附加的参考图链接调用即梦视频接口。 */
	@FunctionalInterface
	interface VideoGenerator {
		CompletableFuture<String> generate(List<String> referenceLinks, String prompt);
	}

	/**
	 * 默认脚本生成器：调用模型接口执行脚本 agent，返回脚本文本。
	 * <p>
	 * 包内可见，测试可以在真实调用前后包一层，用来校验请求内容或替换视频生成。
	 * </p>
	 *
	 * @param request 脚本 agent 请求
	 * @return 脚本文本 future
	 */
	static CompletableFuture<String> callScriptAgent(AIRequest request) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				AIOutput inner = LLMConnector.call(request);
				FileUtil.printAndCollectContent(inner.getReasoner());
				String script = FileUtil.printAndCollectContent(inner.getContent()).toText().trim();
				if (script.isEmpty())
					throw new RuntimeException("视频脚本为空");
				return script;
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				throw new RuntimeException("agent调用失败，请重试", e);
			}
		});
	}

	/**
	 * 从工具参数里取出参考图参数。
	 *
	 * @param data 工具参数 JSON 文本
	 * @return 参考图参数，缺失或无法解析时返回空串
	 */
	private static String readReference(String data) {
		try {
			JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
			if (jo.has("reference") && !jo.get("reference").isJsonNull())
				return jo.get("reference").getAsString().trim();
		} catch (RuntimeException e) {
			// 参数不是合法 JSON 时按缺少参数处理
		}
		return "";
	}
}
