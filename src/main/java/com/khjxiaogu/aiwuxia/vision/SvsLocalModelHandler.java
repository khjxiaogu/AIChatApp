package com.khjxiaogu.aiwuxia.vision;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.Response;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;

/**
 * SVS（歌声转换）的请求处理器。
 * <p>
 * 收到 SVS 请求后转给 localemp.py（HTTP）处理：先通过 {@link LocalModel#registerTask(String)}
 * 注册本地任务，等 localemp 把音频回调到服务器的 {@code /kh$localModelDeployData} 后，
 * 再把结果回传。因此本处理器所在进程必须能收到服务器的回调。
 * </p>
 * <p>
 * 支持 {@code localemp_api.md} 的参数：{@code url}、{@code voice}（兼容旧 {@code timbre}）、
 * {@code reqid}、{@code name}、{@code pitch}、{@code filter_radius}（兼容旧 {@code filter}）、
 * {@code protect}（兼容旧 {@code strength}）、{@code do_split}（默认 true）。
 * 地址取构造参数或 {@code -DsvsHost=host:port}，默认 {@code 127.0.0.1:8064}。
 * </p>
 */
public final class SvsLocalModelHandler {

	/** localemp.py 默认地址。 */
	public static final String DEFAULT_SVS_HOST = "127.0.0.1:8064";
	/** localemp 处理长音频可能较慢，客户端占用 4 份资源。 */
	private static final int SVS_RESOURCE_AMOUNT = 4;
	/** 等 localemp 回调的超时。 */
	private static final long CALLBACK_TIMEOUT_MINUTES = 5;

	private SvsLocalModelHandler() {
	}

	/**
	 * 创建处理器。
	 *
	 * @param lock    与其它本地模型共享的资源锁，null 时新建一个容量 4 的锁
	 * @param svsHost localemp.py 地址，空串表示按 {@code -DsvsHost} / 默认地址
	 * @return 请求处理器
	 */
	public static RequestHandler create(ResourceLock lock, String svsHost) {
		final ResourceLock effectiveLock = lock == null ? new ResourceLock(SVS_RESOURCE_AMOUNT) : lock;
		final String host = svsHost == null || svsHost.trim().isEmpty()
				? System.getProperty("svsHost", DEFAULT_SVS_HOST).trim() : svsHost.trim();
		final String resolvedHost = host.isEmpty() ? DEFAULT_SVS_HOST : host;
		return new RequestHandler() {
			@Override
			public Response handle(JsonObject request) {
				return SvsLocalModelHandler.handle(request, effectiveLock, resolvedHost, ProgressReporter.NOOP);
			}

			@Override
			public Response handle(JsonObject request, ProgressReporter progress) {
				return SvsLocalModelHandler.handle(request, effectiveLock, resolvedHost, progress);
			}
		};
	}

	/** 把服务器的 SVS 请求转给 localemp.py；结果由 localemp 回调完成本地任务后再回复。 */
	private static Response handle(JsonObject request, ResourceLock lock, String svsHost,
			ProgressReporter progress) {
		progress.queued("等待本地模型资源");
		try (ResourcePermit permit = lock.acquire(SVS_RESOURCE_AMOUNT)) {
			String url = JsonConfig.getTrimmed(request, "url");
			if (url.isEmpty()) {
				throw new IllegalArgumentException("missing svs url");
			}
			String voice = firstNonEmpty(JsonConfig.getTrimmed(request, "voice"), JsonConfig.getTrimmed(request, "timbre"));
			if (voice.isEmpty()) {
				throw new IllegalArgumentException("missing svs voice");
			}
			String reqid = JsonConfig.getTrimmed(request, "reqid");
			if (reqid.isEmpty()) {
				throw new IllegalArgumentException("missing svs reqid");
			}
			// 必须用独立的本地 reqid：服务器已经用原始 reqid 注册了等待任务，
			// 同一个 key 会把它覆盖，导致 MCP 一直等到超时。
			String localReqid = reqid + "-svs-" + UUID.randomUUID();
			CompletableFuture<ModelGenerationResult> callback = LocalModel.registerTask(localReqid);
			String name = JsonConfig.getTrimmed(request, "name");
			int pitch = JsonConfig.getInt(request, "pitch", 0);
			int filterRadius = JsonConfig.getInt(request, "filter_radius", JsonConfig.getInt(request, "filter", 0));
			double protect = JsonConfig.getDouble(request, "protect", JsonConfig.getDouble(request, "strength", 0d));
			boolean doSplit = JsonConfig.getBool(request, "do_split", true);

			StringBuilder query = new StringBuilder("?url=").append(encode(url))
					.append("&voice=").append(encode(voice))
					.append("&reqid=").append(encode(localReqid));
			if (!name.isEmpty()) {
				query.append("&name=").append(encode(name));
			}
			query.append("&pitch=").append(pitch)
					.append("&filter_radius=").append(filterRadius)
					.append("&protect=").append(protect)
					.append("&do_split=").append(doSplit);
			// 排队结束、正式把请求下发给 localemp 的那一刻才切到“执行中”
			progress.running("已下发 localemp");
			String response = HttpRequestBuilder.create("http", svsHost).url(query.toString())
					.get().readString();
			System.out.println("[SVS-LocalModel] " + response);
			// localemp 只在回调成功后返回 200，此时本地任务应已由回调完成
			ModelGenerationResult result = callback.get(CALLBACK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
			if (result == null || result.bodyData == null || result.bodyData.length == 0) {
				throw new IllegalStateException("local SVS callback returned no audio");
			}
			return new Response(result.bodyData, result.format);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static String firstNonEmpty(String first, String second) {
		return first != null && !first.isEmpty() ? first : second == null ? "" : second;
	}

	private static String encode(String value) throws IOException {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
	}
}
