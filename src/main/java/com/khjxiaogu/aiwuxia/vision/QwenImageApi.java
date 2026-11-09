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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;

/**
 * Qwen-Image 2.1（ComfyUI REST API）客户端。
 * <p>
 * 对应本地部署的 ComfyUI，默认地址 {@code http://127.0.0.1:8188}，无鉴权。支持 README 中的
 * 四套工作流：
 * </p>
 * <ul>
 *   <li>{@link #textToImage(String) 文生图}（{@code t2i.json}）；</li>
 *   <li>{@link #textToImage(String, boolean) 极速草稿}（{@code t2i-fast.json}，8 步 + 加速 LoRA）；</li>
 *   <li>{@link #edit(InputImage, String) 改图}（{@code edit.json}，单图 + 编辑指令）；</li>
 *   <li>{@link #reference(List, String) 多图参考}（{@code reference.json}，最多 10 张）。</li>
 * </ul>
 * <p>
 * 调用流程与 Python 参考实现一致：上传参考图 → POST {@code /prompt} → 轮询
 * {@code /history/{prompt_id}} → GET {@code /view} 下载图片。默认在提交前调用 {@code /free}
 * 标记任务结束后卸载模型，可用 {@code freeAfter=false} 关闭。
 * </p>
 * <p>
 * 地址可通过构造器传入，或使用系统属性 {@code -DqwenImageUrl=...}/环境变量 {@code QWEN_API}。
 * 工作流节点 ID 与字段名严格按 README 中的 graphs 定义，不依赖外部 JSON 文件。
 * </p>
 */
public class QwenImageApi {

	/** 默认 ComfyUI 地址。 */
	public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8188";
	/** 默认负面提示词（与 README 中的示例一致）。 */
	public static final String DEFAULT_NEGATIVE = "低质量, 模糊, 变形, 多余手指, 水印, 文字乱码, 过曝";
	/** 默认 UNet（GGUF）。 */
	public static final String DEFAULT_UNET = "qwen-image-2.1-Q8_0.gguf";
	/** 默认文本编码器。 */
	public static final String DEFAULT_CLIP = "qwen3vl_8b_int8_convrot.safetensors";
	/** 默认 VAE。 */
	public static final String DEFAULT_VAE = "qwen_image_2.1_vae_bf16.safetensors";
	/** 极速草稿 LoRA。 */
	public static final String DEFAULT_FAST_LORA = "p_q_image_2.1_8_steps_v0.1_comfyui_bf16.safetensors";
	/** 多图参考上限。 */
	public static final int MAX_REFERENCE_IMAGES = 10;
	/** 普通模式默认步数（不暴露给模型设置）。 */
	public static final int DEFAULT_STEPS = 12;
	/** 极速草稿步数（不暴露给模型设置）。 */
	public static final int FAST_STEPS = 8;
	/** 默认 CFG（不暴露给模型设置）。 */
	public static final float DEFAULT_CFG = 1.0f;

	/** 服务地址（不含末尾斜杠）。 */
	private final String baseUrl;
	/** 默认上传子目录。 */
	private String uploadSubfolder = "qwen";
	/** 等待历史的超时时间。 */
	private long timeoutMillis = 30 * 60 * 1000L;
	/** 轮询间隔。 */
	private long pollIntervalMillis = 2000L;
	/** 模型文件名，可按部署的量化档位覆盖。 */
	private String unetName = DEFAULT_UNET;
	private String clipName = DEFAULT_CLIP;
	private String vaeName = DEFAULT_VAE;
	private String fastLoraName = DEFAULT_FAST_LORA;
	/** 可选资源锁：非 null 时每次生成/上传都先占用资源，避免与其它本地模型并发抢显存。 */
	private ResourceLock resourceLock;
	/** 每次操作占用的资源量（Qwen Image 建议 6 份）。 */
	private int resourceAmount = 6;

	/** 使用默认地址（系统属性/环境变量优先）。 */
	public QwenImageApi() {
		this(defaultBaseUrl(), null, 6);
	}

