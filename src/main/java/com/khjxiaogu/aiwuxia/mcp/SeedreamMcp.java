package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
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
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.vision.JimengImageGenerator;
import com.khjxiaogu.aiwuxia.vision.SeedanceVideoGenerator;

/**
 * 即梦图片/视频生成工具。
 * <p>
 * 两个工具各自独立注册，不建 Provider 类。两者都声明为异步：工具直接返回生成 future，
 * 由 {@code ToolCallManager} 创建任务并通知会话。视频生成使用即梦 Seedance 2.5
 * （火山方舟 Ark API），图片生成仍走原有的即梦图片接口。
 * </p>
 */
public class SeedreamMcp {
	/**
	 * 停止生成开关的工具名。
	 * <p>
	 * 视频脚本 agent 发现用户输入有问题时调用它：本次视频不再提交生成，问题原因直接作为
	 * {@code jimeng_video} 的工具结果返回给调用者。
	 * </p>
	 */
	public static final String REJECT_TOOL_NAME = "reject_request";
	/** 即梦 Seedance 视频接口允许的参考图数量上限（工具侧保持 14 张，避免一次请求过大）。 */
	private static final int MAX_VIDEO_REFERENCE_IMAGES = 14;
	/** 视频脚本 agent 的工具调用轮次上限。 */
	private static final int VIDEO_AGENT_MAX_ROUNDS = 8;
	/** 问题原因的最大长度，避免异常长的输入把工具结果撑爆。 */
	private static final int MAX_REJECT_REASON_LENGTH = 500;
	/** 未配置即梦视频提示词（{@code config/jimeng-video.txt}）时使用的兜底系统提示词。 */
	private static final String DEFAULT_VIDEO_SYSTEM_PROMPT = "你是一名演出设计师，请根据用户输入写一份不超过15秒（默认10秒）的AI视频剧本，画幅默认9:16，使用“图一”“图二”等引用参考图，开头说明每个参考图的指代哪个人物，描述人物时请写全名或者图片编号，禁止使用一切其他代称。输出不含markdown格式，不包含具体时间。视频为日系萌系圆润画风视频，视频对话为全中文，无字幕。输出需要包含”视频风格“、”参考图说明“、”场景“、”剧本“。剧本需要扩写至500字左右，你需要发挥想象力。如果没有额外参考图，按现有参考图的大致意图推测补全；不要虚构图片编号，也不要提问。注意需要删除发型等人物特征，保留服饰等特征。";

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
				.putParam("reference", "参考图列表，以英文逗号,分隔后给出，参考图可以是图片id或者系统提供的内置参考图文本，同一个人仅使用一张参考图，参考图数量越少越好。", "参考图")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”", "提示词")
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
	 * 视频生成接入即梦 Seedance 2.5（火山方舟 Ark API），工具名保持 {@code jimeng_video}
	 * 以兼容既有提示词与配置。工具接收用户的想法，可选接收非内置的72位图片id；视频脚本交给一个带工具的脚本 agent 产出：
	 * </p>
	 * <ul>
	 *   <li>系统提示词取 {@code config/jimeng-video.txt}（即梦视频提示词），末尾追加内置参考图清单；</li>
	 *   <li>调用者传入的72位图片id先被直接附加，占据图1开始的编号；</li>
	 *   <li>脚本 agent 从清单里自行挑选内置参考图，用附加参考图工具（内置名字或72位图片id）继续往后附加，
	 *       工具返回参考图编号或错误；内置参考图不需要调用者指定；</li>
	 *   <li>脚本 agent 还可以用图片识别工具查看图片id对应的图片；</li>
	 *   <li>脚本 agent 还拿到停止生成开关 {@value #REJECT_TOOL_NAME}：发现用户输入有问题时调用它并写清问题原因，
	 *       本次视频不再提交生成，问题原因直接作为工具结果返回给调用者；</li>
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
		SeedanceVideoGenerator generator = new SeedanceVideoGenerator(config);
		String system = buildVideoSystemPrompt(systemPrompt, refImages);
		System.out.println(system);
		if (!generator.isConfigured()) {
			System.out.println("[即梦视频] 未配置 Seedance apiKey，jimeng_video 调用时会失败；"
					+ "请在 config/image-generation.json 中配置 apiKey（火山方舟 API Key）。");
		}
		return createVideo(refImages, tos, systemPrompt, generator.getDuration(), SeedreamMcp::callScriptAgent,
				generator::generateVideo);
	}

