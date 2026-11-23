package com.khjxiaogu.aiwuxia.mcp;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
import com.khjxiaogu.aiwuxia.llm.AttachmentResolver;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.ModelType;

/**
 * 视频生成子 agent 工具。
 * <p>
 * 与 {@link SDXLAgentMcp} 的“出图子 agent”同构：主模型只给一个粗略需求（以及可选参考图），
 * 由子 agent 负责把需求扩写成 H3 提示词、决定画面比例与时长、挑选参考图，再调用内层的
 * {@code h3_gen_video}（{@link H3VideoMcp#createGenerateTool(ObjectStorageProvider, boolean)}
 * 的同步版本）完成生成。
 * </p>
 * <p>
 * 子 agent 可用工具：
 * </p>
 * <ul>
 *   <li>{@code h3_gen_video}：同步生成视频（单次任务内最多 2 次）；</li>
 *   <li>{@code qwen_gen_image}：没有合适参考图时先出一张首帧，再用它做图生视频；</li>
 *   <li>{@code image_recognition}：看懂用户给的参考图；</li>
 *   <li>{@code web_view} / {@code web_search}：查资料；</li>
 *   <li>{@code search_character}：配置了角色设定时按名字查设定。</li>
 * </ul>
 * <p>
 * 子 agent 里的工具调用结果只回到子 agent（文本），视频附件本身不会自动传给主模型，因此本工具
 * 会收集内层 {@code h3_gen_video} 产生的 {@link VideoContent}，在子 agent 结束后作为外层工具
 * 附件返回，由会话统一发送。子 agent 的提示词可放在 {@code config/h3/agent/} 下
 * （{@code role.txt} / {@code charaset.txt} / {@code rules.txt}），缺省时使用内置提示词。
 * </p>
 * <p>
 * 本工具同样<b>不持有资源锁</b>：视频生成排队与显存占用全部由本地模型连接服务负责，
 * 聊天进程只发请求。
 * </p>
 */
public class H3VideoAgentMcp {

	/** 工具名。 */
	public static final String TOOL_AGENT = "h3_video_agent";
	/** 子 agent 自身允许的工具调用轮次上限。 */
	private static final int SUBAGENT_MAX_ROUNDS = 6;
	/** 子 agent 内单次任务最多生成视频次数。 */
	private static final int SUBAGENT_VIDEO_LIMIT = 2;
	/** 子 agent 内最多生成首帧图片次数。 */
	private static final int SUBAGENT_IMAGE_LIMIT = 2;

