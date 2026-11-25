package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.modelproxy.AttachmentFetcher;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelEta;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.Response;
import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCall;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCodec;
import com.khjxiaogu.aiwuxia.modelproxy.api.QwenParams;
import com.khjxiaogu.aiwuxia.modelproxy.cache.LocalModelFileCache;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.tools.TaskPriority;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * Qwen-Image 2.1（本地 ComfyUI）的请求处理器。
 * <p>
 * 只负责“把服务器请求翻译成 ComfyUI 调用”，不再持有任何网络连接：连接、重连、进度上报
 * 由 {@link com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient} 统一管理。
 * </p>
 */
public final class QwenLocalModelHandler {

	/** Qwen 生图占用 6 份资源。 */
	public static final int RESOURCE_AMOUNT = 6;
	/** ComfyUI 健康检查路径。 */
	private static final String HEALTH_PATH = "/queue";
	/** ComfyUI 生成可能较慢，探活超时放宽。 */
	private static final int HEALTH_TIMEOUT_MS = 10_000;

	private QwenLocalModelHandler() {
	}

	/**
	 * 创建处理器。
	 *
	 * @param lock    与其它本地模型共享的资源锁，null 时新建一个容量 6 的锁
	 * @param baseUrl ComfyUI 地址，空串表示按系统属性/环境变量/默认地址
	 * @return 请求处理器
	 */
	public static RequestHandler create(ResourceLock lock, String baseUrl) {
		final ResourceLock effectiveLock = lock == null ? new ResourceLock(RESOURCE_AMOUNT) : lock;
		final String resolvedBaseUrl = resolveBaseUrl(baseUrl);
		return new RequestHandler() {
			@Override
			public Response handle(JsonObject request) {
				return QwenLocalModelHandler.handle(request, readImages(request), effectiveLock, resolvedBaseUrl,
						ProgressReporter.NOOP);
			}

			@Override
			public Response handle(JsonObject request, ProgressReporter progress) {
				return QwenLocalModelHandler.handle(request, readImages(request), effectiveLock, resolvedBaseUrl,
						progress);
			}

			@Override
			public Response handle(LocalModelCall<?> call, ProgressReporter progress, AttachmentFetcher fetcher) {
				return handleTyped(call, effectiveLock, resolvedBaseUrl, progress, fetcher);
			}
		};
	}

