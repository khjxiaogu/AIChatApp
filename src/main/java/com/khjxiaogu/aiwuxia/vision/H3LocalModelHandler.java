package com.khjxiaogu.aiwuxia.vision;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.tools.TaskPriority;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.Response;
import com.khjxiaogu.aiwuxia.voice.LocalModelEta;

/**
 * MiniMax H3（本地 ComfyUI）的请求处理器。
 * <p>
 * 只负责“把服务器请求翻译成 ComfyUI 调用”，不再持有网络连接；连接、重连、进度上报由
 * {@link LocalModelClient} 统一管理。资源锁按 H3 规则使用：先等到至少
 * {@value #MIN_RESOURCE_AMOUNT} 份，再一次性占满当前剩余资源；Foley 配音固定占
 * {@value #FOLEY_RESOURCE_AMOUNT} 份。
 * </p>
 */
public final class H3LocalModelHandler {

	/** H3 生视频的最低资源要求。 */
	public static final int MIN_RESOURCE_AMOUNT = H3VideoApi.MIN_RESOURCE_AMOUNT;
	/** Foley 配音固定占用的资源份数。 */
	public static final int FOLEY_RESOURCE_AMOUNT = 10;
	/** 未传入资源锁时自建锁的容量：至少要能容纳 H3 的最低要求。 */
	private static final int FALLBACK_LOCK_CAPACITY = 12;
	/** ComfyUI 健康检查路径（与 Qwen 共用同一个进程）。 */
	private static final String HEALTH_PATH = "/queue";
	/** ComfyUI 生成可能较慢，探活超时放宽。 */
	private static final int HEALTH_TIMEOUT_MS = 10_000;

	private H3LocalModelHandler() {
	}

	/**
	 * 创建处理器。
	 *
	 * @param lock    与其它本地模型共享的资源锁，null 时新建一个容量 12 的锁
	 * @param baseUrl ComfyUI 地址，空串表示按系统属性/环境变量/默认地址
	 * @return 请求处理器
	 */
	public static RequestHandler create(ResourceLock lock, String baseUrl) {
		final ResourceLock effectiveLock = lock == null ? new ResourceLock(FALLBACK_LOCK_CAPACITY) : lock;
		final String resolvedBaseUrl = H3VideoApi.resolveBaseUrl(baseUrl);
		return new RequestHandler() {
			@Override
			public Response handle(JsonObject request) {
				return H3LocalModelHandler.handle(request, effectiveLock, resolvedBaseUrl, ProgressReporter.NOOP);
			}

			@Override
			public Response handle(JsonObject request, ProgressReporter progress) {
				return H3LocalModelHandler.handle(request, effectiveLock, resolvedBaseUrl, progress);
			}
		};
	}

	/** ComfyUI 健康检查（与 Qwen 同一个进程）。 */
	public static LocalModelClient.HealthCheck healthCheck(String baseUrl) {
		return LocalModelClient.httpHealthCheck(H3VideoApi.resolveBaseUrl(baseUrl) + HEALTH_PATH,
				HEALTH_TIMEOUT_MS);
	}

