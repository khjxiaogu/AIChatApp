package com.khjxiaogu.aiwuxia.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.vision.H3VideoApi;
import com.khjxiaogu.aiwuxia.vision.H3VideoUsage;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.voice.ModelType;

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
	/** 单次生成最长等待时间（分钟）：15 秒视频在 16GB 卡上可能跑十几分钟。 */
	private static final long GENERATE_TIMEOUT_MINUTES = 90L;
	/** 参考图上限：1 张首帧，或首帧 + 尾帧。 */
	private static final int MAX_REFERENCE_IMAGES = 2;
	/** 参考视频上限：1 个视频的首尾帧，或两个视频各取一帧。 */
	private static final int MAX_REFERENCE_VIDEOS = 2;
	/** 参考视频/视频截帧的大小上限：视频会以 Base64 走本地模型通道文本报文，需留出报文头余量。 */
	private static final int MAX_REFERENCE_VIDEO_BYTES = 6 * 1024 * 1024;

	/**
	 * 视频生成模式。
	 */
	public enum Mode {
		/** H3 视频制作：int8 主模型，8 步，默认 5 秒，最长 15 秒。 */
		H3(TOOL_GENERATE, "H3 视频制作", H3VideoApi.ModelTier.INT8, 5d, 1d, 15d),
		/** NVFP4 简单动图：NVFP4 主模型，6 步，默认 3 秒，最长 8 秒，画布更小。 */
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

	private final ObjectStorageProvider tos;
	private final List<ToolData> tools = new ArrayList<>();

	/**
	 * @param tos 生成视频的对象存储，可为 null（此时工具返回失败）
	 */
	public H3VideoMcp(ObjectStorageProvider tos) {
		this.tos = tos;
		tools.add(createGenerateTool(tos, true, Mode.H3));
		tools.add(createGenerateTool(tos, true, Mode.NVFP4));
		tools.add(createExtractFrameTool(tos, true));
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
				+ "参考图留空为文生视频；给 1 张作首帧；给 2 张（逗号分隔）作首帧和尾帧。";
		ToolData.Builder builder = new ToolData.Builder(effectiveMode.toolName(), description)
				.putParam("prompt", "视频提示词：描述画面、镜头运动即可，用英文短句。开头是视频风格，中间以[1s-2s]类似的格式表达时间戳和动作，最后是视频统一指导。",
						"提示词")
				.putOptionalParam("reference",
						"参考图对象存储 ID，可留空；1 张=首帧（图生视频），2 张用逗号分隔=首帧+尾帧"
								+ "（尾帧必须与首帧同时给出）。首帧会拉伸到所选画布，建议参考图本身就是目标比例。",
						"参考图")
				.putOptionalParam("video",
						"参考视频对象存储 ID，与 reference 二选一。1 个视频=取它的首帧和末帧；2 个用逗号分隔="
								+ "第一个视频的首帧、第二个视频的末帧。视频会先由本地模型服务用 ComfyUI 截帧，"
								+ "再作为 H3 的首/尾帧。",
						"参考视频")
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
							if (references.size() > MAX_REFERENCE_IMAGES) {
								return ToolResult.text("参数错误：参考图最多 " + MAX_REFERENCE_IMAGES
										+ " 张（首帧、尾帧各一张）。");
							}
							if (referenceVideos.size() > MAX_REFERENCE_VIDEOS) {
								return ToolResult.text("参数错误：参考视频最多 " + MAX_REFERENCE_VIDEOS + " 个。");
							}
							if (!references.isEmpty() && !referenceVideos.isEmpty()) {
								return ToolResult.text("参数错误：reference（图片）与 video（视频）只能二选一。");
							}
							if ((!references.isEmpty() || !referenceVideos.isEmpty()) && tos == null) {
								return ToolResult.failure("视频存储不可用，无法读取参考图/参考视频。");
							}
							int[] size = dimensions(JsonConfig.getNonBlankString(jo, "resolution", "16:9"),
									effectiveMode);
							double seconds = duration(jo, effectiveMode);
							JsonObject request = new JsonObject();
							request.addProperty("action", "video");
							request.addProperty("model", effectiveMode.tier().id());
							request.addProperty("steps", effectiveMode.steps());
							request.addProperty("sparse", true);
							// 全部视频 MCP 禁用音频
							request.addProperty("audio", false);
							request.addProperty("prompt", prompt);
							request.addProperty("width", size[0]);
							request.addProperty("height", size[1]);
							request.addProperty("seconds", seconds);
							request.addProperty("seed", H3VideoApi.randomSeed());
							for (int i = 0; i < references.size(); i++) {
								String id = references.get(i);
								if (!tos.exists(id, output == null ? null : output::addUsage)) {
									return ToolResult.text("参数错误：参考图" + id + "已清理或不存在。");
								}
								byte[] bytes = tos.download(id, output == null ? null : output::addUsage);
								// 视频与图片共用同一套内容哈希 id：视频 id 能通过 exists 检查，
								// 但作为首帧/尾帧提交会让 ComfyUI 收到非图片数据，这里按内容明确拒绝
								if (!LocalStorage.detectMimeType(bytes, id).startsWith("image/")) {
									return ToolResult.text("参数错误：参考图" + id + "不是图片，不能作为视频的首帧/尾帧。");
								}
								JsonObject image = new JsonObject();
								image.addProperty("filename", id + ".png");
								image.addProperty("data", Base64.getEncoder().encodeToString(bytes));
								request.add(i == 0 ? "first_frame" : "last_frame", image);
							}
							for (int i = 0; i < referenceVideos.size(); i++) {
								String id = referenceVideos.get(i);
								if (!tos.exists(id, output == null ? null : output::addUsage)) {
									return ToolResult.text("参数错误：参考视频" + id + "已清理或不存在。");
								}
								byte[] bytes = tos.download(id, output == null ? null : output::addUsage);
								if (bytes.length > MAX_REFERENCE_VIDEO_BYTES) {
									return ToolResult.text("参数错误：参考视频过大（上限 "
											+ (MAX_REFERENCE_VIDEO_BYTES / 1024 / 1024) + "MB）。");
								}
								if (!LocalStorage.detectMimeType(bytes, id).startsWith("video/")) {
									return ToolResult.text("参数错误：参考视频" + id + "不是视频文件。");
								}
								JsonObject video = new JsonObject();
								video.addProperty("filename", id + ".mp4");
								video.addProperty("data", Base64.getEncoder().encodeToString(bytes));
								if (referenceVideos.size() == 1) {
									// 同一个视频：只传一份视频，由本地服务取首帧和末帧
									request.add("first_video", video);
									request.addProperty("use_video_last_frame", true);
								} else if (i == 0) {
									request.add("first_video", video);
								} else {
									request.add("last_video", video);
								}
							}
							List<H3VideoApi.VideoResult> videos = generate(request);
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
				.putParam("video", "视频对象存储 id（72 位哈希，可带扩展名）", "视频")
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
							byte[] videoBytes = tos.download(videoId, output == null ? null : output::addUsage);
							if (videoBytes.length > MAX_REFERENCE_VIDEO_BYTES) {
								return ToolResult.text("参数错误：视频过大（上限 "
										+ (MAX_REFERENCE_VIDEO_BYTES / 1024 / 1024) + "MB）。");
							}
							if (!LocalStorage.detectMimeType(videoBytes, videoId).startsWith("video/")) {
								return ToolResult.text("参数错误：video 不是视频文件。");
							}
							int frame = JsonConfig.getInt(jo, "frame", 0);
							JsonObject request = new JsonObject();
							request.addProperty("action", "extract_frame");
							request.addProperty("frame", frame);
							JsonObject video = new JsonObject();
							video.addProperty("filename", videoId + ".mp4");
							video.addProperty("data", Base64.getEncoder().encodeToString(videoBytes));
							request.add("video", video);
							byte[] imageBytes = extractFrame(request);
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
	 * @param request 请求 JSON（{@code action=extract_frame}）
	 * @return png 字节
	 * @throws Exception 本地模型离线、超时或截帧失败
	 */
	static byte[] extractFrame(JsonObject request) throws Exception {
		long timeoutMillis = TimeUnit.MINUTES.toMillis(30);
		CompletableFuture<ModelGenerationResult> future = LocalModel.require(ModelType.H3,
				UUID.randomUUID().toString(), request, timeoutMillis);
		ModelGenerationResult result = future.get(31, TimeUnit.MINUTES);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("本地 H3 模型没有返回视频帧");
		}
		String format = result.format == null ? "" : result.format.trim().toLowerCase();
		if ("json".equals(format)) {
			JsonObject response = JsonParser
					.parseString(new String(result.bodyData, StandardCharsets.UTF_8))
					.getAsJsonObject();
			String error = JsonConfig.getNonBlankString(response, "error", "");
			if (!error.isEmpty()) {
				throw new IllegalStateException(error);
			}
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
	 * H3（int8）用 864×480 一档的面积；NVFP4 简单动图用 640×352 一档，显存与耗时更低。
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
			return small ? new int[] { 640, 352 } : new int[] { 864, 480 };
		case "9:16":
			return small ? new int[] { 352, 640 } : new int[] { 480, 864 };
		case "1:1":
			return small ? new int[] { 512, 512 } : new int[] { 640, 640 };
		case "4:3":
			return small ? new int[] { 576, 448 } : new int[] { 736, 544 };
		case "3:4":
			return small ? new int[] { 448, 576 } : new int[] { 544, 736 };
		default:
			throw new IllegalArgumentException(
					"不支持的比例 " + key + "，必须是 16:9/9:16/1:1/4:3/3:4 之一");
		}
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
	static List<H3VideoApi.VideoResult> generate(JsonObject request) throws Exception {
		long timeoutMillis = TimeUnit.MINUTES.toMillis(GENERATE_TIMEOUT_MINUTES);
		CompletableFuture<ModelGenerationResult> future = LocalModel.require(ModelType.H3,
				UUID.randomUUID().toString(), request, timeoutMillis);
		// 本地模型通道内部按 timeoutMillis 等待，这里多留一分钟给回传与解码
		ModelGenerationResult result = future.get(GENERATE_TIMEOUT_MINUTES + 1, TimeUnit.MINUTES);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("本地 H3 模型没有返回视频");
		}
		// 新协议：结果以 mp4 原始字节 + 格式回传
		String format = result.format == null ? "" : result.format.trim().toLowerCase();
		if (isVideoFormat(format)) {
			return List.of(new H3VideoApi.VideoResult("h3.mp4", "video", "output", result.bodyData));
		}
		// 兼容旧的 JSON 包裹格式（也用于回传参数错误等 error 字段）
		JsonObject response = JsonParser
				.parseString(new String(result.bodyData, StandardCharsets.UTF_8))
				.getAsJsonObject();
		// 本地模型侧把参数错误、资源锁不足、ComfyUI 失败等放在 error 字段里回传
		String error = JsonConfig.getNonBlankString(response, "error", "");
		if (!error.isEmpty()) {
			throw new IllegalStateException(error);
		}
		JsonElement videos = response.get("videos");
		List<H3VideoApi.VideoResult> results = new ArrayList<>();
		if (videos == null || !videos.isJsonArray()) {
			return results;
		}
		for (JsonElement item : videos.getAsJsonArray()) {
			if (!item.isJsonObject()) {
				continue;
			}
			JsonObject video = item.getAsJsonObject();
			String data = JsonConfig.getNonBlankString(video, "data", "");
			if (data.isEmpty()) {
				continue;
			}
			results.add(new H3VideoApi.VideoResult(
					JsonConfig.getNonBlankString(video, "filename", "h3.mp4"),
					JsonConfig.getNonBlankString(video, "subfolder", "video"),
					JsonConfig.getNonBlankString(video, "type", "output"),
					Base64.getDecoder().decode(data)));
		}
		return results;
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

	/** 兼容旧调用：H3 模式的上传。 */
	private static ToolResult uploadResults(ObjectStorageProvider tos, List<H3VideoApi.VideoResult> videos,
			AIOutput output) throws Exception {
		return uploadResults(tos, videos, output, Mode.H3);
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
