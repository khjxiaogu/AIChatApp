package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelAttachments;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.modelproxy.api.H3Params;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCall;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.vision.FoleyVideoUsage;
import com.khjxiaogu.aiwuxia.vision.H3VideoApi;
import com.khjxiaogu.aiwuxia.vision.H3VideoUsage;

/**
 * MiniMax H3 视频生成的 MCP 工具（本地模型通道）。
 * <p>
 * 提供两种生成模式和一个视频截帧工具，共用同一套“提示词/参考图/参考视频/比例/时长”输入：
 * </p>
 * <ul>
 *   <li>{@link Mode#H3}：{@code h3_gen_video}，int8 主模型，视频制作，8 步，默认 5 秒（1~15）；</li>
 *   <li>{@link Mode#NVFP4}：{@code h3_gen_anim}，NVFP4 主模型，简单动图，6 步，默认 3 秒（1~8）；</li>
 *   <li>{@code video_extract_frame}：从视频截取任意一帧（0 首帧、-1 末帧）。</li>
 * </ul>
 * <p>
 * <b>所有视频 MCP 均禁用音频</b>：工作流不加载音频 VAE、不做音频解码，输出为无声 mp4。
 * 提示词里描述的声音会被忽略。
 * </p>
 * <p>
 * 计费：生成成功后按 {@link H3VideoUsage} 的算力时长公式上报用量，
 * <b>每 1 小时计算时间计 1,000,000 配额</b>。
 * </p>
 * <p>
 * 本工具不持有资源锁：聊天进程只负责发请求，显存与任务排队统一由本地模型连接服务
 * （{@code H3LocalModelHandler} 一侧的 {@code ResourceLock}）负责。
 * </p>
 */
public class H3VideoMcp implements ToolProvider {

	/** H3 视频制作工具名。 */
	public static final String TOOL_GENERATE = "h3_gen_video";
	/** NVFP4 简单动图工具名。 */
	public static final String TOOL_ANIM = "h3_gen_anim";
	/** 视频截帧工具名。 */
	public static final String TOOL_FRAME = "video_extract_frame";
	/** 视频配音（Foley）工具名。 */
	public static final String TOOL_FOLEY = "video_foley";
	/** 单次生成最长等待时间（分钟）：15 秒视频在 16GB 卡上可能跑十几分钟。 */
	private static final long GENERATE_TIMEOUT_MINUTES = 90L;
	/** 参考图上限：1 张首帧，或首帧 + 尾帧。 */
	private static final int MAX_REFERENCE_IMAGES = 2;
	/** 参考视频上限：1 个视频作为首帧来源，或两个视频分别作首/尾帧来源。 */
	private static final int MAX_REFERENCE_VIDEOS = 2;
	/**
	 * NVFP4 画布在基础尺寸上的放大比例：宽高各提高 50%，再按 H3 的 32 倍数向上取整。
	 * <p>以 16:9 为例，基础 640×352 → 960×544；1:1 为 768×768。</p>
	 */
	private static final double NVFP4_RESOLUTION_SCALE = 1.2d;

	/**
	 * 视频生成模式。
	 */
	public enum Mode {
		/** H3 视频制作：int8 主模型，8 步，默认 5 秒，最长 15 秒。 */
		H3(TOOL_GENERATE, "H3 视频制作", H3VideoApi.ModelTier.INT8, 5d, 1d, 15d),
		/** NVFP4 简单动图：NVFP4 主模型，6 步，默认 3 秒，最长 8 秒，画布宽高为基础尺寸的 1.5 倍。 */
		NVFP4(TOOL_ANIM, "NVFP4 简单动图", H3VideoApi.ModelTier.NVFP4, 3d, 1d, 8d);

		private final String toolName;
		private final String displayName;
		private final H3VideoApi.ModelTier tier;
		private final double defaultDuration;
		private final double minDuration;
		private final double maxDuration;

		Mode(String toolName, String displayName, H3VideoApi.ModelTier tier, double defaultDuration,
				double minDuration, double maxDuration) {
			this.toolName = toolName;
			this.displayName = displayName;
			this.tier = tier;
			this.defaultDuration = defaultDuration;
			this.minDuration = minDuration;
			this.maxDuration = maxDuration;
		}

