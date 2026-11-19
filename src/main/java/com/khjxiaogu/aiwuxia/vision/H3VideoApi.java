package com.khjxiaogu.aiwuxia.vision;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;

/**
 * MiniMax H3（ComfyUI REST API）视频生成客户端。
 * <p>
 * 与 {@link QwenImageApi} 访问同一个 ComfyUI（默认 {@code http://127.0.0.1:8188}），只是工作流图不同：
 * </p>
 * <ul>
 *   <li>{@link #textToVideo(String, int, int, int, long, boolean) 文生视频}；</li>
 *   <li>{@link #video(String, int, int, int, long, InputImage, InputImage, boolean) 图生视频 /
 *       首尾帧视频}：给首帧为图生视频，再给尾帧为首尾帧插值。</li>
 * </ul>
 * <p>
 * 参数约定与 REST-API.md 一致：宽高是 <b>32</b> 的倍数，帧数必须是 <b>17k+5</b>（24fps，
 * 124≈5 秒、243≈10 秒、362≈15 秒），int8 档默认 8 步、NVFP4 档默认 6 步，采样器 {@code euler} +
 * {@code simple}，走 {@code BasicGuider}，因此<b>没有 CFG、也没有负面条件</b>。
 * 稀疏注意力（{@code BlockSparseAttention}/sol-attn）默认开启；<b>默认不生成音频</b>。
 * </p>
 * <p>
 * 调用流程：上传首/尾帧 → 提交前调用 {@code /free} 挂“任务结束后卸载模型”标记 →
 * POST {@code /prompt} → 轮询 {@code /history/{prompt_id}} → GET {@code /view} 下载 mp4。
 * 资源占用：int8 档先等到至少 8 份，再一次性占满当前全部剩余资源；NVFP4 档固定占 10 份，
 * 任务期间其它本地模型（Qwen / SDXL 等）都会让路。
 * </p>
 * <p>
 * 地址取 {@code -Dh3VideoUrl}，未配置时退回 {@code -DqwenImageUrl}（同一个 ComfyUI）、
 * 环境变量 {@code H3_VIDEO_API} / {@code QWEN_API}，最后落到
 * {@link QwenImageApi#DEFAULT_BASE_URL}。
 * </p>
 */
public class H3VideoApi {

	/** 默认 ComfyUI 地址（与 Qwen Image 共用）。 */
	public static final String DEFAULT_BASE_URL = QwenImageApi.DEFAULT_BASE_URL;
	/** 默认 H3 UNet（10Eros turbo 蒸馏版，int8）。 */
	public static final String DEFAULT_UNET = "10Eros_Max_h3_TURBO-hybrid_beta4_int8_convrot.safetensors";
	/** NVFP4 主模型（10.86 GiB，仅 RTX 50 系 / torch ≥ 2.8）。 */
	public static final String NVFP4_UNET = "10Eros_Max_H3_FL2VA-NVFP4.safetensors";
	/** 默认文本编码器。 */
	public static final String DEFAULT_CLIP = "qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors";
	/** 默认视频 VAE。 */
	public static final String DEFAULT_VAE = "minimax_h3_video_vae_int8_convrot.safetensors";
	/** 默认音频 VAE（仅 {@code audioEnabled=true} 时加载；视频 MCP 默认禁用音频）。 */
	public static final String DEFAULT_AUDIO_VAE = "minimax_h3_audio_vae_fp32.safetensors";
	/** H3 输出帧率。 */
	public static final int FPS = 24;
	/** 默认采样步数（int8 档；NVFP4 档默认 6，见 {@link ModelTier#defaultSteps()}）。 */
	public static final int DEFAULT_STEPS = 8;
	/** 固定采样器。 */
	public static final String SAMPLER = "euler";
	/** 固定调度器。 */
	public static final String SCHEDULER = "simple";
	/** 帧数网格间隔：帧数必须满足 {@code frames % 17 == 5}。 */
	public static final int FRAME_STEP = 17;
	/** 帧数网格偏移。 */
	public static final int FRAME_OFFSET = 5;
	/** 输出目录/前缀（ComfyUI 的 output/video 下）。 */
	public static final String DEFAULT_FILENAME_PREFIX = "video/MiniMax-H3";
	/** 宽高必须是 32 的倍数。 */
	public static final int SIZE_MULTIPLE = 32;
	/** H3 的最低资源要求：少于这个份数不允许开始生成（H3 权重约 19.5GB）。 */
	public static final int MIN_RESOURCE_AMOUNT = 8;
	/** NVFP4 档运行时固定占用的资源份数。 */
	public static final int NVFP4_RESOURCE_AMOUNT = 10;
	/** 稀疏注意力默认按“保留 10% block + 3% sink”。 */
	public static final double SPARSE_FACTOR = 0.13;
	/** 序列小于该 token 数时稀疏注意力自动退回稠密。 */
	public static final int SPARSE_MIN_TOKENS = 12288;

	/**
	 * 主模型档位。
	 * <p>
	 * 两档除主模型文件不同外，算力/加载量也不同（用于计费估算）：
	 * </p>
	 * <ul>
	 *   <li>{@link #INT8}：19.53GB 主模型，单步 a=4.94e-4、b=3.86e-8，默认 8 步；</li>
	 *   <li>{@link #NVFP4}：10.86GB 主模型，单步 a=6.10e-4、b=4.80e-8，默认 6 步，运行时占 10 份资源。</li>
	 * </ul>
	 */
	public enum ModelTier {
		/** int8 主模型（H3 视频制作）。 */
		INT8("int8", DEFAULT_UNET, 4.94e-4, 3.86e-8, 38.42, 8, 0),
		/** NVFP4 主模型（简单动图）。 */
		NVFP4("nvfp4", NVFP4_UNET, 6.10e-4, 4.80e-8, 30.55, 6, NVFP4_RESOURCE_AMOUNT);

		private final String id;
		private final String unetName;
		/** 单步耗时的线性项系数 a（秒/token）。 */
		private final double stepLinear;
		/** 单步耗时的二次项系数 b（秒/token²）。 */
		private final double stepQuadratic;
		private final double loadGb;
		private final int defaultSteps;
		private final int resourceAmount;

