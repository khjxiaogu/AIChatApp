package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.tools.TaskPriority;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.Response;
import com.khjxiaogu.aiwuxia.voice.LocalModelEta;

/**
 * SDXL（SD WebUI / A1111 / Forge）的请求处理器。
 * <p>
 * 服务器把 {@code {"path":...,"data":...}} 原样送进来，本处理器转成对 SD WebUI 的 POST；
 * 不再持有网络连接，连接与进度上报由 {@link LocalModelClient} 统一管理。
 * </p>
 */
public final class SDXLLocalModelHandler {

	/** SDXL 生成占用整把锁（12 份）。 */
	private static final int RESOURCE_AMOUNT = 12;
	/** SD WebUI（A1111 / Forge）提供的轻量探活接口。 */
	private static final String HEALTH_PATH = "/internal/ping";
	/** 生成图片时 SD WebUI 可能较慢，单次探活给足超时，避免误判。 */
	private static final int HEALTH_TIMEOUT_MS = 10_000;

	private SDXLLocalModelHandler() {
	}

	/**
	 * 创建处理器。
	 *
	 * @param lock 与其它本地模型共享的资源锁，null 时新建一个容量 12 的锁
	 * @return 请求处理器
	 */
	public static RequestHandler create(ResourceLock lock) {
		final ResourceLock effectiveLock = lock == null ? new ResourceLock(RESOURCE_AMOUNT) : lock;
		return new RequestHandler() {
			@Override
			public Response handle(JsonObject request) {
				return SDXLLocalModelHandler.handle(request, effectiveLock, ProgressReporter.NOOP);
			}

			@Override
			public Response handle(JsonObject request, ProgressReporter progress) {
				return SDXLLocalModelHandler.handle(request, effectiveLock, progress);
			}
		};
	}

	/** SD WebUI 健康检查；未配置 {@code sdwebuiUrl} 时返回 null（不启用）。 */
	public static LocalModelClient.HealthCheck healthCheck() {
		String sdwebuiUrl = System.getProperty("sdwebuiUrl");
		if (sdwebuiUrl == null || sdwebuiUrl.trim().isEmpty()) {
			return null;
		}
		String base = sdwebuiUrl.trim();
		if (!base.startsWith("http://") && !base.startsWith("https://")) {
			base = "http://" + base;
		}
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return LocalModelClient.httpHealthCheck(base + HEALTH_PATH, HEALTH_TIMEOUT_MS);
	}

	/** 把服务器的 SD WebUI 请求转发出去，并把响应字节原样回传。 */
	private static Response handle(JsonObject request, ResourceLock lock, ProgressReporter progress) {
		String reqid = request.has("reqid") && !request.get("reqid").isJsonNull()
				? request.get("reqid").getAsString() : "";
		String path = request.has("path") && !request.get("path").isJsonNull()
				? request.get("path").getAsString() : "";
		String sdwebuiUrl = System.getProperty("sdwebuiUrl");
		progress.queued("等待本地模型资源");
		long queuedAt = System.currentTimeMillis();
		try (ResourcePermit permit = lock.acquire(RESOURCE_AMOUNT, TaskPriority.HIGH)) {
			long waited = System.currentTimeMillis() - queuedAt;
			Gson gson = new Gson();
			// 排队结束、正式把请求下发给 SD WebUI 的那一刻才切到“执行中”
			// 没有真实进度，给一个预计总耗时：前端按 10% 步进到 90%，避免长时间空转。
			// 不同 API 的耗时不同：API 级配置优先，其次模型级 sdxlEtaMs，最后按 API 取默认值
			String api = sdxlApi(path);
			progress.running("已下发 SD WebUI", LocalModelEta.resolve(
					"interrogate".equals(api) ? 15_000L : 45_000L,
					LocalModelEta.propertyName("sdxl", api), "sdxlEtaMs"));
			String body = gson.fromJson(request.get("data").toString(), String.class);
			long sentAt = System.currentTimeMillis();
			System.out.println("[SDXL] 已获取资源锁（排队 " + waited + "ms），请求 SD WebUI：reqid=" + reqid
					+ " path=" + path + " 请求体=" + (body == null ? 0 : body.length()) + "B");
			HttpRequestBuilder.OpenedConnectionBuilder connection = HttpRequestBuilder
					.create("http", sdwebuiUrl)
					.header("Accept", "application/json")
					.header("Content-Type", "application/json; utf-8")
					.url(path)
					.post()
					.send(body);
			byte[] data = connection.readBytes();
			System.out.println("[SDXL] SD WebUI 返回：reqid=" + reqid + " path=" + path + " HTTP="
					+ connection.getResponseCode() + " 响应=" + (data == null ? 0 : data.length) + "B 用时="
					+ (System.currentTimeMillis() - sentAt) + "ms");
			return new Response(data, "json");
		} catch (IOException ex) {
			System.err.println("[SDXL] 请求 SD WebUI 失败：reqid=" + reqid + " path=" + path + " url="
					+ sdwebuiUrl + " 错误=" + ex);
			ex.printStackTrace();
			throw new RuntimeException(ex);
		} catch (RuntimeException ex) {
			// JSON 解析失败、请求字段缺失等非 IO 问题也明确打出来，避免只看到协议层笼统的错误
			System.err.println("[SDXL] 处理请求出错：reqid=" + reqid + " path=" + path + " 错误=" + ex);
			ex.printStackTrace();
			throw ex;
		}
	}

	/**
	 * 按请求路径识别 SD WebUI 的 API 名，用于选择对应的预计耗时。
	 *
	 * @param path 请求路径，例如 {@code /sdapi/v1/txt2img}
	 * @return {@code txt2img} / {@code img2img} / {@code interrogate}；未知路径返回 null
	 */
	private static String sdxlApi(String path) {
		String value = path == null ? "" : path.trim().toLowerCase(java.util.Locale.ROOT);
		if (value.contains("interrogate")) {
			return "interrogate";
		}
		if (value.contains("img2img")) {
			return "img2img";
		}
		if (value.contains("txt2img")) {
			return "txt2img";
		}
		return null;
	}
}