	/** 未配置 {@code config/h3/agent} 提示词时使用的内置系统提示词。 */
	private static final String DEFAULT_VIDEO_AGENT_PROMPT = "【系统提示】\n"
			+ "你是一名 AI 视频导演（分镜师），负责把用户的粗略需求扩写成 MiniMax H3 可以直接生成的视频提示词，"
			+ "并调用 h3_gen_video（H3 视频制作）或 h3_gen_anim（NVFP4 简单动图）完成生成。以下内容均为虚构。\n"
			+ "\n=== 提示词写法 ===\n"
			+ "1. 在一段中文里写清：画面主体、场景与环境、光线与色调、整体风格、镜头运动（推近/拉远/摇/移/跟随）"
			+ "以及画面节奏。所有视频都不生成音频，不要写声音、对白、音乐或环境音。\n"
			+ "2. 需要分镜时用 [0s-2s] 这样的时间段写法，没有分镜就不要写时间码。\n"
			+ "3. 不要写负面提示词、CFG、步数、采样器、模型名或像素尺寸：H3 没有负面条件，这些参数由代码固定。\n"
			+ "4. 不要引用没有提供给你的参考图编号；只有确实提供了参考图才写“首帧/尾帧”。\n"
			+ "\n=== 模式选择 ===\n"
			+ "1. 用户要“简单动图、短动图、快速预览”或只是让画面轻微动起来时，用 h3_gen_anim"
			+ "（NVFP4，1~8 秒，默认 3 秒，采样步数更少，画布宽高各 +50%）。\n"
			+ "2. 用户要完整视频、较长镜头、首尾帧转场或更高质量时，用 h3_gen_video"
			+ "（int8，1~15 秒，默认 5 秒）。\n"
			+ "3. 只有一个明确视频需求时不要两种模式都跑；每种模式最多生成 2 次视频。\n"
			+ "\n=== 参数选择 ===\n"
			+ "1. resolution：用户没有要求时按内容决定——横屏叙事用 16:9，人物或竖屏内容用 9:16，"
			+ "人物特写可用 3:4，方图或其它用 1:1、4:3。\n"
			+ "2. duration：用户没有要求时，h3_gen_video 默认 5 秒（1~15），h3_gen_anim 默认 3 秒（1~8）；"
			+ "用户明确要求时取范围内最接近的值。\n"
			+ "3. reference：用户给的参考图按顺序是首帧、尾帧，原样把图片 id 传给生成工具，"
			+ "不要改写、不要凭空编造图片 id。\n"
			+ "4. video：用户给的参考视频用 video 字段（与 reference 二选一）：使用视频生视频工作流，"
			+ "1 个视频取片段末帧作首帧（延续生成），2 个视频取第一个的末帧、第二个的首帧；原样传视频 id，不要改写。\n"
			+ "5. first/last：首/尾来源可以自由选择图片或视频（与 reference/video 二选一）。first 是首帧来源，"
			+ "last 是尾帧来源且必须同时给 first；图片直接作帧，视频取片段末帧/首帧。\n"
			+ "\n=== 工具使用 ===\n"
			+ "1. 需要看懂用户给的参考图时，先用 image_recognition；参考视频需要看某一帧时用 video_extract_frame。\n"
			+ "2. 用户要求给已有视频重新配音效时，用 video_foley（保留画面、只换音效）；不要用视频生成工具重画。"
			+ "只改一段时给 start/length（length 最长 30 秒），其余保留原音轨；整段重配可省略。\n"
			+ "3. 用户没给图、但视频需要一个明确的开场画面时，可以先用 qwen_gen_image 生成首帧，"
			+ "再把返回的图片 id 作为生成工具的首帧参考图。\n"
			+ "4. 视频截帧得到的图片 id 也可以作为生成工具的首帧/尾帧；需要单独某一帧时用 video_extract_frame，"
			+ "帧号 0 为首帧、-1 为末帧。\n"
			+ "5. 每种模式最多生成 2 次视频；两次都不满意就停止，把最后一次的视频 id 与描述交给用户判断。\n"
			+ "6. 每次成功生成都会得到视频 id，最终输出必须包含最后一次成功的视频 id。\n"
			+ "\n=== 输出协议 ===\n"
			+ "1. 禁止在输出中提及内部工具名、参数名或任何技术细节。\n"
			+ "2. 输出自然语言，不包含 markdown、列表符号或代码块。\n"
			+ "3. 简明说明视频内容（比例、时长、画面）和视频 id；不要声称生成了声音。\n";

	private H3VideoAgentMcp() {
	}