		/** 工具名。 */
		public String toolName() {
			return toolName;
		}

		/** 用户可读名称。 */
		public String displayName() {
			return displayName;
		}

		/** 主模型档位。 */
		public H3VideoApi.ModelTier tier() {
			return tier;
		}

		/** 默认时长（秒）。 */
		public double defaultDuration() {
			return defaultDuration;
		}

		/** 时长下限（秒）。 */
		public double minDuration() {
			return minDuration;
		}

		/** 时长上限（秒）。 */
		public double maxDuration() {
			return maxDuration;
		}

		/** 该模式的固定采样步数。 */
		public int steps() {
			return tier.defaultSteps();
		}
	}

	private final List<ToolData> tools = new ArrayList<>();

	/**
	 * @param tos 生成视频的对象存储，可为 null（此时工具返回失败）
	 */
	public H3VideoMcp(ObjectStorageProvider tos) {
		tools.add(createGenerateTool(tos, true, Mode.H3));
		tools.add(createGenerateTool(tos, true, Mode.NVFP4));
		tools.add(createExtractFrameTool(tos, true));
		tools.add(createFoleyTool(tos, true));
	}

	@Override
	public Collection<ToolData> getTools() {
		return tools;
	}

	/**
	 * 创建 H3 视频制作工具（兼容旧调用）。
	 *
	 * @param tos   对象存储
	 * @param async true 表示异步；false 表示同步等待
	 * @return 工具定义
	 */
	public static ToolData createGenerateTool(ObjectStorageProvider tos, boolean async) {
		return createGenerateTool(tos, async, Mode.H3);
	}

