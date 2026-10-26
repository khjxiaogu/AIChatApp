package com.khjxiaogu.aiwuxia.vision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

/**
 * 即梦 Seedance 2.5 视频生成客户端（火山方舟 Ark API）。
 * <p>
 * 与旧版火山视觉服务（AK/SK 签名 + {@code CVSync2AsyncSubmitTask}）不同，
 * Seedance 2.5 走方舟的 Bearer API：
 * </p>
 * <ol>
 * <li>{@code POST /api/v3/contents/generations/tasks} 提交任务，返回任务 id；</li>
 * <li>{@code GET /api/v3/contents/generations/tasks/{id}} 轮询，成功后从
 * {@code content.video_url} 取视频地址。</li>
 * </ol>
 * <p>
 * 参考图以 {@code image_url} 内容块提交，{@code role} 为 {@code reference_image}，
 * 因此多张参考图是“角色/场景参考”，不是首尾帧。
 * </p>
 * <p>
 * 计费口径：视频 token = 输出宽 × 输出高 × 输出帧率 × 输出时长 / 1024，
 * 官方价格 70 元/百万视频 token；本站配额按 2 元/百万等效 token 折算，
 * 因此计入配额的等效 token = 视频 token × 35（见 {@link SeedanceVideoUsage}）。
 * </p>
 * <p>
 * 配置来自 {@code config/image-generation.json}（与 Seedream 生图共用）：
 * </p>
 * <table border="1">
 * <caption>配置字段</caption>
 * <tr><th>字段</th><th>默认值</th><th>含义</th></tr>
 * <tr><td>apiKey（或 arkApiKey）</td><td>无</td><td>方舟 API Key，必填</td></tr>
 * <tr><td>videoBaseUrl</td><td>https://ark.cn-beijing.volces.com/api/v3</td><td>方舟 API 地址</td></tr>
 * <tr><td>videoModel</td><td>doubao-seedance-2-5-260628</td><td>模型 id 或推理接入点 id</td></tr>
 * <tr><td>videoDuration</td><td>10</td><td>时长秒数（4-15；未配置、0 或负数按 10 秒，超过 15 秒按 15 秒）</td></tr>
 * <tr><td>videoResolution</td><td>480p</td><td>480p / 720p / 1080p</td></tr>
 * <tr><td>videoRatio</td><td>9:16</td><td>画幅，默认竖版 9:16</td></tr>
 * <tr><td>videoFps</td><td>24</td><td>计费用输出帧率；Seedance 默认 24fps，实际不同时改这里</td></tr>
 * <tr><td>videoWidth / videoHeight</td><td>按分辨率/画幅推导</td><td>计费用输出尺寸，可选覆盖</td></tr>
 * <tr><td>videoGenerateAudio</td><td>true</td><td>是否生成音频</td></tr>
 * <tr><td>videoWatermark</td><td>false</td><td>是否加水印</td></tr>
 * <tr><td>videoPollIntervalMs</td><td>5000</td><td>轮询间隔毫秒</td></tr>
 * <tr><td>videoMaxPollAttempts</td><td>360</td><td>最大轮询次数（默认约 30 分钟）</td></tr>
 * </table>
 */
public class SeedanceVideoGenerator {

	/** 默认方舟 API 地址。 */
	public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
	/** 默认 Seedance 2.5 模型。 */
	public static final String DEFAULT_MODEL = "doubao-seedance-2-5-260628";

	private final String apiKey;
	private final String baseUrl;
	private final String model;
	private final int duration;
	private final String resolution;
	private final String ratio;
	private final int width;
	private final int height;
	private final int fps;
	private final boolean generateAudio;
	private final boolean watermark;
	private final long pollIntervalMs;
	private final int maxPollAttempts;

