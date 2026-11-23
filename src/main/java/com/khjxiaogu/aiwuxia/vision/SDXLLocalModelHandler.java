package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.modelproxy.AttachmentFetcher;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.Response;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelEta;
import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCall;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCodec;
import com.khjxiaogu.aiwuxia.modelproxy.api.SdxlAction;
import com.khjxiaogu.aiwuxia.modelproxy.api.SdxlParams;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.tools.TaskPriority;

/**
 * SDXL（SD WebUI / A1111 / Forge）的请求处理器。
 * <p>
 * 与 H3 / Qwen 的处理器同一套架构：服务器只下发 {@link SdxlParams}，本处理器先按需从服务器
 * 拉取参考图附件（{@link AttachmentFetcher}），再用 {@link SDXLApi} 调用 SD WebUI，
 * 最后把结果还原成图片字节回传。地址取 {@code -DsdwebuiUrl=127.0.0.1:7860}。
 * </p>
 */
public final class SDXLLocalModelHandler {

	/** SDXL 生成占用整把锁（12 份）。 */
	private static final int RESOURCE_AMOUNT = 12;
	/** SD WebUI（A1111 / Forge）提供的轻量探活接口。 */
	private static final String HEALTH_PATH = "/internal/ping";
	/** 生成图片时 SD WebUI 可能较慢，单次探活给足超时，避免误判。 */
	private static final int HEALTH_TIMEOUT_MS = 10_000;
	/** 反推比生图快得多，用于区分预计耗时。 */
	private static final long INTERROGATE_ETA_MS = 15_000L;
	/** 生图的默认预计耗时。 */
	private static final long GENERATE_ETA_MS = 45_000L;

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
				return handle(LocalModelCodec.decode(request), ProgressReporter.NOOP, null);
			}

			@Override
			public Response handle(LocalModelCall<?> call, ProgressReporter progress, AttachmentFetcher fetcher) {
				return SDXLLocalModelHandler.handle(call, effectiveLock, progress, fetcher);
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

	/** 拉取参考图 → 执行一次 SDXL 调用 → 回传结果字节。 */
	private static Response handle(LocalModelCall<?> call, ResourceLock lock, ProgressReporter progress,
			AttachmentFetcher fetcher) {
		SdxlParams params = call.requireParams(SdxlParams.class);
		params.validate();
		String reqid = call.reqid;
		String action = params.action.wireName;
		String sdwebuiUrl = System.getProperty("sdwebuiUrl");
		progress.queued("等待本地模型资源");
		long queuedAt = System.currentTimeMillis();
		try (ResourcePermit permit = lock.acquire(RESOURCE_AMOUNT, TaskPriority.HIGH)) {
			long waited = System.currentTimeMillis() - queuedAt;
			// 参考图按需拉取：只用到图的反推/图生图才会真正下载
			byte[] imageBytes = readAttachment(call, params.image, fetcher);
			// 排队结束、正式把请求下发给 SD WebUI 的那一刻才切到“执行中”
			progress.running("已下发 SD WebUI", LocalModelEta.resolve(
					params.action == SdxlAction.INTERROGATE ? INTERROGATE_ETA_MS : GENERATE_ETA_MS,
					LocalModelEta.propertyName("sdxl", action), "sdxlEtaMs"));
			long sentAt = System.currentTimeMillis();
			System.out.println("[SDXL] 已获取资源锁（排队 " + waited + "ms），请求 SD WebUI：reqid=" + reqid
					+ " action=" + action + (params.image == null ? "" : " 附件=" + params.image));
			byte[] data = SDXLApi.dispatch(params, imageBytes, SDXLApi.direct(sdwebuiUrl));
			String format = params.action == SdxlAction.INTERROGATE ? "json" : SDXLApi.imageFormat(data);
			System.out.println("[SDXL] SD WebUI 返回：reqid=" + reqid + " action=" + action + " 结果="
					+ (data == null ? 0 : data.length) + "B/" + format + " 用时="
					+ (System.currentTimeMillis() - sentAt) + "ms");
			return new Response(data, format);
		} catch (IOException ex) {
			System.err.println("[SDXL] 请求 SD WebUI 失败：reqid=" + reqid + " action=" + action + " url="
					+ sdwebuiUrl + " 错误=" + ex);
			ex.printStackTrace();
			throw new RuntimeException(ex);
		} catch (RuntimeException ex) {
			System.err.println("[SDXL] 处理请求出错：reqid=" + reqid + " action=" + action + " 错误=" + ex);
			ex.printStackTrace();
			throw ex;
		}
	}

	/** 若参数里的图片是附件，则从服务器拉取；否则返回 null（链接/Base64 兜底）。 */
	private static byte[] readAttachment(LocalModelCall<?> call, String image, AttachmentFetcher fetcher)
			throws IOException {
		if (image == null || image.isEmpty()) {
			return null;
		}
		AttachmentRef ref = call.attachment(image);
		if (ref == null) {
			return null;
		}
		if (fetcher == null) {
			throw new IOException("没有附件拉取器，无法获取参考图：" + image);
		}
		return fetcher.fetch(call.reqid, ref);
	}
}