	/**
	 * 可替换脚本生成与视频生成的可测重载（默认时长 10 秒）。
	 * <p>
	 * 除两个网络边界（模型接口、即梦视频接口）由调用方注入外，行为与
	 * {@link #createVideo(JsonObject, Map, ObjectStorageProvider, String)} 完全一致：
	 * 输入解析、时长校验、外部参考图附加、脚本 agent 请求构造、编号与最终提交都不变。
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
		return createVideo(refImages, tos, systemPrompt, 10, scriptGenerator, videoGenerator);
	}

	/**
	 * 可替换脚本生成与视频生成、并指定默认时长的可测重载。
	 *
	 * @param refImages       内置参考图
	 * @param tos             对象存储
	 * @param systemPrompt    即梦视频提示词，为空时使用内置兜底提示词
	 * @param defaultDuration 调用者未指定 duration 时使用的默认秒数
	 * @param scriptGenerator 脚本生成器
	 * @param videoGenerator  视频生成器
	 * @return 独立的工具定义
	 */
	static ToolData createVideo(Map<String, String> refImages, ObjectStorageProvider tos, String systemPrompt,
			int defaultDuration, ScriptGenerator scriptGenerator, VideoGenerator videoGenerator) {
		final int fallbackDuration = Math.max(4, Math.min(15, defaultDuration > 0 ? defaultDuration : 10));
		String system = buildVideoSystemPrompt(systemPrompt, refImages);
		return new ToolData.Builder("jimeng_video",
				"使用即梦 Seedance 2.5 生成视频发送。工具会把视频想法交给视频脚本 agent，由它自行挑选并附加内置参考图、写即梦视频脚本并据此生成视频；调用者只需给想法，最多再补充非内置的72位图片id参考图，并可指定 4-15 秒的视频时长。如果脚本 agent 发现想法本身存在严重问题（违法违规、涉及真实人物隐私、需求自相矛盾等），工具会停止生成并直接返回问题原因，此时请把原因转告用户，不要原样重试。")
				.putParam("prompt", "用简略清晰的语言描述整个视频的内容梗概，无需详细描述动作场景和角色特征，也不要写参考图。", "视频想法")
				.putParam("reference",
						"可选：对话里出现、且不在内置参考图中的非内置参考图，只填72位图片id，以英文逗号,分隔。这些图会直接附加为图1开始的参考图；内置参考图不用填，由脚本 agent 自行选用。",
						"参考图")
				.putParam("duration", "可选：视频时长（秒），4-15 的整数，默认 " + fallbackDuration
						+ " 秒；这个时长会写进脚本要求并提交给视频接口。", "视频时长")
				.async()
				.tool((data, output, manager) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String promptText = jo.has("prompt") && !jo.get("prompt").isJsonNull()
							? jo.get("prompt").getAsString().trim()
							: "";
					if (promptText.isEmpty())
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少提示词prompt。"));
					int duration = readDuration(jo, fallbackDuration);
					if (duration < 0)
						return CompletableFuture.completedFuture(
								ToolResult.text("参数错误：duration 必须是 4-15 的整数（秒）。"));
					// 本次请求已附加的参考图，顺序即脚本里的图1、图2：外部参考图在前，脚本 agent 附加的内置参考图在后
					List<String> links = new ArrayList<>();
					String externalRefs = jo.has("reference") && !jo.get("reference").isJsonNull()
							? jo.get("reference").getAsString()
							: null;
					String referenceError = attachExternalReferences(externalRefs, tos, links, output);
					if (referenceError != null)
						return CompletableFuture.completedFuture(ToolResult.text(referenceError));
					StringBuilder user = new StringBuilder();
					user.append("本次视频时长：").append(duration).append(" 秒；脚本必须按这个时长写，时间戳不要超过 ")
							.append(duration).append(" 秒。\n");
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
					// 输入检查开关：脚本 agent 发现用户输入有问题时合上它，本次不再提交视频生成
					AtomicReference<String> rejection = new AtomicReference<>();
					ToolCallManager subManager = new ToolCallManager().setMaxRounds(VIDEO_AGENT_MAX_ROUNDS);
					subManager.registerTool(MultiModalMcp.createImageRecognition(tos));
					subManager.registerTool(createAttachReference(refImages, tos, links));
					subManager.registerTool(createRejectRequest(rejection));
					Builder b = AIRequest.builder("videoAgent").modelHint("deepseek/pro").taskType(TaskType.STORY)
							.strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(384000);
					b.addHistoryItem(Role.SYSTEM, system);
					b.addHistoryItem(Role.USER, user.toString());
					b.toolCallManager(subManager);
					AIRequest request = b.build();
					return scriptGenerator.generate(request).handle((script, error) -> {
						// 开关已合上：不提交视频接口，把问题原因作为工具结果回传给调用者。
						// 脚本 agent 被停下时可能没产出脚本（甚至因此报“视频脚本为空”），
						// 这类失败由问题原因接管，不再向上抛。
						String rejected = rejection.get();
						if (rejected != null)
							return CompletableFuture.<ToolResult>completedFuture(ToolResult.text(rejectedResult(rejected)));
						if (error != null)
							throw new CompletionException(error instanceof CompletionException && error.getCause() != null
									? error.getCause()
									: error);
						List<String> requestLinks;
						synchronized (links) {
							requestLinks = new ArrayList<>(links);
						}
						// 整段脚本就是视频生成提示词，已附加的参考图按附加顺序提交，时长用本次调用校验过的值；
						// 成功生成后由视频客户端回报 Seedance 视频用量，计入当前 AIOutput 的配额
						return videoGenerator.generate(requestLinks, script, duration,
								output == null ? null : output::addUsage)
								.thenApply(url -> ToolResult.of("视频生成成功", new VideoContent(url)));
					}).thenCompose(future -> future);
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
		// 视频工具是一次性调用：没有人会回答脚本 agent 的追问，必须自行推测并直接给出脚本
		sb.append("\n\n本次调用是单向的：没有人会回答你的追问，请根据现有信息自行推测"
				+ "（参考图不足时按现有参考图的大致意图补全），直接输出最终脚本，不要提问。");
		// 输入检查开关：判断输入该拒绝时不要用脚本内容表达拒绝，而是合上开关让本次生成停下
		sb.append("\n\n## 停止生成开关（").append(REJECT_TOOL_NAME).append("）\n")
				.append("发现用户输入存在无法自行推测解决的严重问题时（内容违法违规、涉及真实人物或隐私、需求自相矛盾、")
				.append("参考图与需求明显冲突、信息少到写不出脚本等），请调用 ").append(REJECT_TOOL_NAME)
				.append(" 并在 reason 里写清问题原因，然后立即结束本次输出：本次视频会因此停止生成，")
				.append("问题原因会直接返回给调用者，不会被当成视频脚本提交。需要拒绝时不要用脚本正文表达拒绝。")
				.append("只是不够具体、可以自行补全的信息不要调用本工具。\n");
		if (!refImages.isEmpty()) {
			sb.append("\n\n## 可用的内置参考图（使用前必须先用 attach_reference 附加，图片id也可以用 image_recognition 查看）\n");
			for (String name : refImages.keySet()) {
				sb.append("- ").append(name).append("\n");
			}
			sb.append("\n只要涉及到参考图中的人物或者服装，就要选择对应参考图并提供说明\n");
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
				.putParam("reference", "要附加的图片：系统提示词里列出的内置参考图名字，或者72位图片id", "参考图")
				.putParam("description", "参考图的用途说明", "参考图用途")
				.maxCalls(MAX_VIDEO_REFERENCE_IMAGES)
				.toolText((data, output, manager) -> {
					String name = readReference(data);
					if (name.isEmpty())
						return "参数错误：缺少reference。";
					String link = refImages.get(name);
					System.out.println(readDescription(data));
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

	/**
	 * 创建停止生成开关工具。
	 * <p>
	 * 脚本 agent 发现用户输入有问题时调用本工具，并把问题原因写进 {@code reason}。工具做三件事：
	 * </p>
	 * <ol>
	 *   <li>把问题原因记进 {@code rejection}（只记第一次），{@code createVideo} 据此跳过视频生成；</li>
	 *   <li>中断脚本 agent 的输出：不再发起新的模型请求，本批后续的工具调用也不再执行；</li>
	 *   <li>回传“已停止”，告诉模型不用再输出脚本。</li>
	 * </ol>
	 * <p>
	 * 声明为顺序屏障：它执行前先让本批此前的附加参考图等调用跑完，之后才判断是否停下，
	 * 因此不会出现“刚停止又附加了一张参考图”的中间状态。
	 * </p>
	 *
	 * @param rejection 问题原因接收方，第一次写入生效
	 * @return 独立的工具定义
	 */
	static ToolData createRejectRequest(AtomicReference<String> rejection) {
		return new ToolData.Builder(REJECT_TOOL_NAME,
				"停止本次视频生成，并把用户输入存在的问题原因回传给调用者。只在发现用户输入存在无法自行推测解决的严重问题时调用，"
						+ "例如内容违法违规、涉及真实人物或隐私、需求自相矛盾、参考图与需求明显冲突、信息少到写不出脚本；"
						+ "调用后本次视频不会提交生成，问题原因会直接返回给调用者，因此必须写清 reason。"
						+ "只是不够具体的信息请自行推测补全，不要调用本工具。")
				.putParam("reason", "用户输入存在的问题原因（中文，写清具体问题，便于转告用户）", "问题原因")
				.maxCalls(1)
				.orderSensitive()
				.tool((data, output, manager) -> {
					String reason = readRejectReason(data);
					if (reason.isEmpty())
						return CompletableFuture
								.completedFuture(ToolResult.text("参数错误：缺少问题原因 reason，本次视频生成继续。"));
					rejection.compareAndSet(null, reason);
					// 开关已合上：停止脚本 agent 的后续轮次，同一批里后面的工具调用也随之作废
					if (output != null)
						output.interrupt();
					return CompletableFuture
							.completedFuture(ToolResult.text("已停止本次视频生成，问题原因已回传给调用者；不要继续输出脚本。"));
				}).build();
	}

	/**
	 * 开关合上后回传给调用者的工具结果文本。
	 *
	 * @param reason 脚本 agent 给出的问题原因
	 * @return 工具结果文本
	 */
	static String rejectedResult(String reason) {
		return "视频生成已停止：用户输入存在问题（" + reason + "）。请把问题原因转告用户，不要原样重试。";
	}

	/**
	 * 从工具参数里取出问题原因。
	 *
	 * @param data 工具参数 JSON 文本
	 * @return 问题原因（过长时截断）；缺失、为空或无法解析时返回空串
	 */
	static String readRejectReason(String data) {
		try {
			JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
			if (jo.has("reason") && !jo.get("reason").isJsonNull()) {
				String reason = jo.get("reason").getAsString().trim();
				return reason.length() > MAX_REJECT_REASON_LENGTH
						? reason.substring(0, MAX_REJECT_REASON_LENGTH) + "…"
						: reason;
			}
		} catch (RuntimeException e) {
			// 参数不是合法 JSON 时按缺少参数处理
		}
		return "";
	}

	/** 视频脚本生成器：执行脚本 agent 的请求并返回脚本正文。 */
	@FunctionalInterface
	interface ScriptGenerator {
		CompletableFuture<String> generate(AIRequest request);
	}

	/** 视频生成器：用最终提示词、已附加的参考图链接、本次时长调用即梦视频接口，并回报用量。 */
	@FunctionalInterface
	interface VideoGenerator {
		CompletableFuture<String> generate(List<String> referenceLinks, String prompt, int duration,
				Consumer<UsageIntf<?>> usage);
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
				String reasoning = FileUtil.printAndCollectContent(inner.getReasoner()).toText().trim();
				String script = FileUtil.printAndCollectContent(inner.getContent()).toText().trim();
				if (script.isEmpty())
					throw new RuntimeException("视频脚本为空（模型 " + modelName(request)
							+ "，maxTokens " + request.maxToken + "，思维链 " + reasoning.length() + " 字，用量 "
							+ inner.getUsages() + "）；模型调用已结束但正文为空");
				return script;
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				throw new RuntimeException("agent调用失败，请重试", e);
			}
		});
	}

	/** 请求里声明的模型名，用于空正文诊断。 */
	private static String modelName(AIRequest request) {
		return request.modelHint == null || request.modelHint.length == 0 ? "默认"
				: String.join("/", request.modelHint);
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
	/**
	 * 读取并校验工具参数里的视频时长。
	 * <p>
	 * 缺失或空串使用默认时长；非数字、非整数、超出 4-15 秒返回 -1。
	 * </p>
	 *
	 * @param jo       工具参数
	 * @param fallback 默认时长
	 * @return 校验后的秒数；非法返回 -1
	 */
	static int readDuration(JsonObject jo, int fallback) {
		if (jo == null || !jo.has("duration") || jo.get("duration").isJsonNull())
			return fallback;
		JsonElement element = jo.get("duration");
		if (!element.isJsonPrimitive())
			return -1;
		try {
			String raw = element.getAsString().trim();
			// 兼容模型把时长写成“8秒”“8s”这种带单位的文本
			raw = raw.replaceFirst("(?i)(秒|s)$", "").trim();
			if (raw.isEmpty())
				return fallback;
			double parsed = Double.parseDouble(raw);
			int value = (int) parsed;
			if (parsed != value || value < 4 || value > 15)
				return -1;
			return value;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static String readDescription(String data) {
		try {
			JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
			if (jo.has("description") && !jo.get("description").isJsonNull())
				return jo.get("description").getAsString().trim();
		} catch (RuntimeException e) {
			// 参数不是合法 JSON 时按缺少参数处理
		}
		return "";
	}
}