	/**
	 * 从 {@code image-generation.json} 构造客户端。
	 * <p>
	 * 缺失 API Key 时不在构造期报错：离线测试和仅使用生图的环境仍可正常启动，
	 * 真正提交视频任务时再返回明确的失败信息。
	 * </p>
	 *
	 * @param config 配置对象
	 */
	public SeedanceVideoGenerator(JsonObject config) {
		JsonObject cfg = config == null ? new JsonObject() : config;
		String configuredKey = firstString(cfg, "apiKey", "arkApiKey", null);
		if (configuredKey == null) {
			configuredKey = primitiveString(cfg, "videoApiKey");
		}
		String key = configuredKey == null ? null : configuredKey.trim();
		// 允许配置里直接粘贴 "Bearer xxx"，避免请求头出现两个 Bearer
		if (key != null && key.regionMatches(true, 0, "Bearer ", 0, 7)) {
			key = key.substring(7).trim();
		}
		this.apiKey = key;
		this.baseUrl = trimTrailingSlash(firstString(cfg, "videoBaseUrl", "arkBaseUrl", DEFAULT_BASE_URL));
		this.model = firstString(cfg, "videoModel", "arkVideoModel", DEFAULT_MODEL);
		int configuredDuration = intValue(cfg, "videoDuration", 10);
		// 本工具需求：单条视频最大 15 秒；未配置、0、负数都按默认 10 秒，超过 15 秒按 15 秒处理
		this.duration = Math.max(4, Math.min(15, configuredDuration > 0 ? configuredDuration : 10));
		this.resolution = normalizeResolution(firstString(cfg, "videoResolution", "480p"));
		this.ratio = firstString(cfg, "videoRatio", "9:16");
		int[] derivedSize = dimensions(this.ratio, resolutionShortSide(this.resolution));
		int configuredWidth = intValue(cfg, "videoWidth", 0);
		int configuredHeight = intValue(cfg, "videoHeight", 0);
		if (configuredWidth > 0 && configuredHeight > 0) {
			this.width = configuredWidth;
			this.height = configuredHeight;
		} else {
			this.width = derivedSize[0];
			this.height = derivedSize[1];
		}
		// 计费用帧率；Seedance 默认 24fps，实际输出不同时可通过 videoFps 校准
		int configuredFps = intValue(cfg, "videoFps", 24);
		this.fps = Math.max(1, Math.min(120, configuredFps > 0 ? configuredFps : 24));
		this.generateAudio = boolValue(cfg, "videoGenerateAudio", true);
		this.watermark = boolValue(cfg, "videoWatermark", false);
		this.pollIntervalMs = Math.max(1000, longValue(cfg, "videoPollIntervalMs", 5000));
		this.maxPollAttempts = (int) Math.min(Integer.MAX_VALUE,
				Math.max(1, longValue(cfg, "videoMaxPollAttempts", 720*3)));
	}