		ModelTier(String id, String unetName, double stepLinear, double stepQuadratic, double loadGb,
				int defaultSteps, int resourceAmount) {
			this.id = id;
			this.unetName = unetName;
			this.stepLinear = stepLinear;
			this.stepQuadratic = stepQuadratic;
			this.loadGb = loadGb;
			this.defaultSteps = defaultSteps;
			this.resourceAmount = resourceAmount;
		}

		/** 报文里的档位名（{@code int8} / {@code nvfp4}）。 */
		public String id() {
			return id;
		}

		/** 主模型文件名。 */
		public String unetName() {
			return unetName;
		}

		/** 单步耗时线性项系数 a，见 {@code H3VideoUsage}。 */
		public double stepLinear() {
			return stepLinear;
		}

		/** 单步耗时二次项系数 b，见 {@code H3VideoUsage}。 */
		public double stepQuadratic() {
			return stepQuadratic;
		}

		/** 整条任务需要读入的总权重（GB），用于估算冷加载时间。 */
		public double loadGb() {
			return loadGb;
		}

		/** 该档位默认采样步数。 */
		public int defaultSteps() {
			return defaultSteps;
		}

		/**
		 * 该档位固定占用的资源份数；0 表示“至少 {@value #MIN_RESOURCE_AMOUNT} 份后占满剩余”。
		 */
		public int resourceAmount() {
			return resourceAmount;
		}

		/** 解析档位名；空或未知时退回 {@link #INT8}。 */
		public static ModelTier fromString(String value) {
			if (value == null) {
				return INT8;
			}
			String normalized = value.trim().toLowerCase();
			if ("nvfp4".equals(normalized) || "fp4".equals(normalized)) {
				return NVFP4;
			}
			return INT8;
		}
	}

	/**
	 * 默认资源占用：0 表示“先等到至少 {@value #MIN_RESOURCE_AMOUNT} 份，再一次性占满当前全部剩余资源”。
	 * <p>
	 * 显式 {@link #setResourceLock(ResourceLock, int)} 传正数时改为固定份数。
	 * </p>
	 */
	public static final int DEFAULT_RESOURCE_AMOUNT = 0;

	/** 服务地址（不含末尾斜杠）。 */
	private final String baseUrl;
	/** 首/尾帧上传子目录。 */
	private String uploadSubfolder = "h3";
	/** 等待历史的超时时间：视频 8 步 / 15 秒在 16GB 卡上可能超过 10 分钟。 */
	private long timeoutMillis = 90 * 60 * 1000L;
	/** 轮询间隔。 */
	private long pollIntervalMillis = 3000L;
	/** 模型文件名，可按部署的量化档位覆盖。 */
	private String unetName = DEFAULT_UNET;
	private String clipName = DEFAULT_CLIP;
	private String vaeName = DEFAULT_VAE;
	private String audioVaeName = DEFAULT_AUDIO_VAE;
	private String filenamePrefix = DEFAULT_FILENAME_PREFIX;
	/** 当前主模型档位。 */
	private ModelTier modelTier = ModelTier.INT8;
	/** 采样步数；默认跟随档位。 */
	private int steps = DEFAULT_STEPS;
	/** 是否启用稀疏注意力（sol-attn）；默认开。 */
	private boolean sparse = true;
	/** 是否生成音频；视频 MCP 默认禁用，保持 false。 */
	private boolean audioEnabled = false;
	/** 可选资源锁：非 null 时每次生成/上传都先占用资源，避免与其它本地模型并发抢显存。 */
	private ResourceLock resourceLock;
	/** 每次操作占用的资源量。 */
	private int resourceAmount = DEFAULT_RESOURCE_AMOUNT;
	/** 可选的进度回调：生成过程各阶段会回调一次，供本地模型连接器向服务器上报进度。 */
	private volatile ProgressListener progressListener;

	/**
	 * 生成进度回调。
	 * <p>
	 * 只报告粗粒度阶段（已提交、采样完成、下载中、完成），用于进度协商；不保证实时百分比。
	 * </p>
	 */
	public interface ProgressListener {
		/**
		 * @param ratio  进度比例，0~1
		 * @param detail 阶段说明
		 */
		void onProgress(double ratio, String detail);
	}

	/** 使用默认地址（系统属性/环境变量优先）。 */
	public H3VideoApi() {
		this(resolveBaseUrl(null), null, DEFAULT_RESOURCE_AMOUNT);
	}

	/**
	 * @param baseUrl ComfyUI 地址，例如 {@code http://127.0.0.1:8188}
	 */
	public H3VideoApi(String baseUrl) {
		this(baseUrl, null, DEFAULT_RESOURCE_AMOUNT);
	}

	/**
	 * @param baseUrl ComfyUI 地址
	 * @param lock    资源锁，可为 null
	 * @param amount  每次操作占用的资源量
	 */
	public H3VideoApi(String baseUrl, ResourceLock lock, int amount) {
		this.baseUrl = normalizeBaseUrl(baseUrl);
		setResourceLock(lock, amount);
	}