	/**
	 * @param baseUrl ComfyUI 地址，例如 {@code http://127.0.0.1:8188}
	 */
	public QwenImageApi(String baseUrl) {
		this(baseUrl, null, 6);
	}

	/**
	 * @param baseUrl ComfyUI 地址
	 * @param lock    资源锁，可为 null
	 * @param amount  每次操作占用的资源量
	 */
	public QwenImageApi(String baseUrl, ResourceLock lock, int amount) {
		this.baseUrl = normalizeBaseUrl(baseUrl);
		setResourceLock(lock, amount);
	}

	private static String defaultBaseUrl() {
		String value = System.getProperty("qwenImageUrl");
		if (value == null || value.trim().isEmpty()) {
			value = System.getenv("QWEN_API");
		}
		return value == null || value.trim().isEmpty() ? DEFAULT_BASE_URL : value.trim();
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
	 * 设置资源锁：之后每次生成/上传都会先占用指定资源，用完自动释放。
	 * <p>
	 * Qwen Image 跑在本地 GPU 上，应与其它本地模型（例如 SDXL）共用同一把
	 * {@link ResourceLock}，避免并发抢显存。不设置时不做额外加锁。
	 * </p>
	 *
	 * @param lock   资源锁，可为 null
	 * @param amount 每次操作占用的资源量，小于等于 0 时按 1 处理
	 * @return 当前实例
	 */
	public QwenImageApi setResourceLock(ResourceLock lock, int amount) {
		this.resourceLock = lock;
		this.resourceAmount = amount <= 0 ? 1 : amount;
		return this;
	}

	/** 设置默认上传子目录。 */
	public QwenImageApi setUploadSubfolder(String uploadSubfolder) {
		this.uploadSubfolder = uploadSubfolder == null || uploadSubfolder.trim().isEmpty() ? "qwen"
				: uploadSubfolder.trim();
		return this;
	}

	/** 设置等待超时（毫秒）。 */
	public QwenImageApi setTimeoutMillis(long timeoutMillis) {
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		return this;
	}

	/** 设置轮询间隔（毫秒）。 */
	public QwenImageApi setPollIntervalMillis(long pollIntervalMillis) {
		this.pollIntervalMillis = Math.max(100L, pollIntervalMillis);
		return this;
	}

	/** 覆盖 UNet 文件名。 */
	public QwenImageApi setUnetName(String unetName) {
		this.unetName = requireText(unetName, DEFAULT_UNET);
		return this;
	}

	/** 覆盖文本编码器文件名。 */
	public QwenImageApi setClipName(String clipName) {
		this.clipName = requireText(clipName, DEFAULT_CLIP);
		return this;
	}

	/** 覆盖 VAE 文件名。 */
	public QwenImageApi setVaeName(String vaeName) {
		this.vaeName = requireText(vaeName, DEFAULT_VAE);
		return this;
	}

	/** 覆盖极速草稿 LoRA 文件名。 */
	public QwenImageApi setFastLoraName(String fastLoraName) {
		this.fastLoraName = requireText(fastLoraName, DEFAULT_FAST_LORA);
		return this;
	}

	private static String requireText(String value, String fallback) {
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	// ==================== 文生图 / 极速草稿 ====================

	/**
	 * 使用默认参数文生图：768×1024、25 步、CFG 1.0、随机种子、euler/simple。
	 *
	 * @param prompt 提示词
	 * @return 生成图片
	 * @throws IOException 服务不可用或生成失败
	 */
	public List<ImageResult> textToImage(String prompt) throws IOException {
		return textToImage(prompt, DEFAULT_NEGATIVE, 768, 1024, DEFAULT_STEPS, DEFAULT_CFG, randomSeed(), 1024, false,
				true);
	}

	/**
	 * 文生图/极速草稿。
	 *
	 * @param prompt 提示词
	 * @param fast   true 使用 8 步加速 LoRA（固定 steps=8、cfg=1.0）
	 * @return 生成图片
	 * @throws IOException 服务不可用或生成失败
	 */
	public List<ImageResult> textToImage(String prompt, boolean fast) throws IOException {
		return textToImage(prompt, DEFAULT_NEGATIVE, 768, fast ? 768 : 1024,
				fast ? FAST_STEPS : DEFAULT_STEPS, DEFAULT_CFG, randomSeed(), 1024, fast, true);
	}

	/**
	 * 文生图完整参数。
	 *
	 * @param prompt      提示词
	 * @param negative    负面提示词，空串使用默认
	 * @param width       宽
	 * @param height      高
	 * @param steps       步数（fast 时强制 8）
	 * @param cfg         CFG（fast 时强制 1.0）
	 * @param seed        种子
	 * @param resolution  参考图缩放基准（文生图也传给 TextEncode 节点）
	 * @param fast        是否使用 8 步加速 LoRA
	 * @param freeAfter   提交前是否标记任务结束后卸载模型
	 * @return 生成图片
	 * @throws IOException 服务不可用或生成失败
	 */
	public List<ImageResult> textToImage(String prompt, String negative, int width, int height, int steps,
			float cfg, long seed, int resolution, boolean fast, boolean freeAfter) throws IOException {
		JsonObject graph = buildTextToImage(prompt, negative, width, height, steps, cfg, seed, resolution, fast);
		return run(graph, freeAfter);
	}

	/**
	 * 构造文生图工作流（不提交），便于调试或自行改写参数。
	 */
	public JsonObject buildTextToImage(String prompt, String negative, int width, int height, int steps,
			float cfg, long seed, int resolution, boolean fast) {
		JsonObject graph = loaders();
		String modelNode = "1";
		int effectiveSteps = steps;
		float effectiveCfg = cfg;
		if (fast) {
			graph.add("12", loraLoader(fastLoraName));
			modelNode = "12";
			effectiveSteps = 8;
			effectiveCfg = 1.0f;
		}
		graph.add("4", textEncoder(prompt, negative, resolution));
		graph.add("5", emptyLatent(width, height));
		graph.add("9", sampler(ref(modelNode, 0), ref("4", 0), ref("4", 1), ref("5", 0),
				effectiveSteps, effectiveCfg, seed));
		graph.add("10", vaeDecode());
		graph.add("11", saveImage());
		return graph;
	}

	// ==================== 改图 / 多图参考 ====================

	/**
	 * 单图改图：先上传参考图，再用编辑指令生成。
	 *
	 * @param image       参考图
	 * @param instruction 编辑指令（中文可用）
	 * @return 生成图片
	 * @throws IOException 上传或生成失败
	 */
	public List<ImageResult> edit(InputImage image, String instruction) throws IOException {
		return edit(image, instruction, DEFAULT_NEGATIVE, DEFAULT_STEPS, DEFAULT_CFG, randomSeed(), 1024, false, true);
	}

	/**
	 * 单图改图完整参数。
	 */
	public List<ImageResult> edit(InputImage image, String instruction, String negative, int steps,
			float cfg, long seed, int resolution, boolean fast, boolean freeAfter) throws IOException {
		if (image == null) {
			throw new IllegalArgumentException("缺少参考图");
		}
		ResourcePermit permit = acquire();
		try {
			String name = uploadImageUnlocked(image);
			return runUnlocked(buildEdit(Collections.singletonList(name), instruction, negative, steps, cfg, seed,
					resolution, fast), freeAfter);
		} finally {
			release(permit);
		}
	}

	/**
	 * 多图参考：先上传全部参考图，再按图1、图2…序号引用。
	 *
	 * @param images 参考图，最多 10 张
	 * @param prompt 指令/提示词
	 * @return 生成图片
	 * @throws IOException 上传或生成失败
	 */
	public List<ImageResult> reference(List<InputImage> images, String prompt) throws IOException {
		return reference(images, prompt, DEFAULT_NEGATIVE, DEFAULT_STEPS, DEFAULT_CFG, randomSeed(), 1024, false, true);
	}

	/**
	 * 多图参考完整参数。
	 */
	public List<ImageResult> reference(List<InputImage> images, String prompt, String negative, int steps,
			float cfg, long seed, int resolution, boolean fast, boolean freeAfter) throws IOException {
		if (images == null || images.isEmpty()) {
			throw new IllegalArgumentException("缺少参考图");
		}
		if (images.size() > MAX_REFERENCE_IMAGES) {
			throw new IllegalArgumentException("参考图最多 " + MAX_REFERENCE_IMAGES + " 张");
		}
		ResourcePermit permit = acquire();
		try {
			List<String> names = new ArrayList<>(images.size());
			for (InputImage image : images) {
				names.add(uploadImageUnlocked(image));
			}
			return runUnlocked(buildEdit(names, prompt, negative, steps, cfg, seed, resolution, fast), freeAfter);
		} finally {
			release(permit);
		}
	}

	/**
	 * 构造改图/参考图工作流（不提交）。
	 *
	 * @param uploadedImageNames 已上传的参考图名（可带 {@code qwen/} 前缀）
	 * @param instruction        编辑指令/提示词
	 * @param negative           负面提示词
	 * @param steps              步数
	 * @param cfg                CFG
	 * @param seed               种子
	 * @param resolution         参考图缩放基准
	 * @param fast               是否使用极速 LoRA
	 */
	public JsonObject buildEdit(List<String> uploadedImageNames, String instruction, String negative, int steps,
			float cfg, long seed, int resolution, boolean fast) {
		JsonObject graph = loaders();
		String modelNode = "1";
		int effectiveSteps = steps;
		float effectiveCfg = cfg;
		if (fast) {
			graph.add("12", loraLoader(fastLoraName));
			modelNode = "12";
			effectiveSteps = 8;
			effectiveCfg = 1.0f;
		}
		JsonObject encoder = textEncoder(instruction, negative, resolution);
		int index = 0;
		if (uploadedImageNames != null) {
			for (String name : uploadedImageNames) {
				if (name == null || name.trim().isEmpty()) {
					continue;
				}
				if (++index > MAX_REFERENCE_IMAGES) {
					throw new IllegalArgumentException("参考图最多 " + MAX_REFERENCE_IMAGES + " 张");
				}
				String nodeId = String.valueOf(200 + index);
				JsonObject loadImage = node("LoadImage");
				loadImage.getAsJsonObject("inputs").addProperty("image", name.trim());
				graph.add(nodeId, loadImage);
				encoder.getAsJsonObject("inputs").add("image_" + index, ref(nodeId, 0));
			}
		}
		if (index == 0) {
			throw new IllegalArgumentException("缺少参考图");
		}
		graph.add("4", encoder);
		graph.add("9", sampler(ref(modelNode, 0), ref("4", 0), ref("4", 1), ref("4", 2),
				effectiveSteps, effectiveCfg, seed));
		graph.add("10", vaeDecode());
		graph.add("11", saveImage());
		return graph;
	}

	// ==================== 上传 / 提交 / 轮询 / 下载 ====================

	/**
	 * 上传参考图到 ComfyUI 的 input 目录。
	 *
	 * @param image 图片（文件名与字节）
	 * @return 节点里应填写的图片名，例如 {@code qwen/ref.png}
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

	/** 上传参考图（调用方已持有资源锁）。 */
	private String uploadImageUnlocked(InputImage image) throws IOException {
		if (image == null || image.data == null || image.data.length == 0) {
			throw new IllegalArgumentException("参考图不能为空");
		}
		String filename = image.filename == null || image.filename.trim().isEmpty() ? "image.png"
				: new File(image.filename.trim()).getName();
		String boundary = "----QwenImageBoundary" + UUID.randomUUID().toString().replace("-", "");
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
			throw new IOException("Qwen Image 上传参考图失败 HTTP " + code + "：" + body);
		}
		JsonObject json = parseJsonObject(body, "上传参考图");
		String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : "";
		String subfolder = json.has("subfolder") && !json.get("subfolder").isJsonNull()
				? json.get("subfolder").getAsString() : "";
		if (name.isEmpty()) {
			throw new IOException("Qwen Image 上传参考图失败：响应缺少 name");
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
			throw new IOException("Qwen Image 提交失败：响应缺少 prompt_id");
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
					throw new IOException("Qwen Image 生成失败：" + status);
				}
				if (status == null || !status.has("completed") || status.get("completed").getAsBoolean()) {
					return entry;
				}
			}
			sleep(pollIntervalMillis);
		}
		throw new IOException("Qwen Image 生成超时（" + timeoutMillis + "ms）：" + promptId);
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
	 * 下载 history 条目里全部输出图片。
	 *
	 * @param historyEntry {@link #waitFor(String)} 的结果
	 * @return 图片列表，永不为 null
	 * @throws IOException 下载失败
	 */
	public List<ImageResult> downloadImages(JsonObject historyEntry) throws IOException {
		List<ImageResult> results = new ArrayList<>();
		if (historyEntry == null || !historyEntry.has("outputs") || !historyEntry.get("outputs").isJsonObject()) {
			return results;
		}
		for (java.util.Map.Entry<String, JsonElement> node : historyEntry.getAsJsonObject("outputs").entrySet()) {
			if (!node.getValue().isJsonObject()) {
				continue;
			}
			JsonElement images = node.getValue().getAsJsonObject().get("images");
			if (images == null || !images.isJsonArray()) {
				continue;
			}
			for (JsonElement imageElement : images.getAsJsonArray()) {
				if (!imageElement.isJsonObject()) {
					continue;
				}
				JsonObject image = imageElement.getAsJsonObject();
				String filename = image.has("filename") ? image.get("filename").getAsString() : "";
				String subfolder = image.has("subfolder") ? image.get("subfolder").getAsString() : "";
				String type = image.has("type") ? image.get("type").getAsString() : "output";
				if (filename.isEmpty()) {
					continue;
				}
				String query = "?filename=" + enc(filename) + "&subfolder=" + enc(subfolder) + "&type=" + enc(type);
				byte[] data = new HttpRequestBuilder(baseUrl).url("/view" + query)
						.header("Accept", "*/*").readTimeout(120000)
						.get().readBytes();
				results.add(new ImageResult(filename, subfolder, type, data));
			}
		}
		return results;
	}

	/**
	 * 完整执行一次工作流：按需标记卸载 → 提交 → 轮询 → 下载。
	 *
	 * @param graph     工作流
	 * @param freeAfter 是否在提交前标记任务结束后卸载模型
	 * @return 生成图片
	 * @throws IOException 任何一步失败
	 */
	public List<ImageResult> run(JsonObject graph, boolean freeAfter) throws IOException {
		ResourcePermit permit = acquire();
		try {
			return runUnlocked(graph, freeAfter);
		} finally {
			release(permit);
		}
	}

	/** 执行工作流（调用方已持有资源锁）。 */
	private List<ImageResult> runUnlocked(JsonObject graph, boolean freeAfter) throws IOException {
		if (freeAfter) {
			try {
				freeUnlocked();
			} catch (Exception ex) {
				// /free 只是显存优化，接口可能返回空响应或失败，不应阻断出图
				System.err.println("[QwenImage] 标记显存释放失败：" + ex.getMessage());
			}
		}
		String promptId = submit(graph);
		return downloadImages(waitFor(promptId));
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

	// ==================== 工作流节点 ====================

	private JsonObject loaders() {
		JsonObject graph = new JsonObject();
		JsonObject unet = node("UnetLoaderGGUF");
		unet.getAsJsonObject("inputs").addProperty("unet_name", unetName);
		graph.add("1", unet);
		JsonObject clip = node("CLIPLoader");
		JsonObject clipInputs = clip.getAsJsonObject("inputs");
		clipInputs.addProperty("clip_name", clipName);
		clipInputs.addProperty("type", "qwen_image");
		clipInputs.addProperty("device", "default");
		graph.add("2", clip);
		JsonObject vae = node("VAELoader");
		vae.getAsJsonObject("inputs").addProperty("vae_name", vaeName);
		graph.add("3", vae);
		return graph;
	}

	private JsonObject loraLoader(String loraName) {
		JsonObject node = node("LoraLoaderModelOnly");
		JsonObject inputs = node.getAsJsonObject("inputs");
		inputs.add("model", ref("1", 0));
		inputs.addProperty("lora_name", loraName);
		inputs.addProperty("strength_model", 1.0);
		return node;
	}

	private JsonObject textEncoder(String prompt, String negative, int resolution) {
		JsonObject node = node("TextEncodeQwenImage21");
		JsonObject inputs = node.getAsJsonObject("inputs");
		inputs.add("clip", ref("2", 0));
		inputs.add("vae", ref("3", 0));
		inputs.addProperty("prompt", prompt == null ? "" : prompt);
		inputs.addProperty("negative_prompt",
				negative == null || negative.trim().isEmpty() ? DEFAULT_NEGATIVE : negative);
		inputs.addProperty("resolution", resolution);
		return node;
	}

	private JsonObject emptyLatent(int width, int height) {
		JsonObject node = node("EmptyLatentImage");
		JsonObject inputs = node.getAsJsonObject("inputs");
		inputs.addProperty("width", width);
		inputs.addProperty("height", height);
		inputs.addProperty("batch_size", 1);
		return node;
	}

	private JsonObject sampler(JsonArray model, JsonArray positive, JsonArray negative, JsonArray latent,
			int steps, float cfg, long seed) {
		JsonObject node = node("KSampler");
		JsonObject inputs = node.getAsJsonObject("inputs");
		inputs.add("model", model);
		inputs.add("positive", positive);
		inputs.add("negative", negative);
		inputs.add("latent_image", latent);
		inputs.addProperty("seed", seed);
		inputs.addProperty("steps", steps);
		inputs.addProperty("cfg", cfg);
		inputs.addProperty("sampler_name", "euler");
		inputs.addProperty("scheduler", "simple");
		inputs.addProperty("denoise", 1.0);
		return node;
	}

	private JsonObject vaeDecode() {
		JsonObject node = node("VAEDecode");
		JsonObject inputs = node.getAsJsonObject("inputs");
		inputs.add("samples", ref("9", 0));
		inputs.add("vae", ref("3", 0));
		return node;
	}

	private JsonObject saveImage() {
		JsonObject node = node("SaveImage");
		JsonObject inputs = node.getAsJsonObject("inputs");
		inputs.add("images", ref("10", 0));
		inputs.addProperty("filename_prefix", "qwen21");
		return node;
	}

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

	// ==================== 工具方法 ====================

	/** 解析 JSON 对象响应；空响应/JSON null 时给出明确错误，而不是 Gson 的 “Not a JSON Object: null”。 */
	private static JsonObject parseJsonObject(String body, String action) throws IOException {
		if (body == null || body.trim().isEmpty()) {
			throw new IOException("Qwen Image " + action + "失败：服务返回空响应");
		}
		String trimmed = body.trim();
		if ("null".equalsIgnoreCase(trimmed)) {
			throw new IOException("Qwen Image " + action + "失败：服务返回 null");
		}
		try {
			JsonElement element = JsonParser.parseString(trimmed);
			if (element == null || !element.isJsonObject()) {
				throw new IOException("Qwen Image " + action + "失败：响应不是 JSON 对象");
			}
			return element.getAsJsonObject();
		} catch (IOException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new IOException("Qwen Image " + action + "失败：响应无法解析为 JSON：" + trimmed, ex);
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
			return resourceLock.acquire(resourceAmount);
		} catch (RuntimeException ex) {
			throw new IOException("Qwen Image 等待资源锁失败：" + ex.getMessage(), ex);
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
			throw new IOException("Qwen Image 等待被中断", ex);
		}
	}

	/** 一张输入参考图。 */
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

	/** 一张生成图片。 */
	public static final class ImageResult {
		/** 服务端文件名。 */
		public final String filename;
		/** 输出子目录。 */
		public final String subfolder;
		/** 输出类型（通常 output）。 */
		public final String type;
		/** 图片字节。 */
		public final byte[] data;

		public ImageResult(String filename, String subfolder, String type, byte[] data) {
			this.filename = filename;
			this.subfolder = subfolder;
			this.type = type;
			this.data = data;
		}
	}
}