	/**
	 * 处理一次视频请求：占用资源锁 → 调 ComfyUI → 把 mp4 编码成 JSON。
	 * <p>
	 * 等待资源锁期间上报 {@code queued}（排队）；请求正式下发给 ComfyUI 时上报 {@code running}
	 * （执行中），生成各阶段再附带进度，服务端据此延长任务超时。错误通过 {@code error} 字段回传，
	 * 便于工具端展示真实原因。
	 * </p>
	 */
	private static Response handle(JsonObject request, ResourceLock lock, String baseUrl,
			ProgressReporter progress) {
		H3VideoApi.ModelTier tier = H3VideoApi.ModelTier.fromString(JsonConfig.getTrimmed(request, "model"));
		int steps = JsonConfig.getInt(request, "steps", tier.defaultSteps());
		boolean sparse = JsonConfig.getBool(request, "sparse", true);
		// 视频 MCP 一律禁用音频；请求显式打开时也只按 false 处理
		boolean audio = false;
		String action = JsonConfig.getTrimmed(request, "action");
		progress.queued("等待本地模型资源");
		long queuedAt = System.currentTimeMillis();
		ResourcePermit permit = null;
		try {
			// Foley 配音固定占 10 份；int8 档至少 8 份后占满剩余；NVFP4 档固定占 10 份
			permit = ("foley".equals(action) || "foley_segment".equals(action))
					? lock.acquire(FOLEY_RESOURCE_AMOUNT, TaskPriority.LOW)
					: tier.resourceAmount() > 0
							? lock.acquire(tier.resourceAmount(), TaskPriority.LOW)
							: lock.acquireAtLeast(MIN_RESOURCE_AMOUNT, TaskPriority.LOW);
			if (request.has("reqid")) {
				System.out.println("[H3-LocalModel] 已获取资源锁（排队 "
						+ (System.currentTimeMillis() - queuedAt) + "ms，占用 " + permit.amount() + " 份）reqid="
						+ request.get("reqid").getAsString());
			}
			H3VideoApi api = new H3VideoApi(baseUrl).setModelTier(tier).setSteps(steps).setSparse(sparse)
					.setAudioEnabled(audio);
			api.setProgressListener(progress::progress);
			if (!action.isEmpty() && !"video".equals(action) && !"text2video".equals(action)
					&& !"image2video".equals(action) && !"extract_frame".equals(action)
					&& !"video_duration".equals(action) && !"foley".equals(action)
					&& !"foley_segment".equals(action)) {
				throw new IllegalArgumentException("unknown h3 action: " + action);
			}
			if ("extract_frame".equals(action)) {
				// 视频截帧：上传视频 → LoadVideo/ImageFromBatch → 返回 png
				H3VideoApi.InputImage video = readMedia(request, "video", "frame.mp4");
				int frame = JsonConfig.getInt(request, "frame", 0);
				// 截帧与视频生成是不同 API，可分别配置预计耗时
				progress.running("已下发 ComfyUI", LocalModelEta.resolve(15_000L,
						LocalModelEta.propertyName("h3", "extract_frame"), "h3FrameEtaMs"));
				H3VideoApi.ImageResult image = api.extractFrameUnlocked(video, frame);
				return new Response(image.data, "png");
			}
			if ("video_duration".equals(action)) {
				// 只读视频时长，供视频配音计费；返回 JSON 文本
				H3VideoApi.InputImage video = readMedia(request, "video", "video.mp4");
				// 只读时长，通常比生成快得多；单独给 video_duration 留覆盖项
				progress.running("已下发 ComfyUI", LocalModelEta.resolve(15_000L,
						LocalModelEta.propertyName("h3", "video_duration"), "h3FrameEtaMs"));
				double duration = api.probeVideoDuration(video);
				JsonObject result = new JsonObject();
				result.addProperty("duration", duration);
				return new Response(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "json");
			}
			if ("foley".equals(action) || "foley_segment".equals(action)) {
				// HunyuanVideo-Foley 视频配音：整段或局部
				String foleyPrompt = JsonConfig.getTrimmed(request, "prompt");
				if (foleyPrompt.isEmpty()) {
					throw new IllegalArgumentException("missing foley prompt");
				}
				H3VideoApi.InputImage video = readMedia(request, "video", "video.mp4");
				String negative = JsonConfig.getTrimmed(request, "negative");
				// foley / foley_segment 是不同 API：段式配音可能比整段快，可分别配置
				progress.running("已下发 ComfyUI", LocalModelEta.resolve(300_000L,
						LocalModelEta.propertyName("h3", action), "h3FoleyEtaMs"));
				H3VideoApi.FoleyResult result;
				if ("foley_segment".equals(action)) {
					double start = JsonConfig.getDouble(request, "start", 0d);
					double length = JsonConfig.getDouble(request, "length", 0d);
					result = api.foleySegment(video, start, length, foleyPrompt,
							negative.isEmpty() ? null : negative, true);
				} else {
					result = api.foley(video, foleyPrompt,
							negative.isEmpty() ? null : negative, true);
				}
				if (result.video != null && result.video.data != null && result.video.data.length > 0) {
					// 时长从 foley 图的 PreviewAny(10) 节点读取，随结果格式一起回传，避免再调一次 API
					String format = result.duration > 0d
							? String.format(java.util.Locale.ROOT, "mp4;duration=%.3f", result.duration)
							: "mp4";
					return new Response(result.video.data, format);
				}
				throw new IllegalStateException("ComfyUI 没有返回配音视频");
			}
			String prompt = JsonConfig.getTrimmed(request, "prompt");
			if (prompt.isEmpty()) {
				throw new IllegalArgumentException("missing h3 prompt");
			}
			int width = JsonConfig.getInt(request, "width", 864);
			int height = JsonConfig.getInt(request, "height", 480);
			double seconds = JsonConfig.getDouble(request, "seconds", 5d);
			int frames = JsonConfig.getInt(request, "frames", H3VideoApi.snapFrames(seconds));
			long seed = JsonConfig.getLong(request, "seed", H3VideoApi.randomSeed());
			H3VideoApi.InputImage firstFrame = readMedia(request, "first_frame", "frame.png");
			H3VideoApi.InputImage lastFrame = readMedia(request, "last_frame", "frame.png");
			H3VideoApi.InputImage firstVideo = readMedia(request, "first_video", "first.mp4");
			H3VideoApi.InputImage lastVideo = readMedia(request, "last_video", "last.mp4");
			int firstStart = JsonConfig.getInt(request, "first_start", 0);
			int firstLength = JsonConfig.getInt(request, "first_length", 0);
			int lastStart = JsonConfig.getInt(request, "last_start", 0);
			int lastLength = JsonConfig.getInt(request, "last_length", 0);
			H3VideoApi.VideoResult chosen;
			if (firstFrame != null && firstVideo != null) {
				throw new IllegalArgumentException("首帧不能同时提供图片和视频");
			}
			if (lastFrame != null && lastVideo != null) {
				throw new IllegalArgumentException("尾帧不能同时提供图片和视频");
			}
			boolean hasFirst = firstFrame != null || firstVideo != null;
			boolean hasLast = lastFrame != null || lastVideo != null;
			if (hasLast && !hasFirst) {
				throw new IllegalArgumentException("提供尾帧时必须同时提供首帧");
			}
			if (firstVideo != null || lastVideo != null) {
				// 视频生视频：首/尾可分别是图片或视频，由 h3-clips 工作流在 ComfyUI 内取帧/拼接
				progress.running("已下发 ComfyUI", LocalModelEta.resolve(180_000L,
						LocalModelEta.propertyName("h3", "video"), "h3EtaMs"));
				H3VideoApi.H3Output output = api.videoFromSources(prompt, width, height, frames, seed,
						firstFrame, firstVideo, firstStart, firstLength,
						lastFrame, lastVideo, lastStart, lastLength, true);
				chosen = output.stitched != null ? output.stitched : firstVideo(output.videos);
			} else {
				// 纯图片首尾帧：普通 H3 图生视频
				progress.running("已下发 ComfyUI", LocalModelEta.resolve(180_000L,
						LocalModelEta.propertyName("h3", "video"), "h3EtaMs"));
				chosen = firstVideo(api.video(prompt, width, height, frames, seed, firstFrame, lastFrame, true));
			}
			// 结果直接回传 mp4 原始字节：由连接器按二进制分块发送，不再包成 Base64 的 JSON
			if (chosen != null && chosen.data != null && chosen.data.length > 0) {
				return new Response(chosen.data, "mp4");
			}
			throw new IllegalStateException("ComfyUI 没有返回视频文件");
		} catch (Throwable ex) {
			// 参数错误、资源锁容量不足、ComfyUI 失败等：放进 error 字段回传，
			// 否则上层只会上报 “handler error”，工具端看不到真实原因
			JsonObject error = new JsonObject();
			error.addProperty("error", message(ex));
			return new Response(error.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "json");
		} finally {
			if (permit != null) {
				permit.release();
			}
		}
	}

	/** 取第一个有数据的视频结果。 */
	private static H3VideoApi.VideoResult firstVideo(List<H3VideoApi.VideoResult> videos) {
		if (videos != null) {
			for (H3VideoApi.VideoResult video : videos) {
				if (video != null && video.data != null && video.data.length > 0) {
					return video;
				}
			}
		}
		return null;
	}

	/** 取最内层异常的描述，便于回传给工具端。 */
	private static String message(Throwable ex) {
		Throwable cause = ex;
		while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		if (cause == null) {
			return "unknown h3 error";
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}

	/** 读取请求里的媒体（图片/视频，Base64 解码）。 */
	private static H3VideoApi.InputImage readMedia(JsonObject request, String field, String defaultName) {
		JsonElement element = request.get(field);
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonObject image = element.getAsJsonObject();
		String data = JsonConfig.getTrimmed(image, "data");
		if (data.isEmpty()) {
			return null;
		}
		String filename = JsonConfig.getTrimmed(image, "filename");
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(data);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("invalid h3 " + field + " data", ex);
		}
		return new H3VideoApi.InputImage(bytes, filename.isEmpty() ? defaultName : filename);
	}

}