	/** 类型化调用：把参考图附件 id 拉回成字节。 */
	private static Response handleTyped(LocalModelCall<?> call, ResourceLock lock, String baseUrl,
			ProgressReporter progress, AttachmentFetcher fetcher) {
		QwenParams params = call.requireParams(QwenParams.class);
		List<QwenImageApi.InputImage> inputs = new ArrayList<>();
		for (String id : params.images) {
			AttachmentRef ref = call.attachment(id);
			if (ref == null) {
				throw new IllegalArgumentException("缺少附件：" + id);
			}
			if (fetcher == null) {
				throw new IllegalArgumentException("没有附件拉取器：" + id);
			}
			try {
				byte[] bytes = fetcher.fetch(call.reqid, ref);
				String filename = ref.filename == null || ref.filename.isEmpty() ? "image.png" : ref.filename;
				inputs.add(new QwenImageApi.InputImage(bytes, filename));
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
		JsonObject request = LocalModelCodec.encodeLegacyJson(call);
		return handle(request, inputs, lock, baseUrl, progress);
	}

	/** ComfyUI 健康检查，未配置地址时返回 null。 */
	public static LocalModelClient.HealthCheck healthCheck(String baseUrl) {
		return LocalModelClient.httpHealthCheck(resolveBaseUrl(baseUrl) + HEALTH_PATH, HEALTH_TIMEOUT_MS);
	}

	/**
	 * 解析 ComfyUI 地址：参数优先，其次 {@code -DqwenImageUrl}、环境变量 {@code QWEN_API}，
	 * 最后落到 {@link QwenImageApi#DEFAULT_BASE_URL}。
	 *
	 * @param raw 显式传入的地址，可为空
	 * @return 规整后的地址（带协议、无末尾斜杠）
	 */
	public static String resolveBaseUrl(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			value = System.getProperty("qwenImageUrl", "").trim();
		}
		if (value.isEmpty()) {
			String env = System.getenv("QWEN_API");
			value = env == null ? "" : env.trim();
		}
		if (value.isEmpty()) {
			value = QwenImageApi.DEFAULT_BASE_URL;
		}
		if (!value.startsWith("http://") && !value.startsWith("https://")) {
			value = "http://" + value;
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	/** 处理一次生成请求：占用资源锁 → 调 ComfyUI → 把图片编码成 JSON。 */
	private static Response handle(JsonObject request, List<QwenImageApi.InputImage> inputs, ResourceLock lock,
			String baseUrl, ProgressReporter progress) {
		progress.queued("等待本地模型资源");
		long queuedAt = System.currentTimeMillis();
		try (ResourcePermit permit = lock.acquire(RESOURCE_AMOUNT, TaskPriority.HIGH)) {
			System.out.println("[Qwen-LocalModel] 已获取资源锁（排队 " + (System.currentTimeMillis() - queuedAt)
					+ "ms）" + (request.has("reqid") ? " reqid=" + request.get("reqid").getAsString() : ""));
			QwenImageApi api = new QwenImageApi(baseUrl);
			String action = JsonConfig.getTrimmed(request, "action");
			String prompt = JsonConfig.getTrimmed(request, "prompt");
			if (prompt.isEmpty()) {
				throw new IllegalArgumentException("missing qwen prompt");
			}
			String negative = JsonConfig.getTrimmed(request, "negative");
			boolean fast = JsonConfig.getBool(request, "fast", false);
			int resolution = JsonConfig.getInt(request, "resolution", 1024);
			int steps = fast ? QwenImageApi.FAST_STEPS : JsonConfig.getInt(request, "steps", QwenImageApi.DEFAULT_STEPS);
			float cfg = fast ? QwenImageApi.DEFAULT_CFG
					: (float) JsonConfig.getDouble(request, "cfg", QwenImageApi.DEFAULT_CFG);
			long seed = JsonConfig.getLong(request, "seed", QwenImageApi.randomSeed());
			List<QwenImageApi.ImageResult> images;
			// 排队结束、正式把请求下发给 ComfyUI 的那一刻才切到“执行中”
			// 不同动作（text2image / edit）可以分别配置预计耗时，未配置时回落到 qwenEtaMs
			progress.running("已下发 ComfyUI", LocalModelEta.resolve(60_000L,
					LocalModelEta.propertyName("qwen", action), "qwenEtaMs"));
			if ("text2image".equals(action)) {
				int width = JsonConfig.getInt(request, "width", 768);
				int height = JsonConfig.getInt(request, "height", 1024);
				images = api.textToImage(prompt, negative, width, height, steps, cfg, seed, resolution, fast, true);
			} else if ("edit".equals(action)) {
				if (inputs.isEmpty()) {
					throw new IllegalArgumentException("missing qwen reference image");
				}
				images = inputs.size() == 1
						? api.edit(inputs.get(0), prompt, negative, steps, cfg, seed, resolution, fast, true)
						: api.reference(inputs, prompt, negative, steps, cfg, seed, resolution, fast, true);
			} else {
				throw new IllegalArgumentException("unknown qwen action: " + action);
			}
			// 结果直接回传图片原始字节：由连接器按二进制分块发送，不再包成 Base64 的 JSON
			for (QwenImageApi.ImageResult image : images) {
				if (image != null && image.data != null && image.data.length > 0) {
					return new Response(image.data, imageFormat(image.filename));
				}
			}
			throw new IllegalStateException("ComfyUI 没有返回图片文件");
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	/** 根据 ComfyUI 输出的文件名推断图片格式，默认 png。 */
	private static String imageFormat(String filename) {
		String lower = filename == null ? "" : filename.toLowerCase();
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return "jpeg";
		}
		if (lower.endsWith(".webp")) {
			return "webp";
		}
		return "png";
	}

	/** 读取请求里的参考图（Base64 解码）。 */
	private static List<QwenImageApi.InputImage> readImages(JsonObject request) {
		List<QwenImageApi.InputImage> images = new java.util.ArrayList<>();
		com.google.gson.JsonElement element = request.get("images");
		if (element == null || !element.isJsonArray()) {
			return images;
		}
		for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonObject()) {
				continue;
			}
			JsonObject image = item.getAsJsonObject();
			String data = JsonConfig.getTrimmed(image, "data");
			if (data.isEmpty()) {
				continue;
			}
			String filename = JsonConfig.getTrimmed(image, "filename");
			byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(data);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("invalid qwen reference image data", ex);
			}
			// 输入文件也写入部署侧缓存，下次同一份内容再用时本地已有副本
			LocalModelFileCache cache = LocalModelFileCache.shared();
			if (cache != null) {
				LocalModelFileCache.StoreResult stored = cache.store(bytes);
				if (stored != null) {
					System.out.println("[LocalModelCache] 内联输入缓存："
							+ (stored.created ? "新写入" : "命中已存在") + " key=" + stored.key.substring(0, 12)
							+ "… 大小=" + bytes.length + "B");
				}
			}
			images.add(new QwenImageApi.InputImage(bytes,
					filename.isEmpty() ? "image.png" : filename));
		}
		return images;
	}

}