	/**
	 * 按模式创建视频生成工具。
	 * <p>
	 * 独立注册时用异步版本，由工具管理器登记为后台任务；子 agent（见 {@link H3VideoAgentMcp}）
	 * 用同步版本，在调用线程里直接等待生成结果。
	 * </p>
	 *
	 * @param tos   对象存储
	 * @param async true 表示异步；false 表示同步等待
	 * @param mode  模式（H3 视频制作 / NVFP4 简单动图）
	 * @return 工具定义
	 */
	public static ToolData createGenerateTool(ObjectStorageProvider tos, boolean async, Mode mode) {
		final Mode effectiveMode = mode == null ? Mode.H3 : mode;
		String durationHint = (int) effectiveMode.minDuration() + "~" + (int) effectiveMode.maxDuration()
				+ "，默认 " + (int) effectiveMode.defaultDuration();
		String description = "使用本地 MiniMax H3（" + effectiveMode.tier().id()
				+ " 主模型）生成**无声**视频。" + effectiveMode.displayName() + "：只需要提示词、参考图/参考视频、画面比例和时长，"
				+ "尺寸、帧数换算、步数、采样、种子、模型都由代码固定。不生成音频。"
				+ "参考图留空为文生视频；给 1 张作首帧；给 2 张（逗号分隔）作首帧和尾帧。"
				+ "首/尾来源也可以用 first/last 自由选择图片或视频。";
		ToolData.Builder builder = new ToolData.Builder(effectiveMode.toolName(), description)
				.putParam("prompt", "视频提示词：描述画面、镜头运动即可，用英文短句。开头是视频风格，中间以[1s-2s]类似的格式表达时间戳和动作，最后是视频统一指导。",
						"提示词")
				.putOptionalUrlParam("reference",
						"参考图（可留空；1 张=首帧，2 张用逗号分隔=首帧+尾帧）"
								+ "（尾帧必须与首帧同时给出）。首帧会拉伸到所选画布，建议参考图本身就是目标比例。",
						"参考图")
				.putOptionalUrlParam("video",
						"参考视频（与 reference 二选一）。使用 ComfyUI 的视频生视频工作流："
								+ "1 个视频=取所选片段的末帧作为首帧（延续生成）；2 个用逗号分隔="
								+ "第一个视频的末帧 + 第二个视频的首帧，作为中段的首/尾帧。",
						"参考视频")
				.putOptionalUrlParam("first",
						"首帧来源（图片或视频，二选一）。与 reference/video 二选一；"
								+ "视频取片段末帧，图片直接作为首帧。",
						"首帧来源")
				.putOptionalUrlParam("last",
						"尾帧来源（图片或视频，二选一）。必须同时提供 first；"
								+ "视频取片段首帧，图片直接作为尾帧。",
						"尾帧来源")
				.putOptionalParam("resolution",
						"画面比例，必须是 16:9/9:16/1:1/4:3/3:4 之一；默认 16:9（横屏）。", "画面比例")
				.putOptionalParam("duration",
						"视频时长（秒），" + durationHint + "；代码会向上吸附到 H3 的 17k+5 帧格。", "时长（秒）")
				.presenter(ToolCallPresenter.ofArgument(effectiveMode.displayName(), "prompt"))
				.tool((data, output, manager) -> {
					Supplier<ToolResult> work = () -> {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String prompt = JsonConfig.getNonBlankString(jo, "prompt", "");
							if (prompt.isEmpty()) {
								return ToolResult.text("参数错误：缺少提示词 prompt。");
							}
							List<String> references = splitReferences(
									JsonConfig.getNonBlankString(jo, "reference", ""));
							List<String> referenceVideos = splitReferences(
									JsonConfig.getNonBlankString(jo, "video", ""));
							String firstSource = JsonConfig.getNonBlankString(jo, "first", "");
							String lastSource = JsonConfig.getNonBlankString(jo, "last", "");
							boolean explicitSources = !firstSource.isEmpty() || !lastSource.isEmpty();
							if (explicitSources && (!references.isEmpty() || !referenceVideos.isEmpty())) {
								return ToolResult.text("参数错误：first/last 与 reference/video 只能二选一。");
							}
							if (!lastSource.isEmpty() && firstSource.isEmpty()) {
								return ToolResult.text("参数错误：提供 last 时必须同时提供 first。");
							}
							if (references.size() > MAX_REFERENCE_IMAGES) {
								return ToolResult.text("参数错误：参考图最多 " + MAX_REFERENCE_IMAGES
										+ " 张（首帧、尾帧各一张）。");
							}
							if (referenceVideos.size() > MAX_REFERENCE_VIDEOS) {
								return ToolResult.text("参数错误：参考视频最多 " + MAX_REFERENCE_VIDEOS + " 个。");
							}
							if (!references.isEmpty() && !referenceVideos.isEmpty()) {
								return ToolResult.text("参数错误：reference（图片）与 video（视频）只能二选一；"
										+ "混合首尾来源请用 first/last。");
							}
							if ((!references.isEmpty() || !referenceVideos.isEmpty() || explicitSources)
									&& tos == null) {
								return ToolResult.failure("视频存储不可用，无法读取参考图/参考视频。");
							}
							int[] size = dimensions(JsonConfig.getNonBlankString(jo, "resolution", "16:9"),
									effectiveMode);
							double seconds = duration(jo, effectiveMode);
							String reqid = UUID.randomUUID().toString();
							List<AttachmentRef> attachments = new ArrayList<>();
							String firstFrame = null;
							String lastFrame = null;
							String firstVideo = null;
							String lastVideo = null;
							if (explicitSources) {
								AttachmentRef first = registerSource(reqid, attachments, firstSource, tos, output);
								if (first == null) {
									return ToolResult.text("参数错误：首帧来源" + firstSource + "既不是图片也不是视频，或已清理。");
								}
								if (AttachmentRef.KIND_VIDEO.equals(first.kind)) {
									firstVideo = first.id;
								} else {
									firstFrame = first.id;
								}
								if (!lastSource.isEmpty()) {
									AttachmentRef last = registerSource(reqid, attachments, lastSource, tos, output);
									if (last == null) {
										return ToolResult.text("参数错误：尾帧来源" + lastSource + "既不是图片也不是视频，或已清理。");
									}
									if (AttachmentRef.KIND_VIDEO.equals(last.kind)) {
										lastVideo = last.id;
									} else {
										lastFrame = last.id;
									}
								}
							} else {
								for (int i = 0; i < references.size(); i++) {
									String id = references.get(i);
									if (!tos.exists(id, output == null ? null : output::addUsage)) {
										return ToolResult.text("参数错误：参考图" + id + "已清理或不存在。");
									}
									AttachmentRef ref = registerAttachment(reqid, attachments, tos, id,
											AttachmentRef.KIND_IMAGE, "png", id + ".png", null, output);
									if (i == 0) {
										firstFrame = ref.id;
									} else {
										lastFrame = ref.id;
									}
								}
								for (int i = 0; i < referenceVideos.size(); i++) {
									String id = referenceVideos.get(i);
									if (!tos.exists(id, output == null ? null : output::addUsage)) {
										return ToolResult.text("参数错误：参考视频" + id + "已清理或不存在。");
									}
									AttachmentRef ref = registerAttachment(reqid, attachments, tos, id,
											AttachmentRef.KIND_VIDEO, "mp4", id + ".mp4", null, output);
									if (i == 0) {
										firstVideo = ref.id;
									} else {
										lastVideo = ref.id;
									}
								}
							}
							H3Params params = new H3Params("video", prompt, "", effectiveMode.tier().id(),
									effectiveMode.steps(), true, size[0], size[1],
									H3VideoApi.snapFrames(seconds), H3VideoApi.randomSeed(), seconds, firstFrame,
									lastFrame, firstVideo, lastVideo, null, 0, 0, 0, 0, 0, 0d, 0d);
							List<H3VideoApi.VideoResult> videos = generate(
									LocalModelCall.h3(reqid, params, attachments));
							ToolResult result = uploadResults(tos, videos, output, effectiveMode);
							if (result.isSuccess() && output != null) {
								// 成功后按估算算力时长计入配额：每 1 小时 1,000,000 配额
								output.addUsage(createUsage(jo, effectiveMode));
							}
							return result;
						} catch (IllegalArgumentException ex) {
							return ToolResult.text("参数错误：" + ex.getMessage());
						} catch (Throwable ex) {
							return ToolResult.failure("H3 视频生成失败：" + failureMessage(ex));
						}
					};
					return async ? CompletableFuture.supplyAsync(work)
							: CompletableFuture.completedFuture(work.get());
				});
		if (async) {
			builder.async();
		}
		return builder.build();
	}

	/**
	 * 登记一个首/尾来源（图片或视频），返回附件引用。
	 * <p>需要先下载一次判断 mime，字节随附件一起保存，避免客户端拉取时重复下载。</p>
	 *
	 * @param reqid    请求 ID
	 * @param refs     附件列表
	 * @param id       对象存储 id
	 * @param tos      对象存储
	 * @param output   用量输出，可为 null
	 * @return 附件引用；不存在或既不是图片也不是视频时返回 null
	 * @throws IOException 读取失败
	 */
	private static AttachmentRef registerSource(String reqid, List<AttachmentRef> refs, String id,
			ObjectStorageProvider tos, AIOutput output) throws IOException {
		if (!tos.exists(id, output == null ? null : output::addUsage)) {
			return null;
		}
		byte[] bytes = tos.download(id, output == null ? null : output::addUsage);
		String mime = LocalStorage.detectMimeType(bytes, id);
		if (mime.startsWith("image/")) {
			return registerAttachment(reqid, refs, tos, id, AttachmentRef.KIND_IMAGE, "png", id + ".png", bytes,
					output);
		}
		if (mime.startsWith("video/")) {
			return registerAttachment(reqid, refs, tos, id, AttachmentRef.KIND_VIDEO, "mp4", id + ".mp4", bytes,
					output);
		}
		return null;
	}

	/** 把对象存储里的媒体登记成附件；{@code prefetched} 不为 null 时直接复用这份字节。 */
	private static AttachmentRef registerAttachment(String reqid, List<AttachmentRef> refs, ObjectStorageProvider tos,
			String id, String kind, String format, String filename, byte[] prefetched, AIOutput output) {
		AttachmentRef ref = LocalModelAttachments.shared().register(reqid, null, kind, format, filename, id,
				() -> prefetched != null ? prefetched : download(tos, id, output));
		refs.add(ref);
		return ref;
	}

	/** 惰性读取对象存储（附件真正被拉取时才调用）。 */
	private static byte[] download(ObjectStorageProvider tos, String id, AIOutput output) {
		try {
			return tos.download(id, output == null ? null : output::addUsage);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 创建视频配音（HunyuanVideo-Foley）工具。
	 *
	 * @param tos   对象存储
	 * @param async true 表示异步
	 * @return 工具定义
	 */
	public static ToolData createFoleyTool(ObjectStorageProvider tos, boolean async) {
		ToolData.Builder builder = new ToolData.Builder(TOOL_FOLEY,
				"给已有视频重新配音效：保留原画面，生成与画面同步的环境音/动作音，并输出带新音轨的视频，提示词必须是英文。"
						+ "默认整段重配；给 start/length 时只重配该片段，其余保留原音轨（原视频需带音轨）。")
				.putUrlParam("video", "输入视频", "视频")
				.putParam("prompt", "想要的音效描述，例如 footsteps on wet asphalt, distant traffic, wind", "音效提示词")
				.putParam("negative",
						"不想出现的声音，例如 speech, dialogue, human voice, vocals, music。",
						"负面提示词")
				.putOptionalParam("start",
						"局部重配起点（秒），默认 0；只改一段时与 length 一起给。",
						"起点（秒）")
				.putOptionalParam("length",
						"局部重配长度（秒），最长 30 秒；省略则整段重配。",
						"长度（秒）")
				.presenter(ToolCallPresenter.ofArgument("视频配音", "prompt"))
				.tool((data, output, manager) -> {
					Supplier<ToolResult> work = () -> {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String videoId = JsonConfig.getNonBlankString(jo, "video", "");
							String prompt = JsonConfig.getNonBlankString(jo, "prompt", "");
							String negative = JsonConfig.getNonBlankString(jo, "negative", "");
							double start = JsonConfig.getDouble(jo, "start", 0d);
							double length = JsonConfig.getDouble(jo, "length", 0d);
							boolean segment = jo.has("start") || jo.has("length");
							if (segment) {
								if (start < 0d) {
									return ToolResult.text("参数错误：start 不能小于 0。");
								}
								if (length <= 0d) {
									return ToolResult.text("参数错误：局部重配必须提供大于 0 的 length。");
								}
								if (length > 30d) {
									return ToolResult.text("参数错误：单段配音最长 30 秒。");
								}
							}
							if (videoId.isEmpty()) {
								return ToolResult.text("参数错误：缺少视频 video。");
							}
							if (prompt.isEmpty()) {
								return ToolResult.text("参数错误：缺少音效提示词 prompt。");
							}
							if (tos == null) {
								return ToolResult.failure("视频存储不可用，无法读取视频。");
							}
							if (!tos.exists(videoId, output == null ? null : output::addUsage)) {
								return ToolResult.text("参数错误：视频" + videoId + "已清理或不存在。");
							}
							String reqid = UUID.randomUUID().toString();
							List<AttachmentRef> attachments = new ArrayList<>();
							AttachmentRef videoRef = registerAttachment(reqid, attachments, tos, videoId,
									AttachmentRef.KIND_VIDEO, "mp4", videoId + ".mp4", null, output);
							H3Params params = new H3Params(segment ? "foley_segment" : "foley", prompt, negative, "",
									0, false, 0, 0, 0, 0L, 0d, null, null, null, null, videoRef.id, 0, 0, 0, 0, 0,
									start, length);
							// 只调用一次 foley；时长由部署端从 foley 图的 PreviewAny(10) 节点读出，
							// 随结果格式带回，不再额外提交 video_duration 探测任务
							FoleyGeneration generated = generateFoley(LocalModelCall.h3(reqid, params, attachments));
							byte[] result = generated.data;
							double duration = generated.duration > 0d ? generated.duration
									: 10d;
							String id = tos.uploadIfNotExists(result, ".mp4",
									output == null ? null : output::addUsage);
							if (output != null) {
								output.addUsage(new FoleyVideoUsage(duration));
							}
							return ToolResult.of("视频配音完成，视频id为：" + id + "。",
									new VideoContent(id, "视频配音"));
						} catch (IllegalArgumentException ex) {
							return ToolResult.text("参数错误：" + ex.getMessage());
						} catch (Throwable ex) {
							return ToolResult.failure("视频配音失败：" + failureMessage(ex));
						}
					};
					return async ? CompletableFuture.supplyAsync(work)
							: CompletableFuture.completedFuture(work.get());
				});
		if (async) {
			builder.async();
		}
		return builder.build();
	}

	/** 通过本地模型通道执行 Foley 配音，并带回部署端从时长节点读出的时长。 */
	static FoleyGeneration generateFoley(LocalModelCall<H3Params> call) throws Exception {
		long timeoutMillis = TimeUnit.MINUTES.toMillis(60);
		ModelGenerationResult result = LocalModel.lhs.send(null, call, timeoutMillis).get(61, TimeUnit.MINUTES);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("本地模型没有返回配音视频");
		}
		String rawFormat = result.format == null ? "" : result.format.trim();
		String format = rawFormat.toLowerCase();
		double duration = 0d;
		int marker = format.indexOf("duration=");
		if (marker >= 0) {
			int end = format.indexOf(';', marker);
			String value = end >= 0 ? format.substring(marker + 9, end) : format.substring(marker + 9);
			try {
				duration = Double.parseDouble(value.trim());
			} catch (NumberFormatException ignored) {
				duration = 0d;
			}
		}
		return new FoleyGeneration(result.bodyData, duration);
	}

	/** Foley 结果：视频字节 + 部署端从 PreviewAny(10) 读出的时长。 */
	static final class FoleyGeneration {
		final byte[] data;
		final double duration;

		FoleyGeneration(byte[] data, double duration) {
			this.data = data;
			this.duration = duration;
		}
	}

	/**
	 * 创建视频截帧工具：从对象存储的视频里取一帧，保存成图片并返回图片 id。
	 *
	 * @param tos   对象存储
	 * @param async true 表示异步
	 * @return 工具定义
	 */
	public static ToolData createExtractFrameTool(ObjectStorageProvider tos, boolean async) {
		ToolData.Builder builder = new ToolData.Builder(TOOL_FRAME,
				"从视频里截取一帧并保存为图片：0 表示首帧，-1 表示末帧，-2 表示倒数第二帧。"
						+ "截出的图片可以作为 H3 的首帧/尾帧，或用于图片识别。")
				.putUrlParam("video", "输入视频", "视频")
				.putOptionalParam("frame", "帧号，默认 0（首帧）；-1 末帧，-2 倒数第二帧", "帧号")
				.presenter(ToolCallPresenter.ofArgument("视频截帧", "video"))
				.tool((data, output, manager) -> {
					Supplier<ToolResult> work = () -> {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String videoId = JsonConfig.getNonBlankString(jo, "video", "");
							if (videoId.isEmpty()) {
								return ToolResult.text("参数错误：缺少视频 video。");
							}
							if (tos == null) {
								return ToolResult.failure("视频存储不可用，无法读取视频。");
							}
							if (!tos.exists(videoId, output == null ? null : output::addUsage)) {
								return ToolResult.text("参数错误：视频" + videoId + "已清理或不存在。");
							}
							int frame = JsonConfig.getInt(jo, "frame", 0);
							String reqid = UUID.randomUUID().toString();
							List<AttachmentRef> attachments = new ArrayList<>();
							AttachmentRef videoRef = registerAttachment(reqid, attachments, tos, videoId,
									AttachmentRef.KIND_VIDEO, "mp4", videoId + ".mp4", null, output);
							H3Params params = new H3Params("extract_frame", "", "", "", 0, false, 0, 0, 0, 0L, 0d,
									null, null, null, null, videoRef.id, frame, 0, 0, 0, 0, 0d, 0d);
							byte[] imageBytes = extractFrame(LocalModelCall.h3(reqid, params, attachments));
							String imageId = tos.uploadIfNotExists(imageBytes, ".png",
									output == null ? null : output::addUsage);
							return ToolResult.of("视频帧已截取，图片id为：" + imageId + "。",
									new ImageContent(imageId, "视频截帧"));
						} catch (IllegalArgumentException ex) {
							return ToolResult.text("参数错误：" + ex.getMessage());
						} catch (Throwable ex) {
							return ToolResult.failure("视频截帧失败：" + failureMessage(ex));
						}
					};
					return async ? CompletableFuture.supplyAsync(work)
							: CompletableFuture.completedFuture(work.get());
				});
		if (async) {
			builder.async();
		}
		return builder.build();
	}

	/**
	 * 通过本地模型通道执行一次视频截帧。
	 *
	 * @param call 统一调用对象（{@code action=extract_frame} + 视频附件）
	 * @return png 字节
	 * @throws Exception 本地模型离线、超时或截帧失败
	 */
	static byte[] extractFrame(LocalModelCall<H3Params> call) throws Exception {
		long timeoutMillis = TimeUnit.MINUTES.toMillis(30);
		ModelGenerationResult result = LocalModel.lhs.send(null, call, timeoutMillis).get(31, TimeUnit.MINUTES);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("本地 H3 模型没有返回视频帧");
		}
		return result.bodyData;
	}

	/**
	 * 按工具参数构造计费用量。
	 *
	 * @param jo   工具参数
	 * @param mode 模式
	 * @return 用量
	 */
	static H3VideoUsage createUsage(JsonObject jo, Mode mode) {
		Mode effectiveMode = mode == null ? Mode.H3 : mode;
		int[] size = dimensions(JsonConfig.getNonBlankString(jo, "resolution", "16:9"), effectiveMode);
		double seconds = duration(jo, effectiveMode);
		String prompt = JsonConfig.getNonBlankString(jo, "prompt", "");
		return new H3VideoUsage(size[0], size[1], seconds, effectiveMode.steps(), true,
				effectiveMode.tier(), H3VideoUsage.textTokens(prompt));
	}

	/**
	 * 画面比例 → 实际尺寸。全部是 32 的倍数。
	 * <p>
	 * H3（int8）用 864×480 一档的面积；NVFP4 简单动图以 640×352 为基准，
	 * 宽高各提高 50% 并向上对齐到 32 的倍数（16:9=960×544、1:1=768×768）。
	 * </p>
	 *
	 * @param ratio 比例字符串，全角冒号也会被规整
	 * @param mode  模式
	 * @return {@code {width, height}}
	 * @throws IllegalArgumentException 不支持的比例
	 */
	static int[] dimensions(String ratio, Mode mode) {
		String key = ratio == null ? "" : ratio.trim().replace('：', ':');
		boolean small = mode == Mode.NVFP4;
		switch (key) {
		case "16:9":
			return small ? new int[] { nvfp4Size(864), nvfp4Size(480) } : new int[] { 864, 480 };
		case "9:16":
			return small ? new int[] { nvfp4Size(480), nvfp4Size(864) } : new int[] { 480, 864 };
		case "1:1":
			return small ? new int[] { nvfp4Size(640), nvfp4Size(640) } : new int[] { 640, 640 };
		case "4:3":
			return small ? new int[] { nvfp4Size(736), nvfp4Size(544) } : new int[] { 736, 544 };
		case "3:4":
			return small ? new int[] { nvfp4Size(544), nvfp4Size(736) } : new int[] { 544, 736 };
		default:
			throw new IllegalArgumentException(
					"不支持的比例 " + key + "，必须是 16:9/9:16/1:1/4:3/3:4 之一");
		}
	}

	/**
	 * NVFP4 画布尺寸：基础尺寸按 {@link #NVFP4_RESOLUTION_SCALE} 放大，并向上取到
	 * {@link H3VideoApi#SIZE_MULTIPLE} 的倍数。
	 *
	 * @param base 基础尺寸（宽或高）
	 * @return 放大并 32 对齐后的尺寸
	 */
	private static int nvfp4Size(int base) {
		int step = H3VideoApi.SIZE_MULTIPLE;
		return (int) (Math.ceil(base * NVFP4_RESOLUTION_SCALE / step) * step);
	}

	/** 兼容旧调用：H3 模式的尺寸。 */
	static int[] dimensions(String ratio) {
		return dimensions(ratio, Mode.H3);
	}

	/**
	 * 读取并校验时长（秒）。
	 *
	 * @param jo   工具参数
	 * @param mode 模式
	 * @return 秒数
	 * @throws IllegalArgumentException 时长非法
	 */
	static double duration(JsonObject jo, Mode mode) {
		Mode effectiveMode = mode == null ? Mode.H3 : mode;
		String raw = JsonConfig.getNonBlankString(jo, "duration", "");
		if (raw.isEmpty()) {
			return effectiveMode.defaultDuration();
		}
		// 兼容模型把时长写成“6秒”“6s”这种带单位的文本
		String value = raw.replaceFirst("(?i)(秒|s)$", "").trim();
		double parsed;
		try {
			parsed = Double.parseDouble(value);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("duration 必须是秒数，例如 5 或 7.5");
		}
		if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < effectiveMode.minDuration()
				|| parsed > effectiveMode.maxDuration()) {
			throw new IllegalArgumentException("duration 必须在 " + (int) effectiveMode.minDuration() + "~"
					+ (int) effectiveMode.maxDuration() + " 秒之间");
		}
		return parsed;
	}

	/** 兼容旧调用：H3 模式的时长。 */
	static double duration(JsonObject jo) {
		return duration(jo, Mode.H3);
	}

	/**
	 * 通过本地模型通道执行一次 H3 请求。
	 *
	 * @param request 请求 JSON（见 {@link com.khjxiaogu.aiwuxia.vision.H3LocalModelHandler}）
	 * @return 生成的视频
	 * @throws Exception 本地模型离线、超时或生成失败
	 */
	static List<H3VideoApi.VideoResult> generate(LocalModelCall<H3Params> call) throws Exception {
		long timeoutMillis = TimeUnit.MINUTES.toMillis(GENERATE_TIMEOUT_MINUTES);
		// 本地模型通道内部按 timeoutMillis 等待，这里多留一分钟给回传与解码
		ModelGenerationResult result = LocalModel.lhs.send(null, call, timeoutMillis)
				.get(GENERATE_TIMEOUT_MINUTES + 1, TimeUnit.MINUTES);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("本地 H3 模型没有返回视频");
		}
		String format = result.format == null ? "" : result.format.trim().toLowerCase();
		if (!isVideoFormat(format)) {
			throw new IllegalStateException("本地 H3 模型返回了非视频结果：" + format);
		}
		return List.of(new H3VideoApi.VideoResult("h3.mp4", "video", "output", result.bodyData));
	}

	/** 是否是视频格式名（mp4/webm/... 或 video/*）。 */
	private static boolean isVideoFormat(String format) {
		switch (format) {
		case "mp4":
		case "webm":
		case "mov":
		case "mkv":
			return true;
		default:
			return format.startsWith("video/");
		}
	}

	/** 上传视频并把 id 与视频附件写入工具结果。 */
	private static ToolResult uploadResults(ObjectStorageProvider tos, List<H3VideoApi.VideoResult> videos,
			AIOutput output, Mode mode) throws Exception {
		if (videos == null || videos.isEmpty()) {
			return ToolResult.failure("视频生成失败：服务没有返回视频。");
		}
		if (tos == null) {
			return ToolResult.failure("视频生成失败：视频存储不可用。");
		}
		String firstId = null;
		for (H3VideoApi.VideoResult video : videos) {
			if (video == null || video.data == null || video.data.length == 0) {
				continue;
			}
			// 请求 .mp4 后缀：远端对象存储据此识别 MIME；LocalStorage 会去掉媒体后缀并按内容推断 MIME
			String id = tos.uploadIfNotExists(video.data, ".mp4", output == null ? null : output::addUsage);
			if (firstId == null) {
				firstId = id;
			}
		}
		if (firstId == null) {
			return ToolResult.failure("视频生成失败：没有可保存的视频数据。");
		}
		String name = mode == null ? "H3 生成视频" : mode.displayName();
		return ToolResult.of("视频生成成功，视频id为：" + firstId + "。", new VideoContent(firstId, name));
	}

	/** 把底层异常转成便于模型理解的提示。 */
	private static String failureMessage(Throwable ex) {
		String message = QwenImageMcp.rootMessage(ex);
		if (message != null && message.contains("no local model found")) {
			return "本地模型服务未连接（H3 通道），请先启动 LocalModelConnectionService。";
		}
		return message;
	}

	/**
	 * 拆分参考图 ID：英文逗号与中文逗号都接受，去空白后忽略空项。
	 *
	 * @param raw 参考图参数，可为 null
	 * @return 参考图 ID 列表，永不为 null
	 */
	static List<String> splitReferences(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return new ArrayList<>();
		}
		return QwenImageMcp.splitReferences(raw.replace('，', ','));
	}
}