	/**
	 * 解析 ComfyUI 地址：参数优先，其次 {@code -Dh3VideoUrl}、{@code -DqwenImageUrl}、
	 * 环境变量 {@code H3_VIDEO_API} / {@code QWEN_API}，最后落到 {@link #DEFAULT_BASE_URL}。
	 * <p>
	 * 图片与视频跑在同一个 ComfyUI 进程里，因此没有单独配置视频地址时可以直接复用 Qwen 的地址。
	 * </p>
	 *
	 * @param raw 显式传入的地址，可为空
	 * @return 规整后的地址（带协议、无末尾斜杠）
	 */
	public static String resolveBaseUrl(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			value = System.getProperty("h3VideoUrl", "").trim();
		}
		if (value.isEmpty()) {
			value = System.getProperty("qwenImageUrl", "").trim();
		}
		if (value.isEmpty()) {
			String env = System.getenv("H3_VIDEO_API");
			value = env == null ? "" : env.trim();
		}
		if (value.isEmpty()) {
			String env = System.getenv("QWEN_API");
			value = env == null ? "" : env.trim();
		}
		return normalizeBaseUrl(value);
	}

	private static String normalizeBaseUrl(String baseUrl) {
		String value = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE_URL : baseUrl.trim();
		if (!value.startsWith("http://") && !value.startsWith("https://")) {
			value = "http://" + value;
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	/** 服务地址。 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * 设置资源锁：之后每次生成/上传都会先占用资源，用完自动释放。
	 * <p>
	 * H3 默认“至少 {@value #MIN_RESOURCE_AMOUNT} 份，然后占满剩余全部资源”，任务期间其它本地模型
	 * 无法启动；只有显式传正数 {@code amount} 时才按固定份数占用。
	 * </p>
	 *
	 * @param lock   资源锁，可为 null
	 * @param amount 固定占用份数；小于等于 0 表示“至少 {@value #MIN_RESOURCE_AMOUNT} 份后占满剩余”
	 * @return 当前实例
	 */
	public H3VideoApi setResourceLock(ResourceLock lock, int amount) {
		this.resourceLock = lock;
		this.resourceAmount = Math.max(0, amount);
		return this;
	}

	/** 设置首/尾帧上传子目录。 */
	public H3VideoApi setUploadSubfolder(String uploadSubfolder) {
		this.uploadSubfolder = uploadSubfolder == null || uploadSubfolder.trim().isEmpty() ? "h3"
				: uploadSubfolder.trim();
		return this;
	}

	/** 设置等待超时（毫秒）。 */
	public H3VideoApi setTimeoutMillis(long timeoutMillis) {
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		return this;
	}

	/** 设置轮询间隔（毫秒）。 */
	public H3VideoApi setPollIntervalMillis(long pollIntervalMillis) {
		this.pollIntervalMillis = Math.max(100L, pollIntervalMillis);
		return this;
	}

	/** 覆盖 UNet 文件名。 */
	public H3VideoApi setUnetName(String unetName) {
		this.unetName = requireText(unetName, DEFAULT_UNET);
		return this;
	}

	/** 覆盖文本编码器文件名。 */
	public H3VideoApi setClipName(String clipName) {
		this.clipName = requireText(clipName, DEFAULT_CLIP);
		return this;
	}

	/** 覆盖视频 VAE 文件名。 */
	public H3VideoApi setVaeName(String vaeName) {
		this.vaeName = requireText(vaeName, DEFAULT_VAE);
		return this;
	}

	/** 覆盖音频 VAE 文件名。 */
	public H3VideoApi setAudioVaeName(String audioVaeName) {
		this.audioVaeName = requireText(audioVaeName, DEFAULT_AUDIO_VAE);
		return this;
	}

	/** 覆盖输出目录/前缀。 */
	public H3VideoApi setFilenamePrefix(String filenamePrefix) {
		this.filenamePrefix = requireText(filenamePrefix, DEFAULT_FILENAME_PREFIX);
		return this;
	}

	/**
	 * 切换主模型档位，同时把默认步数调整为该档位的默认值（随后可用 {@link #setSteps(int)} 覆盖）。
	 *
	 * @param tier 档位，null 视为 {@link ModelTier#INT8}
	 * @return 当前实例
	 */
	public H3VideoApi setModelTier(ModelTier tier) {
		this.modelTier = tier == null ? ModelTier.INT8 : tier;
		this.unetName = this.modelTier.unetName();
		this.steps = this.modelTier.defaultSteps();
		return this;
	}

	/** 当前主模型档位。 */
	public ModelTier getModelTier() {
		return modelTier;
	}

	/** 设置采样步数（1~100）。 */
	public H3VideoApi setSteps(int steps) {
		this.steps = Math.max(1, Math.min(100, steps));
		return this;
	}

	/** 当前采样步数。 */
	public int getSteps() {
		return steps;
	}

	/** 是否使用稀疏注意力（sol-attn）。 */
	public H3VideoApi setSparse(boolean sparse) {
		this.sparse = sparse;
		return this;
	}

	/** 当前是否使用稀疏注意力。 */
	public boolean isSparse() {
		return sparse;
	}

	/**
	 * 是否生成音频（默认 false：所有视频 MCP 都禁用音频）。
	 *
	 * @param audioEnabled true 时才加载音频 VAE、解码音频并混入 mp4
	 * @return 当前实例
	 */
	public H3VideoApi setAudioEnabled(boolean audioEnabled) {
		this.audioEnabled = audioEnabled;
		return this;
	}

	/** 当前是否生成音频。 */
	public boolean isAudioEnabled() {
		return audioEnabled;
	}

	/**
	 * 设置进度回调；传 null 关闭。回调抛出的异常会被吞掉，不会影响生成。
	 *
	 * @param listener 进度回调，可为 null
	 * @return 当前实例
	 */
	public H3VideoApi setProgressListener(ProgressListener listener) {
		this.progressListener = listener;
		return this;
	}

	/** 安全地回调一次进度。 */
	private void notifyProgress(double ratio, String detail) {
		ProgressListener listener = progressListener;
		if (listener == null) {
			return;
		}
		try {
			listener.onProgress(ratio, detail);
		} catch (Throwable ignored) {
			// 进度上报失败不影响生成
		}
	}

	private static String requireText(String value, String fallback) {
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	// ==================== 帧数 / 尺寸 ====================

	/**
	 * 秒数 → H3 帧数：按 24fps 换算后向上取整到最近的 {@code 17k+5}。
	 * <p>
	 * 例如 5 秒 → 120 帧 → 124 帧（≈5.17 秒）、10 秒 → 240 → 243、15 秒 → 360 → 362。
	 * 结果是“不小于请求时长”的最近合法帧数，H3 只接受这个网格上的帧数。
	 * </p>
	 *
	 * @param seconds 期望时长（秒），非正数按最短片段处理
	 * @return 合法帧数
	 */
	public static int snapFrames(double seconds) {
		double requested = seconds <= 0 ? 0 : seconds * FPS;
		long target = requested <= FRAME_OFFSET ? FRAME_OFFSET : (long) Math.ceil(requested);
		return normalizeFrames((int) Math.min(Integer.MAX_VALUE, target));
	}

	/**
	 * 帧数 → 最近的 ≥ 输入值的合法帧数（{@code 17k+5}）。
	 *
	 * @param frames 期望帧数
	 * @return 合法帧数，至少为 {@value #FRAME_OFFSET}
	 */
	public static int normalizeFrames(int frames) {
		long value = Math.max(FRAME_OFFSET, frames);
		while (value % FRAME_STEP != FRAME_OFFSET) {
			value++;
		}
		return (int) Math.min(Integer.MAX_VALUE, value);
	}

	/** 帧数是否落在 H3 的 {@code 17k+5} 网格上。 */
	public static boolean isValidFrameCount(int frames) {
		return frames >= FRAME_OFFSET && frames % FRAME_STEP == FRAME_OFFSET;
	}

	/**
	 * 校验宽高：必须是 {@value #SIZE_MULTIPLE} 的正整数倍。
	 *
	 * @throws IllegalArgumentException 不满足约束
	 */
	private static void validateSize(int width, int height) {
		if (width <= 0 || height <= 0 || width % SIZE_MULTIPLE != 0 || height % SIZE_MULTIPLE != 0) {
			throw new IllegalArgumentException(
					"宽高必须是 " + SIZE_MULTIPLE + " 的倍数：" + width + "x" + height);
		}
	}

	// ==================== 文生视频 / 图生视频 ====================

	/**
	 * 文生视频（无参考图）。
	 *
	 * @param prompt    提示词（画面 + 镜头 + 声音写一段）
	 * @param width     宽，32 的倍数
	 * @param height    高，32 的倍数
	 * @param frames    帧数，非 17k+5 时自动向上吸附
	 * @param seed      随机种子，由调用方固定或随机
	 * @param freeAfter 提交前是否标记任务结束后卸载模型
	 * @return 生成的视频文件
	 * @throws IOException 服务不可用或生成失败
	 */
	public List<VideoResult> textToVideo(String prompt, int width, int height, int frames, long seed,
			boolean freeAfter) throws IOException {
		return run(buildVideo(prompt, width, height, frames, seed, null, null), freeAfter);
	}

	/**
	 * 图生视频 / 首尾帧视频。
	 *
	 * @param prompt     提示词
	 * @param width      宽，32 的倍数
	 * @param height     高，32 的倍数
	 * @param frames     帧数，非 17k+5 时自动向上吸附
	 * @param seed       随机种子
	 * @param firstFrame 首帧图，可为 null（为 null 时忽略尾帧，即文生视频）
	 * @param lastFrame  尾帧图，可为 null；给尾帧时必须同时给首帧
	 * @param freeAfter  提交前是否标记任务结束后卸载模型
	 * @return 生成的视频文件
	 * @throws IOException 上传或生成失败
	 */
	public List<VideoResult> video(String prompt, int width, int height, int frames, long seed,
			InputImage firstFrame, InputImage lastFrame, boolean freeAfter) throws IOException {
		if (lastFrame != null && firstFrame == null) {
			throw new IllegalArgumentException("首尾帧视频必须同时提供首帧");
		}
		ResourcePermit permit = acquire();
		try {
			String firstName = firstFrame == null ? null : uploadImageUnlocked(firstFrame);
			String lastName = lastFrame == null ? null : uploadImageUnlocked(lastFrame);
			return runUnlocked(buildVideo(prompt, width, height, frames, seed, firstName, lastName), freeAfter);
		} finally {
			release(permit);
		}
	}

	/**
	 * 构造 H3 工作流（不提交），便于调试或自行改写参数。
	 * <p>
	 * 节点 ID 与 {@code api/graphs/h3-*.json} 保持一致：{@code 5} 提示词/尺寸/帧数、
	 * {@code 8} 步数、{@code 9} 种子、{@code 20}/{@code 21} 首/尾帧、{@code 14} 输出。
	 * </p>
	 *
	 * @param prompt           提示词
	 * @param width            宽
	 * @param height           高
	 * @param frames           帧数
	 * @param seed             随机种子
	 * @param uploadedFirstName 已上传的首帧名（可带 {@code h3/} 前缀），可为 null
	 * @param uploadedLastName  已上传的尾帧名，可为 null
	 * @return 工作流图
	 */
	public JsonObject buildVideo(String prompt, int width, int height, int frames, long seed,
			String uploadedFirstName, String uploadedLastName) {
		validateSize(width, height);
		if (uploadedLastName != null && uploadedFirstName == null) {
			throw new IllegalArgumentException("首尾帧视频必须同时提供首帧");
		}
		int length = normalizeFrames(frames);
		JsonObject graph = new JsonObject();
		// 1 UNet / 2 文本编码器 / 3 视频 VAE / 4 音频 VAE（仅 audioEnabled 时）
		JsonObject unet = node("UNETLoader");
		unet.getAsJsonObject("inputs").addProperty("unet_name", unetName);
		unet.getAsJsonObject("inputs").addProperty("weight_dtype", "default");
		graph.add("1", unet);
		JsonObject clip = node("CLIPLoader");
		JsonObject clipInputs = clip.getAsJsonObject("inputs");
		clipInputs.addProperty("clip_name", clipName);
		clipInputs.addProperty("type", "minimax");
		clipInputs.addProperty("device", "default");
		graph.add("2", clip);
		JsonObject vae = node("VAELoader");
		vae.getAsJsonObject("inputs").addProperty("vae_name", vaeName);
		graph.add("3", vae);
		if (audioEnabled) {
			JsonObject audioVae = node("VAELoader");
			audioVae.getAsJsonObject("inputs").addProperty("vae_name", audioVaeName);
			graph.add("4", audioVae);
		}
		// 5 提示词 + 画布 + 帧数（首/尾帧可选）
		JsonObject video = node("MiniMaxH3ImageToVideo");
		JsonObject videoInputs = video.getAsJsonObject("inputs");
		videoInputs.add("clip", ref("2", 0));
		videoInputs.add("vae", ref("3", 0));
		videoInputs.addProperty("prompt", prompt == null ? "" : prompt);
		videoInputs.addProperty("width", width);
		videoInputs.addProperty("height", height);
		videoInputs.addProperty("length", length);
		graph.add("5", video);
		// 15 可选稀疏注意力：sol-attn（序列 <12288 token 时节点内部自动退回稠密）
		String samplingModel = "1";
		if (sparse) {
			JsonObject sparseAttention = node("BlockSparseAttention");
			JsonObject sparseInputs = sparseAttention.getAsJsonObject("inputs");
			sparseInputs.add("model", ref("1", 0));
			sparseInputs.addProperty("selection", "sol-attn");
			sparseInputs.addProperty("selection.tau", 1.2);
			sparseInputs.addProperty("start_percent", 0.2);
			sparseInputs.addProperty("end_percent", 1.0);
			sparseInputs.addProperty("dense_blocks", "");
			sparseInputs.addProperty("min_tokens", SPARSE_MIN_TOKENS);
			sparseInputs.addProperty("extra_tokens", 256);
			sparseInputs.addProperty("sink_conditioning", "exact_kv_and_rows");
			sparseInputs.addProperty("verbose", false);
			graph.add("15", sparseAttention);
			samplingModel = "15";
		}
		// 6 只吃正向条件的 BasicGuider（H3 没有 CFG）
		JsonObject guider = node("BasicGuider");
		guider.getAsJsonObject("inputs").add("model", ref(samplingModel, 0));
		guider.getAsJsonObject("inputs").add("conditioning", ref("5", 0));
		graph.add("6", guider);
		JsonObject samplerSelect = node("KSamplerSelect");
		samplerSelect.getAsJsonObject("inputs").addProperty("sampler_name", SAMPLER);
		graph.add("7", samplerSelect);
		JsonObject scheduler = node("BasicScheduler");
		JsonObject schedulerInputs = scheduler.getAsJsonObject("inputs");
		schedulerInputs.add("model", ref(samplingModel, 0));
		schedulerInputs.addProperty("scheduler", SCHEDULER);
		schedulerInputs.addProperty("steps", steps);
		schedulerInputs.addProperty("denoise", 1.0);
		graph.add("8", scheduler);
		JsonObject noise = node("RandomNoise");
		noise.getAsJsonObject("inputs").addProperty("noise_seed", seed);
		graph.add("9", noise);
		JsonObject sampler = node("SamplerCustomAdvanced");
		JsonObject samplerInputs = sampler.getAsJsonObject("inputs");
		samplerInputs.add("noise", ref("9", 0));
		samplerInputs.add("guider", ref("6", 0));
		samplerInputs.add("sampler", ref("7", 0));
		samplerInputs.add("sigmas", ref("8", 0));
		samplerInputs.add("latent_image", ref("5", 1));
		graph.add("10", sampler);
		// 11 视频帧 → 13 合成 mp4 → 14 保存；音频节点只在 audioEnabled 时加入
		JsonObject decode = node("VAEDecode");
		decode.getAsJsonObject("inputs").add("samples", ref("10", 0));
		decode.getAsJsonObject("inputs").add("vae", ref("3", 0));
		graph.add("11", decode);
		if (audioEnabled) {
			JsonObject decodeAudio = node("VAEDecodeAudio");
			decodeAudio.getAsJsonObject("inputs").add("samples", ref("10", 0));
			decodeAudio.getAsJsonObject("inputs").add("vae", ref("4", 0));
			graph.add("12", decodeAudio);
		}
		JsonObject createVideo = node("CreateVideo");
		JsonObject createInputs = createVideo.getAsJsonObject("inputs");
		createInputs.add("images", ref("11", 0));
		createInputs.addProperty("fps", FPS);
		if (audioEnabled) {
			createInputs.add("audio", ref("12", 0));
		}
		createInputs.addProperty("bit_depth", 8);
		graph.add("13", createVideo);
		JsonObject saveVideo = node("SaveVideo");
		JsonObject saveInputs = saveVideo.getAsJsonObject("inputs");
		saveInputs.add("video", ref("13", 0));
		saveInputs.addProperty("filename_prefix", filenamePrefix);
		saveInputs.addProperty("format", "auto");
		saveInputs.addProperty("codec", "auto");
		graph.add("14", saveVideo);
		// 16/17 从解码帧批次取首帧、末帧；18/19 保存成 png，供“视频作为首尾帧”直接复用
		JsonObject firstBatch = node("ImageFromBatch");
		JsonObject firstBatchInputs = firstBatch.getAsJsonObject("inputs");
		firstBatchInputs.add("image", ref("11", 0));
		firstBatchInputs.addProperty("batch_index", 0);
		firstBatchInputs.addProperty("length", 1);
		graph.add("16", firstBatch);
		JsonObject lastBatch = node("ImageFromBatch");
		JsonObject lastBatchInputs = lastBatch.getAsJsonObject("inputs");
		lastBatchInputs.add("image", ref("11", 0));
		lastBatchInputs.addProperty("batch_index", -1);
		lastBatchInputs.addProperty("length", 1);
		graph.add("17", lastBatch);
		JsonObject saveFirst = node("SaveImage");
		JsonObject saveFirstInputs = saveFirst.getAsJsonObject("inputs");
		saveFirstInputs.add("images", ref("16", 0));
		saveFirstInputs.addProperty("filename_prefix", filenamePrefix + "_first");
		graph.add("18", saveFirst);
		JsonObject saveLast = node("SaveImage");
		JsonObject saveLastInputs = saveLast.getAsJsonObject("inputs");
		saveLastInputs.add("images", ref("17", 0));
		saveLastInputs.addProperty("filename_prefix", filenamePrefix + "_last");
		graph.add("19", saveLast);
		if (uploadedFirstName != null) {
			JsonObject first = node("LoadImage");
			first.getAsJsonObject("inputs").addProperty("image", uploadedFirstName);
			graph.add("20", first);
			videoInputs.add("first_frame", ref("20", 0));
		}
		if (uploadedLastName != null) {
			JsonObject last = node("LoadImage");
			last.getAsJsonObject("inputs").addProperty("image", uploadedLastName);
			graph.add("21", last);
			videoInputs.add("last_frame", ref("21", 0));
		}
		return graph;
	}

	/**
	 * 构造“从视频取一帧”的工作流（对应 {@code api/graphs/video-frame.json}）。
	 * <p>
	 * 帧号使用 {@code batch_index}：{@code 0} 首帧、{@code -1} 末帧、{@code -2} 倒数第二帧。
	 * </p>
	 *
	 * @param uploadedVideoName 已上传到 ComfyUI input 的视频名
	 * @param batchIndex        帧号，支持负数
	 * @return 工作流图
	 */
	public JsonObject buildVideoFrame(String uploadedVideoName, int batchIndex) {
		if (uploadedVideoName == null || uploadedVideoName.trim().isEmpty()) {
			throw new IllegalArgumentException("视频名不能为空");
		}
		JsonObject graph = new JsonObject();
		JsonObject load = node("LoadVideo");
		load.getAsJsonObject("inputs").addProperty("file", uploadedVideoName.trim());
		graph.add("1", load);
		JsonObject components = node("GetVideoComponents");
		components.getAsJsonObject("inputs").add("video", ref("1", 0));
		graph.add("2", components);
		JsonObject batch = node("ImageFromBatch");
		JsonObject batchInputs = batch.getAsJsonObject("inputs");
		batchInputs.add("image", ref("2", 0));
		batchInputs.addProperty("batch_index", batchIndex);
		batchInputs.addProperty("length", 1);
		graph.add("3", batch);
		JsonObject save = node("SaveImage");
		JsonObject saveInputs = save.getAsJsonObject("inputs");
		saveInputs.add("images", ref("3", 0));
		saveInputs.addProperty("filename_prefix", "video/frame");
		graph.add("4", save);
		return graph;
	}

	/**
	 * 从视频里截取一帧。
	 *
	 * @param video      视频字节（文件名建议带 {@code .mp4}）
	 * @param batchIndex 帧号，{@code 0} 首帧、{@code -1} 末帧
	 * @return 截取的图片
	 * @throws IOException 上传、提交或下载失败
	 */
	public ImageResult extractFrame(InputImage video, int batchIndex) throws IOException {
		ResourcePermit permit = acquire();
		try {
			return extractFrameUnlocked(video, batchIndex);
		} finally {
			release(permit);
		}
	}

	/**
	 * 从视频里截取一帧（调用方已持有资源锁）。
	 *
	 * @param video      视频字节
	 * @param batchIndex 帧号
	 * @return 截取的图片
	 * @throws IOException 上传、提交或下载失败
	 */
	public ImageResult extractFrameUnlocked(InputImage video, int batchIndex) throws IOException {
		if (video == null || video.data == null || video.data.length == 0) {
			throw new IllegalArgumentException("参考视频不能为空");
		}
		String uploaded = uploadImageUnlocked(video);
		String promptId = submit(buildVideoFrame(uploaded, batchIndex));
		JsonObject entry = waitFor(promptId);
		List<ImageResult> images = downloadImages(entry);
		if (images.isEmpty()) {
			throw new IOException("ComfyUI 没有返回视频帧图片");
		}
		return images.get(0);
	}

	// ==================== 上传 / 提交 / 轮询 / 下载 ====================

	/**
	 * 上传首帧/尾帧到 ComfyUI 的 input 目录。
	 *
	 * @param image 图片（文件名与字节）
	 * @return 节点里应填写的图片名，例如 {@code h3/ref.png}
	 * @throws IOException 上传失败
	 */
	public String uploadImage(InputImage image) throws IOException {
		ResourcePermit permit = acquire();
		try {
			return uploadImageUnlocked(image);
		} finally {
			release(permit);
		}
	}

	/** 上传首/尾帧（调用方已持有资源锁）。 */
	private String uploadImageUnlocked(InputImage image) throws IOException {
		if (image == null || image.data == null || image.data.length == 0) {
			throw new IllegalArgumentException("参考图不能为空");
		}
		String filename = image.filename == null || image.filename.trim().isEmpty() ? "image.png"
				: new File(image.filename.trim()).getName();
		String boundary = "----H3VideoBoundary" + UUID.randomUUID().toString().replace("-", "");
		HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/upload/image").openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setDoInput(true);
		connection.setConnectTimeout(30000);
		connection.setReadTimeout(120000);
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
		try (OutputStream out = connection.getOutputStream()) {
			writeFormField(out, boundary, "subfolder", uploadSubfolder);
			writeFormField(out, boundary, "overwrite", "true");
			String header = "--" + boundary + "\r\n"
					+ "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
					+ "Content-Type: application/octet-stream\r\n\r\n";
			out.write(header.getBytes(StandardCharsets.UTF_8));
			out.write(image.data);
			out.write("\r\n".getBytes(StandardCharsets.UTF_8));
			out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		}
		int code = connection.getResponseCode();
		String body = readBody(code >= 200 && code < 300 ? connection.getInputStream()
				: connection.getErrorStream());
		connection.disconnect();
		if (code < 200 || code >= 300) {
			throw new IOException("H3 上传参考图失败 HTTP " + code + "：" + body);
		}
		JsonObject json = parseJsonObject(body, "上传参考图");
		String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : "";
		String subfolder = json.has("subfolder") && !json.get("subfolder").isJsonNull()
				? json.get("subfolder").getAsString() : "";
		if (name.isEmpty()) {
			throw new IOException("H3 上传参考图失败：响应缺少 name");
		}
		return subfolder.isEmpty() ? name : subfolder + "/" + name;
	}

	/**
	 * 提交工作流。
	 *
	 * @param graph 工作流图
	 * @return prompt_id
	 * @throws IOException 提交失败
	 */
	public String submit(JsonObject graph) throws IOException {
		JsonObject request = new JsonObject();
		request.add("prompt", graph);
		String body = new HttpRequestBuilder(baseUrl).url("/prompt")
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.readTimeout(30000)
				.post().send(request.toString()).readString();
		JsonObject response = parseJsonObject(body, "提交工作流");
		if (!response.has("prompt_id") || response.get("prompt_id").isJsonNull()) {
			throw new IOException("H3 提交失败：响应缺少 prompt_id");
		}
		return response.get("prompt_id").getAsString();
	}

	/**
	 * 轮询历史直到任务完成。
	 *
	 * @param promptId 提交返回的 prompt_id
	 * @return 该任务的 history 条目
	 * @throws IOException 超时、任务失败或网络错误
	 */
	public JsonObject waitFor(String promptId) throws IOException {
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMillis) {
			JsonObject history = getHistory(promptId);
			JsonElement entryElement = history.get(promptId);
			if (entryElement != null && entryElement.isJsonObject()) {
				JsonObject entry = entryElement.getAsJsonObject();
				JsonObject status = entry.has("status") && entry.get("status").isJsonObject()
						? entry.getAsJsonObject("status") : null;
				if (status != null && status.has("status_str")
						&& "error".equalsIgnoreCase(status.get("status_str").getAsString())) {
					throw new IOException("H3 视频生成失败：" + status);
				}
				if (status == null || !status.has("completed") || status.get("completed").getAsBoolean()) {
					return entry;
				}
			}
			sleep(pollIntervalMillis);
		}
		throw new IOException("H3 视频生成超时（" + timeoutMillis + "ms）：" + promptId);
	}

	/**
	 * 读取一次任务历史。
	 * <p>
	 * ComfyUI 在任务尚未进入历史时可能返回空响应或 JSON {@code null}，这里统一按空对象处理，
	 * 继续轮询，避免把“还没完成”误报成 JSON 解析失败。
	 * </p>
	 */
	private JsonObject getHistory(String promptId) throws IOException {
		String path = "/history/" + URLEncoder.encode(promptId, StandardCharsets.UTF_8.name());
		String body = new HttpRequestBuilder(baseUrl).url(path)
				.header("Accept", "application/json").readTimeout(30000)
				.get().readString();
		if (body == null) {
			return new JsonObject();
		}
		String trimmed = body.trim();
		if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
			return new JsonObject();
		}
		JsonElement element = JsonParser.parseString(trimmed);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	/**
	 * 下载 history 条目里的全部输出，并按扩展名区分视频与图片。
	 * <p>
	 * H3 工作流现在同时产出 {@code mp4 + 首帧 png + 末帧 png}；旧图只有 mp4 时
	 * {@code videos} 照常返回、{@code frames} 为空。
	 * </p>
	 *
	 * @param historyEntry {@link #waitFor(String)} 的结果
	 * @return 视频与首/末帧
	 * @throws IOException 下载失败
	 */
	public H3Output downloadOutputs(JsonObject historyEntry) throws IOException {
		List<VideoResult> videos = new ArrayList<>();
		List<ImageResult> images = new ArrayList<>();
		if (historyEntry == null || !historyEntry.has("outputs") || !historyEntry.get("outputs").isJsonObject()) {
			return new H3Output(videos, images);
		}
		Set<String> seen = new HashSet<>();
		for (java.util.Map.Entry<String, JsonElement> node : historyEntry.getAsJsonObject("outputs").entrySet()) {
			if (!node.getValue().isJsonObject()) {
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> field : node.getValue().getAsJsonObject().entrySet()) {
				if (!field.getValue().isJsonArray()) {
					continue;
				}
				JsonArray items = field.getValue().getAsJsonArray();
				for (JsonElement itemElement : items) {
					if (!itemElement.isJsonObject()) {
						continue;
					}
					JsonObject item = itemElement.getAsJsonObject();
					String filename = item.has("filename") && !item.get("filename").isJsonNull()
							? item.get("filename").getAsString() : "";
					if (filename.isEmpty()) {
						continue;
					}
					String subfolder = item.has("subfolder") && !item.get("subfolder").isJsonNull()
							? item.get("subfolder").getAsString() : "";
					String type = item.has("type") && !item.get("type").isJsonNull()
							? item.get("type").getAsString() : "output";
					String key = filename + "|" + subfolder;
					if (!seen.add(key)) {
						continue;
					}
					byte[] data = new HttpRequestBuilder(baseUrl)
							.url("/view?filename=" + enc(filename) + "&subfolder=" + enc(subfolder) + "&type="
									+ enc(type))
							.header("Accept", "*/*").readTimeout(600000).get().readBytes();
					String lower = filename.toLowerCase(java.util.Locale.ROOT);
					if (isVideoFilename(lower)) {
						videos.add(new VideoResult(filename, subfolder, type, data));
					} else if (isImageFilename(lower)) {
						images.add(new ImageResult(filename, subfolder, type, data));
					} else if (field.getKey().toLowerCase(java.util.Locale.ROOT).contains("video")) {
						videos.add(new VideoResult(filename, subfolder, type, data));
					} else {
						images.add(new ImageResult(filename, subfolder, type, data));
					}
				}
			}
		}
		return new H3Output(videos, images);
	}

	/** 兼容旧调用：只返回视频。 */
	public List<VideoResult> downloadVideos(JsonObject historyEntry) throws IOException {
		return downloadOutputs(historyEntry).videos;
	}

	/** 只返回图片输出（例如视频截帧）。 */
	public List<ImageResult> downloadImages(JsonObject historyEntry) throws IOException {
		return downloadOutputs(historyEntry).images;
	}

	private static boolean isVideoFilename(String lower) {
		return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")
				|| lower.endsWith(".mkv");
	}

	private static boolean isImageFilename(String lower) {
		return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
				|| lower.endsWith(".webp") || lower.endsWith(".gif");
	}

	/**
	 * 完整执行一次工作流：按需标记卸载 → 提交 → 轮询 → 下载。
	 *
	 * @param graph     工作流
	 * @param freeAfter 是否在提交前标记任务结束后卸载模型
	 * @return 视频与首/末帧
	 * @throws IOException 任何一步失败
	 */
	public H3Output runOutput(JsonObject graph, boolean freeAfter) throws IOException {
		ResourcePermit permit = acquire();
		try {
			return runUnlockedOutput(graph, freeAfter);
		} finally {
			release(permit);
		}
	}

	/**
	 * 完整执行一次工作流，只返回视频（兼容旧调用）。
	 *
	 * @param graph     工作流
	 * @param freeAfter 是否在提交前标记任务结束后卸载模型
	 * @return 生成的视频
	 * @throws IOException 任何一步失败
	 */
	public List<VideoResult> run(JsonObject graph, boolean freeAfter) throws IOException {
		return runOutput(graph, freeAfter).videos;
	}

	/** 执行工作流（调用方已持有资源锁），只返回视频。 */
	private List<VideoResult> runUnlocked(JsonObject graph, boolean freeAfter) throws IOException {
		return runUnlockedOutput(graph, freeAfter).videos;
	}

	/** 执行工作流（调用方已持有资源锁）。 */
	private H3Output runUnlockedOutput(JsonObject graph, boolean freeAfter) throws IOException {
		if (freeAfter) {
			try {
				freeUnlocked();
			} catch (Exception ex) {
				// /free 只是显存优化，接口可能返回空响应或失败，不应阻断出片
				System.err.println("[H3Video] 标记显存释放失败：" + ex.getMessage());
			}
		}
		notifyProgress(0.05, "已提交任务");
		String promptId = submit(graph);
		notifyProgress(0.1, "开始采样");
		JsonObject entry = waitFor(promptId);
		notifyProgress(0.8, "采样完成");
		H3Output output = downloadOutputs(entry);
		notifyProgress(1.0, "视频已下载");
		return output;
	}

	/** 请求 ComfyUI 在当前任务结束后卸载模型并清理缓存。 */
	public void free() throws IOException {
		ResourcePermit permit = acquire();
		try {
			freeUnlocked();
		} finally {
			release(permit);
		}
	}

	/** 请求释放显存（调用方已持有资源锁）。 */
	private void freeUnlocked() throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("unload_models", true);
		body.addProperty("free_memory", true);
		// 该接口可能返回空响应，不能按 JSON 对象解析
		new HttpRequestBuilder(baseUrl).url("/free")
				.header("Content-Type", "application/json")
				.readTimeout(15000).post().send(body.toString()).readString();
	}

	/** 中断当前任务。 */
	public void interrupt() throws IOException {
		ResourcePermit permit = acquire();
		try {
			new HttpRequestBuilder(baseUrl).url("/interrupt")
					.header("Content-Type", "application/json")
					.readTimeout(15000).post().send("{}").readString();
		} finally {
			release(permit);
		}
	}

	/** 查询队列状态。 */
	public JsonObject queue() throws IOException {
		return new HttpRequestBuilder(baseUrl).url("/queue")
				.header("Accept", "application/json").readTimeout(15000).get().readJson();
	}

	/** 查询某个节点的可用模型/文件列表。 */
	public JsonObject objectInfo(String nodeName) throws IOException {
		return new HttpRequestBuilder(baseUrl).url("/object_info/" + enc(nodeName))
				.header("Accept", "application/json").readTimeout(30000).get().readJson();
	}

	// ==================== 工具方法 ====================

	private static JsonObject node(String classType) {
		JsonObject node = new JsonObject();
		node.addProperty("class_type", classType);
		node.add("inputs", new JsonObject());
		return node;
	}

	private static JsonArray ref(String nodeId, int outputIndex) {
		JsonArray reference = new JsonArray();
		reference.add(nodeId);
		reference.add(outputIndex);
		return reference;
	}

	/** 解析 JSON 对象响应；空响应/JSON null 时给出明确错误，而不是 Gson 的 “Not a JSON Object: null”。 */
	private static JsonObject parseJsonObject(String body, String action) throws IOException {
		if (body == null || body.trim().isEmpty()) {
			throw new IOException("H3 " + action + "失败：服务返回空响应");
		}
		String trimmed = body.trim();
		if ("null".equalsIgnoreCase(trimmed)) {
			throw new IOException("H3 " + action + "失败：服务返回 null");
		}
		try {
			JsonElement element = JsonParser.parseString(trimmed);
			if (element == null || !element.isJsonObject()) {
				throw new IOException("H3 " + action + "失败：响应不是 JSON 对象");
			}
			return element.getAsJsonObject();
		} catch (IOException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new IOException("H3 " + action + "失败：响应无法解析为 JSON：" + trimmed, ex);
		}
	}

	/** 生成一个非负随机种子。 */
	public static long randomSeed() {
		return ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE;
	}

	/** 申请资源锁；未配置锁时返回 null。 */
	private ResourcePermit acquire() throws IOException {
		if (resourceLock == null) {
			return null;
		}
		try {
			if (resourceAmount <= 0) {
				// H3 默认独占：先满足最低 8 份，再把当前剩余资源全部占满
				return resourceLock.acquireAtLeast(MIN_RESOURCE_AMOUNT);
			}
			return resourceLock.acquire(resourceAmount);
		} catch (RuntimeException ex) {
			throw new IOException("H3 等待资源锁失败：" + ex.getMessage(), ex);
		}
	}

	/** 释放资源锁。 */
	private static void release(ResourcePermit permit) {
		if (permit != null) {
			permit.release();
		}
	}

	private static void writeFormField(OutputStream out, String boundary, String name, String value)
			throws IOException {
		String header = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n";
		out.write(header.getBytes(StandardCharsets.UTF_8));
		out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	private static String readBody(InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		try (InputStream in = stream) {
			return new String(FileUtil.readAll(in), StandardCharsets.UTF_8);
		}
	}

	private static String enc(String value) throws IOException {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
	}

	private static void sleep(long millis) throws IOException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("H3 等待被中断", ex);
		}
	}

	/** 一张输入参考图（首帧 / 尾帧）。 */
	public static final class InputImage {
		/** 图片字节。 */
		public final byte[] data;
		/** 上传文件名，可带扩展名。 */
		public final String filename;

		public InputImage(byte[] data, String filename) {
			this.data = data;
			this.filename = filename;
		}
	}

	/** 一个生成的视频文件。 */
	public static final class VideoResult {
		/** 服务端文件名。 */
		public final String filename;
		/** 输出子目录（通常是 {@code video}）。 */
		public final String subfolder;
		/** 输出类型（通常 output）。 */
		public final String type;
		/** 视频字节（mp4）。 */
		public final byte[] data;

		public VideoResult(String filename, String subfolder, String type, byte[] data) {
			this.filename = filename;
			this.subfolder = subfolder;
			this.type = type;
			this.data = data;
		}
	}

	/** 一张生成的图片（视频首/末帧或单独截帧）。 */
	public static final class ImageResult {
		/** 服务端文件名。 */
		public final String filename;
		/** 输出子目录（通常是 {@code video}）。 */
		public final String subfolder;
		/** 输出类型（通常 output）。 */
		public final String type;
		/** 图片字节（png/jpg）。 */
		public final byte[] data;

		public ImageResult(String filename, String subfolder, String type, byte[] data) {
			this.filename = filename;
			this.subfolder = subfolder;
			this.type = type;
			this.data = data;
		}
	}

	/** 一次 H3 任务的完整输出：视频 + 首/末帧。 */
	public static final class H3Output {
		/** 视频文件，通常是 mp4。 */
		public final List<VideoResult> videos;
		/** 所有图片输出（首帧/末帧）。 */
		public final List<ImageResult> images;
		/** 首帧图片，没有时为 null。 */
		public final ImageResult firstFrame;
		/** 末帧图片，没有时为 null。 */
		public final ImageResult lastFrame;

		public H3Output(List<VideoResult> videos, List<ImageResult> images) {
			this.videos = videos == null ? new ArrayList<>() : videos;
			this.images = images == null ? new ArrayList<>() : images;
			this.firstFrame = findFrame(this.images, "_first", true);
			this.lastFrame = findFrame(this.images, "_last", false);
		}

		private static ImageResult findFrame(List<ImageResult> images, String marker, boolean first) {
			for (ImageResult image : images) {
				String name = image.filename == null ? "" : image.filename.toLowerCase(java.util.Locale.ROOT);
				if (name.contains(marker)) {
					return image;
				}
			}
			return images.isEmpty() ? null : images.get(first ? 0 : images.size() - 1);
		}
	}
}