	/**
	 * 创建视频子 agent 工具。
	 *
	 * @param path   子 agent 配置目录（{@code config/h3/agent}），可为 null
	 * @param tos    对象存储
	 * @param charas 角色提示词，可为 null
	 * @return 独立的工具定义，声明为异步
	 * @throws IOException 读取子 agent 配置失败
	 */
	public static ToolData createVideo(File path, ObjectStorageProvider tos, List<String> charas)
			throws IOException {
		final String system = buildSystemPrompt(path);
		// 首帧工具与生成工具都是无状态的；视频附件按每次调用单独收集
		final ToolData qwenTextToImage = QwenLocalModelMcp.createTextToImageTool(tos);
		return new ToolData.Builder(TOOL_AGENT,
				"调用视频导演子 agent 生成视频：只需给出视频需求的大致描述和参考图，"
						+ "子 agent 会扩写提示词、选择画面比例与时长并完成生成。"
						+ "需求里可以写画面内容、镜头运动、期望时长（秒）和画面比例；"
						+ "子 agent 会在 H3 视频制作（h3_gen_video）与 NVFP4 简单动图（h3_gen_anim）之间选择，"
						+ "首/尾来源可以自由使用图片或视频（first/last），所有视频均无声（禁用音频）。"
						+ "不需要写分辨率、步数等技术参数，这些由代码固定。"
						+ "调用后视频在后台生成（5 秒约 2~5 分钟），完成后会作为附件自动返回对话。")
				.putOptionalParam("reference",
						"参考图列表：图片id（或带扩展名的图片id）用英文逗号分隔，按顺序是首帧、尾帧；没有参考图就留空。",
						"参考图")
				.putOptionalParam("video",
						"参考视频列表：视频id用英文逗号分隔，与 reference 二选一。使用视频生视频工作流："
								+ "1 个视频=取片段末帧作首帧；2 个=第一个的末帧、第二个的首帧；没有参考视频就留空。",
						"参考视频")
				.putOptionalParam("first",
						"首帧来源：图片或视频 id 均可，与 reference/video 二选一。视频取片段末帧，图片直接作首帧。",
						"首帧来源")
				.putOptionalParam("last",
						"尾帧来源：图片或视频 id 均可，必须同时提供 first。视频取片段首帧，图片直接作尾帧。",
						"尾帧来源")
				.putParam("prompt",
						"视频需求，使用中文自然语言描述即可，例如“黄昏天台，女孩转身看向镜头，风声和远处的城市噪音，竖屏 8 秒”。",
						"提示词")
				.presenter(ToolCallPresenter.ofArgument("H3 视频 agent", "prompt"))
				.async()
				.tool((data, output, manager) -> {
					if (!LocalModel.hasOnlineService(ModelType.H3)) {
						return CompletableFuture.completedFuture(ToolResult
								.failure("本地模型服务未连接（H3 通道），请先启动 LocalModelConnectionService。"));
					}
					final JsonObject jo;
					try {
						jo = JsonParser.parseString(data).getAsJsonObject();
					} catch (RuntimeException ex) {
						return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
					}
					String promptText = JsonConfig.getTrimmed(jo, "prompt");
					if (promptText.isEmpty()) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少提示词 prompt。"));
					}
					List<String> references = H3VideoMcp.splitReferences(JsonConfig.getTrimmed(jo, "reference"));
					List<String> referenceVideos = H3VideoMcp.splitReferences(JsonConfig.getTrimmed(jo, "video"));
					String firstSource = JsonConfig.getTrimmed(jo, "first");
					String lastSource = JsonConfig.getTrimmed(jo, "last");
					boolean explicitSources = !firstSource.isEmpty() || !lastSource.isEmpty();
					if (explicitSources && (!references.isEmpty() || !referenceVideos.isEmpty())) {
						return CompletableFuture
								.completedFuture(ToolResult.text("参数错误：first/last 与 reference/video 只能二选一。"));
					}
					if (!lastSource.isEmpty() && firstSource.isEmpty()) {
						return CompletableFuture
								.completedFuture(ToolResult.text("参数错误：提供 last 时必须同时提供 first。"));
					}
					if (references.size() > 2) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：参考图最多 2 张。"));
					}
					if (referenceVideos.size() > 2) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：参考视频最多 2 个。"));
					}
					if (!references.isEmpty() && !referenceVideos.isEmpty()) {
						return CompletableFuture
								.completedFuture(ToolResult.text("参数错误：参考图与参考视频只能二选一；"
										+ "混合首尾来源请用 first/last。"));
					}
					StringBuilder refs = new StringBuilder();
					if (!references.isEmpty() || !referenceVideos.isEmpty() || explicitSources) {
						if (tos == null) {
							return CompletableFuture
									.completedFuture(ToolResult.failure("视频存储不可用，无法读取参考图/视频。"));
						}
						int index = 0;
						for (String id : references) {
							if (!AttachmentResolver.isStorageId(id)) {
								return CompletableFuture.completedFuture(ToolResult
										.text("参数错误：参考图“" + id + "”不是有效的图片id。"));
							}
							if (!tos.exists(id, output == null ? null : output::addUsage)) {
								return CompletableFuture.completedFuture(ToolResult
										.text("参数错误：参考图" + id + "已清理或不存在。"));
							}
							refs.append("图").append(++index).append("id:").append(id).append("\n");
						}
						for (String id : referenceVideos) {
							if (!AttachmentResolver.isStorageId(id)) {
								return CompletableFuture.completedFuture(ToolResult
										.text("参数错误：参考视频“" + id + "”不是有效的视频id。"));
							}
							if (!tos.exists(id, output == null ? null : output::addUsage)) {
								return CompletableFuture.completedFuture(ToolResult
										.text("参数错误：参考视频" + id + "已清理或不存在。"));
							}
							refs.append("视频").append(++index).append("id:").append(id).append("\n");
						}
						if (!firstSource.isEmpty()) {
							if (!AttachmentResolver.isStorageId(firstSource)
									|| !tos.exists(firstSource, output == null ? null : output::addUsage)) {
								return CompletableFuture
										.completedFuture(ToolResult.text("参数错误：first 来源无效或不存在。"));
							}
							refs.append("首帧来源id:").append(firstSource).append("\n");
						}
						if (!lastSource.isEmpty()) {
							if (!AttachmentResolver.isStorageId(lastSource)
									|| !tos.exists(lastSource, output == null ? null : output::addUsage)) {
								return CompletableFuture
										.completedFuture(ToolResult.text("参数错误：last 来源无效或不存在。"));
							}
							refs.append("尾帧来源id:").append(lastSource).append("\n");
						}
					}
					Builder builder = AIRequest.builder("h3VideoAgent").modelHint("deepseek/pro")
							.taskType(TaskType.STORY).strength(ReasoningStrength.MEDIUM)
							.temperature(0.2f).maxTokens(16384);
					builder.addHistoryItem(Role.SYSTEM, system);
					builder.addHistoryItem(Role.USER, refs.toString() + promptText);
					// 每次调用单独收集视频附件，避免上一次调用的结果混进来
					final List<MessageContent> capturedVideos = Collections.synchronizedList(new ArrayList<>());
					// 两种模式都给子 agent；生成视频必须同步等待真实结果
					ToolData h3 = withVideoCapture(
							H3VideoMcp.createGenerateTool(tos, false, H3VideoMcp.Mode.H3), capturedVideos,
							output, H3VideoMcp.Mode.H3);
					ToolData nvfp4 = withVideoCapture(
							H3VideoMcp.createGenerateTool(tos, false, H3VideoMcp.Mode.NVFP4), capturedVideos,
							output, H3VideoMcp.Mode.NVFP4);
					// 视频截帧：可以把视频里的任意一帧转成图片，供生成工具作为首/尾帧
					ToolData extractFrame = H3VideoMcp.createExtractFrameTool(tos, false);
					ToolData foley = H3VideoMcp.createFoleyTool(tos, false);
					ToolCallManager subManager = new ToolCallManager()
							.registerTool(h3)
							.registerTool(nvfp4)
							.registerTool(extractFrame)
							.registerTool(foley)
							.registerTool(qwenTextToImage)
							.setMaxRounds(SUBAGENT_MAX_ROUNDS)
							.setDisableAsyncTasks(true)
							.setToolCallLimit(H3VideoMcp.TOOL_GENERATE, SUBAGENT_VIDEO_LIMIT)
							.setToolCallLimit(H3VideoMcp.TOOL_ANIM, SUBAGENT_VIDEO_LIMIT)
							.setToolCallLimit(QwenImageMcp.TOOL_GENERATE, SUBAGENT_IMAGE_LIMIT);
					subManager.registerTool(MultiModalMcp.createImageRecognition(tos));
					subManager.registerTool(FetchMcp.createWebView(tos));
					ToolData webSearch = DeepseekSearchMcp.createWebSearch();
					if (webSearch != null) {
						subManager.registerTool(webSearch);
					}
					if (charas != null && !charas.isEmpty()) {
						subManager.registerTool(SDXLMcp.createSearchCharacter(charas));
					}
					builder.toolCallManager(subManager);
					final AIRequest request = builder.build();
					return CompletableFuture.supplyAsync(() -> {
						try {
							AIOutput inner = LLMConnector.call(request);
							// 思维链只打印，不进入结果
							FileUtil.printAndCollectContent(inner.getReasoner());
							String text = FileUtil.printAndCollectContent(inner.getContent()).toText().trim();
							if (text.isEmpty()) {
								text = "视频生成任务已结束，但子 agent 没有输出文字说明。";
							}
							synchronized (capturedVideos) {
								if (capturedVideos.isEmpty()) {
									return ToolResult.text(text);
								}
								return ToolResult.of(text, new ArrayList<>(capturedVideos));
							}
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							return ToolResult.text("agent调用失败");
						}
					});
				}).build();
	}

	/**
	 * 组装子 agent 的系统提示词：{@code role.txt} + {@code charaset.txt} + {@code rules.txt}，
	 * 文件都不存在时退回内置提示词，最后追加硬性要求。
	 *
	 * @param path 配置目录，可为 null
	 * @return 完整系统提示词
	 * @throws IOException 读取失败
	 */
	static String buildSystemPrompt(File path) throws IOException {
		String role = readIfExists(path, "role.txt");
		String charaset = readIfExists(path, "charaset.txt");
		String rules = readIfExists(path, "rules.txt");
		StringBuilder sb = new StringBuilder();
		if (role.isEmpty() && rules.isEmpty()) {
			sb.append(DEFAULT_VIDEO_AGENT_PROMPT);
		} else {
			sb.append(role).append(charaset).append(rules);
		}
		sb.append("\n=== 硬性要求 ===\n")
				.append("1. 所有工具调用必须等待真实结果返回后才能继续或结束；禁止提前返回、禁止声称“稍后通知”"
						+ "或把未完成的任务当成完成。\n")
				.append("2. 所有视频一律无声（禁用音频），不要向用户承诺生成声音。\n")
				.append("3. 最终输出必须包含最后一次成功生成的视频 id，你的输出会被抛弃，因此不要向输出说明任何内容，必须执行操作生成视频。\n");
		return sb.toString();
	}

	/** 读取配置目录下的可选文件；文件不存在返回空串。 */
	private static String readIfExists(File path, String name) throws IOException {
		if (path == null) {
			return "";
		}
		File file = new File(path, name);
		if (!file.isFile()) {
			return "";
		}
		return FileUtil.readString(file).replace("\r", "");
	}

	/**
	 * 包装内层生成工具：结果原样返回，同时把视频附件收集起来，供外层工具统一回传；
	 * 成功生成后按对应模式把算力时长计费补记到外层用量上（子 agent 的用量不会自动冒泡）。
	 * <p>
	 * 原工具的可选参数在重建时必须保留“可选”标记，否则子 agent 会被强制传参考图/比例/时长。
	 * </p>
	 *
	 * @param source      内层工具
	 * @param captured    视频附件收集器
	 * @param outerOutput 外层 AIOutput，可为 null
	 * @param mode        模式（决定计费档位与步数）
	 * @return 包装后的工具
	 */
	private static ToolData withVideoCapture(ToolData source, List<MessageContent> captured, AIOutput outerOutput,
			H3VideoMcp.Mode mode) {
		if (source == null) {
			return null;
		}
		ToolData.Builder builder = new ToolData.Builder(source.name, source.description)
				.presenter(source.presenter)
				.async(source.async)
				.maxCalls(source.maxCalls)
				.sessionOnly(source.sessionOnly)
				.orderSensitive(source.orderSensitive);
		for (Map.Entry<String, String> param : source.params.entrySet()) {
			if (source.isOptionalParam(param.getKey())) {
				builder.putOptionalParam(param.getKey(), param.getValue(), source.getParamLabel(param.getKey()));
			} else {
				builder.putParam(param.getKey(), param.getValue(), source.getParamLabel(param.getKey()));
			}
		}
		return builder.tool((data, output, manager) -> source.tool.run(data, output, manager)
				.thenApply(result -> {
					boolean hasVideo = false;
					if (result != null && result.hasAttachments()) {
						for (MessageContent content : result.getAttachments()) {
							if (content instanceof VideoContent) {
								captured.add(content);
								hasVideo = true;
							}
						}
					}
					if (hasVideo && result.isSuccess() && outerOutput != null) {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							outerOutput.addUsage(H3VideoMcp.createUsage(jo, mode));
						} catch (RuntimeException ignored) {
							// 参数解析失败不应影响生成结果返回；计费缺失由上层日志兜底
						}
					}
					return result;
				})).build();
	}

}