	/** API Key 是否已配置。 */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.trim().isEmpty();
	}

	/** 当前使用的模型 id。 */
	public String getModel() {
		return model;
	}

	/** 配置里的默认视频时长（秒），供工具在调用者未指定时使用。 */
	public int getDuration() {
		return duration;
	}

	/** 计费用输出宽度。 */
	public int getWidth() {
		return width;
	}

	/** 计费用输出高度。 */
	public int getHeight() {
		return height;
	}

	/** 计费用输出帧率。 */
	public int getFps() {
		return fps;
	}

	/**
	 * 按本次实际时长创建视频用量对象。
	 *
	 * @param duration 视频时长（秒）
	 * @return 视频用量
	 */
	public SeedanceVideoUsage createUsage(int duration) {
		return new SeedanceVideoUsage(width, height, fps, duration);
	}

	/**
	 * 提交一次视频生成任务并轮询到完成，使用配置里的默认时长。
	 *
	 * @param referenceImageUrls 参考图链接列表，可为 null/空
	 * @param prompt             视频提示词
	 * @return 异步结果，成功时为视频地址
	 */
	public CompletableFuture<String> generateVideo(List<String> referenceImageUrls, String prompt) {
		return generateVideo(referenceImageUrls, prompt, duration);
	}

	/**
	 * 提交一次视频生成任务并轮询到完成。
	 *
	 * @param referenceImageUrls 参考图链接列表，可为 null/空
	 * @param prompt             视频提示词
	 * @param duration           视频时长（秒），4-15
	 * @return 异步结果，成功时为视频地址
	 */
	public CompletableFuture<String> generateVideo(List<String> referenceImageUrls, String prompt, int duration) {
		return generateVideo(referenceImageUrls, prompt, duration, null);
	}

	/**
	 * 提交一次视频生成任务并轮询到完成，成功后回报视频用量。
	 *
	 * @param referenceImageUrls 参考图链接列表，可为 null/空
	 * @param prompt             视频提示词
	 * @param duration           视频时长（秒），4-15
	 * @param usage              用量接收器，可为 null；成功生成后回调一次
	 * @return 异步结果，成功时为视频地址
	 */
	public CompletableFuture<String> generateVideo(List<String> referenceImageUrls, String prompt, int duration,
			Consumer<UsageIntf<?>> usage) {
		if (duration < 4 || duration > 15) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
					"视频时长必须是 4-15 秒的整数，实际：" + duration));
		}
		if (!isConfigured()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"缺少 Seedance API Key：请在 config/image-generation.json 中配置 apiKey（火山方舟 API Key）"));
		}
		if (prompt == null || prompt.trim().isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("视频提示词不能为空"));
		}
		final int requestedDuration = duration;
		return CompletableFuture.supplyAsync(() -> {
			try {
				String taskId = submitTask(referenceImageUrls, prompt.trim(), requestedDuration);
				String videoUrl = pollResult(taskId);
				if (usage != null) {
					try {
						usage.accept(createUsage(requestedDuration));
					} catch (Throwable t) {
						// 计费上报失败不能反过来让已经生成的视频失败
						t.printStackTrace();
					}
				}
				return videoUrl;
			} catch (RuntimeException e) {
				throw e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Seedance 视频生成被中断", e);
			} catch (Exception e) {
				throw new RuntimeException("Seedance 视频生成失败：" + e.getMessage(), e);
			}
		});
	}

	/**
	 * 组装提交任务请求体，使用配置里的默认时长。
	 *
	 * @param referenceImageUrls 参考图链接，可为 null/空
	 * @param prompt             提示词
	 * @return 请求体
	 */
	JsonObject buildRequest(List<String> referenceImageUrls, String prompt) {
		return buildRequest(referenceImageUrls, prompt, duration);
	}

	/**
	 * 组装提交任务请求体。包内可见，便于离线校验。
	 *
	 * @param referenceImageUrls 参考图链接，可为 null/空
	 * @param prompt             提示词
	 * @param duration           视频时长（秒）
	 * @return 请求体
	 */
	JsonObject buildRequest(List<String> referenceImageUrls, String prompt, int duration) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", prompt);
		content.add(text);
		if (referenceImageUrls != null) {
			for (String url : referenceImageUrls) {
				if (url == null || url.trim().isEmpty()) {
					continue;
				}
				JsonObject image = new JsonObject();
				image.addProperty("type", "image_url");
				JsonObject imageUrl = new JsonObject();
				imageUrl.addProperty("url", url.trim());
				image.add("image_url", imageUrl);
				image.addProperty("role", "reference_image");
				content.add(image);
			}
		}
		body.add("content", content);
		if (duration > 0) {
			body.addProperty("duration", duration);
		}
		body.addProperty("resolution", resolution);
		body.addProperty("ratio", ratio);
		body.addProperty("generate_audio", generateAudio);
		body.addProperty("watermark", watermark);
		return body;
	}

	/** 提交任务，返回任务 id。 */
	private String submitTask(List<String> referenceImageUrls, String prompt, int duration) throws IOException {
		JsonObject response = request("POST", "/contents/generations/tasks",
				buildRequest(referenceImageUrls, prompt, duration));
		String taskId = firstString(response, "id", "task_id", null);
		if (taskId == null && response.has("data") && response.get("data").isJsonObject()) {
			taskId = firstString(response.getAsJsonObject("data"), "id", "task_id", null);
		}
		if (taskId == null || taskId.trim().isEmpty()) {
			throw new RuntimeException("Seedance 提交任务未返回任务 id：" + response);
		}
		System.out.println("[Seedance] 视频任务已提交：" + taskId + "（模型 " + model + "）");
		return taskId;
	}

	/** 轮询任务直到成功、失败或超时。 */
	private String pollResult(String taskId) throws Exception {
		for (int i = 0; i < maxPollAttempts; i++) {
			Thread.sleep(pollIntervalMs);
			JsonObject response = request("GET", "/contents/generations/tasks/" + taskId, null);
			JsonObject data = response.has("data") && response.get("data").isJsonObject()
					? response.getAsJsonObject("data") : response;
			String status = firstString(data, "status", "state", null);
			if (status == null) {
				status = firstString(response, "status", "state", null);
			}
			if (status == null && (response.has("error") || data.has("error"))) {
				throw new RuntimeException("Seedance 视频生成失败：" + errorMessage(data.has("error") ? data : response));
			}
			if ("succeeded".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)
					|| "done".equalsIgnoreCase(status)) {
				String videoUrl = extractVideoUrl(data);
				if (videoUrl == null) {
					videoUrl = extractVideoUrl(response);
				}
				if (videoUrl == null || videoUrl.trim().isEmpty()) {
					throw new RuntimeException("Seedance 任务已完成但没有返回视频地址：" + response);
				}
				return videoUrl;
			}
			if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)
					|| "canceled".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)
					|| "not_found".equalsIgnoreCase(status) || "deleted".equalsIgnoreCase(status)) {
				throw new RuntimeException("Seedance 视频生成" + status + "："
						+ errorMessage(data.has("error") ? data : response));
			}
		}
		throw new RuntimeException("Seedance 视频生成超时（" + maxPollAttempts + " 次轮询），任务 id：" + taskId);
	}

	/** 从响应里提取视频地址。 */
	private static String extractVideoUrl(JsonObject object) {
		if (object == null) {
			return null;
		}
		if (object.has("content") && object.get("content").isJsonObject()) {
			JsonObject content = object.getAsJsonObject("content");
			String url = firstString(content, "video_url", "videoUrl", null);
			if (url != null) {
				return url;
			}
			// 少数兼容网关把结果再包一层 content
			if (content.has("data") && content.get("data").isJsonObject()) {
				url = firstString(content.getAsJsonObject("data"), "video_url", "videoUrl", null);
				if (url != null) {
					return url;
				}
			}
		}
		if (object.has("output") && object.get("output").isJsonObject()) {
			String url = firstString(object.getAsJsonObject("output"), "video_url", "videoUrl", null);
			if (url != null) {
				return url;
			}
		}
		return firstString(object, "video_url", "videoUrl", null);
	}

	/** 从错误响应里取出可读信息。 */
	private static String errorMessage(JsonObject response) {
		if (response == null) {
			return "未知错误";
		}
		if (response.has("error")) {
			JsonElement error = response.get("error");
			if (error.isJsonObject()) {
				String message = firstString(error.getAsJsonObject(), "message", "msg", null);
				if (message != null) {
					return message;
				}
			}
			if (error.isJsonPrimitive()) {
				return error.getAsString();
			}
		}
		return firstString(response, "message", "msg", response.toString());
	}

	/**
	 * 发送一次 HTTP 请求并解析 JSON。
	 *
	 * @param method HTTP 方法
	 * @param path   相对 {@link #baseUrl} 的路径
	 * @param body   请求体，GET 时可为 null
	 * @return 响应 JSON
	 * @throws IOException 网络错误或非 2xx 响应
	 */
	private JsonObject request(String method, String path, JsonObject body) throws IOException {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(baseUrl + path);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod(method);
			connection.setConnectTimeout(15000);
			connection.setReadTimeout(60000);
			connection.setRequestProperty("Authorization", "Bearer " + apiKey);
			connection.setRequestProperty("Content-Type", "application/json");
			if (body != null) {
				connection.setDoOutput(true);
				byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
				try (OutputStream output = connection.getOutputStream()) {
					output.write(payload);
				}
			}
			int code = connection.getResponseCode();
			InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
			String text = readAll(stream);
			if (code < 200 || code >= 300) {
				throw new IOException("Seedance API HTTP " + code + "：" + text);
			}
			try {
				return JsonParser.parseString(text).getAsJsonObject();
			} catch (RuntimeException e) {
				throw new IOException("Seedance API 返回的不是 JSON：" + text, e);
			}
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** 读取全部文本。 */
	private static String readAll(InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line).append('\n');
			}
		}
		return builder.toString().trim();
	}

	/** 取第一个非空字符串字段（无别名）。 */
	private static String firstString(JsonObject object, String key, String fallback) {
		return firstString(object, key, null, fallback);
	}

	/** 取第一个非空字符串字段。 */
	private static String firstString(JsonObject object, String key, String alias, String fallback) {		String value = primitiveString(object, key);
		if (value == null || value.trim().isEmpty()) {
			value = primitiveString(object, alias);
		}
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	/** 读取字符串字段。 */
	private static String primitiveString(JsonObject object, String key) {
		if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}
		JsonElement element = object.get(key);
		return element.isJsonPrimitive() ? element.getAsString() : null;
	}

	/** 读取整数字段。 */
	private static int intValue(JsonObject object, String key, int fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return object.get(key).getAsInt();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** 读取长整数字段。 */
	private static long longValue(JsonObject object, String key, long fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return object.get(key).getAsLong();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** 读取布尔字段。 */
	private static boolean boolValue(JsonObject object, String key, boolean fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return object.get(key).getAsBoolean();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** 分辨率字符串转短边像素（480p/720p/1080p）。 */
	private static int resolutionShortSide(String resolution) {
		if (resolution == null) {
			return 720;
		}
		String value = resolution.trim().toLowerCase();
		if (value.endsWith("p")) {
			value = value.substring(0, value.length() - 1);
		}
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : 720;
		} catch (RuntimeException e) {
			return 720;
		}
	}

	/**
	 * 按画幅和短边推导计费尺寸。
	 * <p>
	 * 例：480p + 16:9 → 854×480（官方示例 5 秒 = 48037.5 token），
	 * 720p + 9:16 → 720×1280。比例解析失败或 adaptive 时按默认 9:16。
	 * 非整数边长向上取整，与官方 480p 16:9 的 854 宽一致。
	 * </p>
	 *
	 * @param ratio     画幅字符串，如 9:16
	 * @param shortSide 输出短边像素
	 * @return {宽, 高}
	 */
	private static int[] dimensions(String ratio, int shortSide) {
		double ratioWidth = 9d;
		double ratioHeight = 16d;
		if (ratio != null) {
			String[] parts = ratio.trim().split(":");
			if (parts.length == 2) {
				try {
					double width = Double.parseDouble(parts[0].trim());
					double height = Double.parseDouble(parts[1].trim());
					if (width > 0 && height > 0) {
						ratioWidth = width;
						ratioHeight = height;
					}
				} catch (RuntimeException e) {
					// 非“数字:数字”的画幅（如 adaptive）按默认 9:16 处理
				}
			}
		}
		int side = shortSide > 0 ? shortSide : 720;
		if (ratioWidth >= ratioHeight) {
			return new int[] { (int) Math.ceil(side * ratioWidth / ratioHeight), side };
		}
		return new int[] { side, (int) Math.ceil(side * ratioHeight / ratioWidth) };
	}

	/** 只保留受支持的分辨率。 */
	private static String normalizeResolution(String value) {
		if ("480p".equalsIgnoreCase(value) || "720p".equalsIgnoreCase(value) || "1080p".equalsIgnoreCase(value)) {
			return value.toLowerCase();
		}
		return "720p";
	}

	/** 去掉末尾斜杠。 */
	private static String trimTrailingSlash(String value) {
		String result = value == null || value.trim().isEmpty() ? DEFAULT_BASE_URL : value.trim();
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}
}
