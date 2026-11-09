package com.khjxiaogu.aiwuxia.voice;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;

/**
 * SVS（歌声转换）本地模型客户端。
 * <p>
 * 以 {@link ModelType#SVS} 连接服务器的 {@code /kh$localModelDeploy}，收到 SVS 请求后转给
 * localemp.py（HTTP）处理。调用 localemp 前使用 {@link LocalModel#registerTask(String)}
 * 注册任务，等 localemp 把音频回调到服务器后，再把结果通过 WebSocket 回复服务器。
 * </p>
 * <p>
 * 因此本客户端应与服务器运行在同一个 JVM 中（服务器侧的 {@link LocalModel} 负责回调匹配）；
 * localemp 的 {@code TARGET_URL} 保持指向服务器的 {@code /kh$localModelDeployData}。
 * </p>
 * <p>
 * 支持 {@code localemp_api.md} 的参数：{@code url}、{@code voice}（兼容旧 {@code timbre}）、
 * {@code reqid}、{@code name}、{@code pitch}、{@code filter_radius}（兼容旧 {@code filter}）、
 * {@code protect}（兼容旧 {@code strength}）、{@code do_split}（默认 true）。
 * 服务地址取构造参数或 {@code -DsvsHost=host:port}，默认 {@code 127.0.0.1:8064}。
 * </p>
 */
public class SvsLocalModelClient extends LocalModelClient {

	/** localemp.py 默认地址。 */
	public static final String DEFAULT_SVS_HOST = "127.0.0.1:8064";
	/** localemp 处理长音频可能较慢，客户端占用 4 份资源。 */
	private static final int SVS_RESOURCE_AMOUNT = 4;

	public SvsLocalModelClient(URI serverUri, ResourceLock lock, boolean autoReconnect, long reconnectIntervalMs) {
		this(serverUri, lock, autoReconnect, reconnectIntervalMs,
				System.getProperty("svsHost", DEFAULT_SVS_HOST));
	}

	/**
	 * @param serverUri           服务器 WebSocket 地址
	 * @param lock                与其它本地模型共享的资源锁，null 时新建一个容量 4 的锁
	 * @param autoReconnect       是否自动重连
	 * @param reconnectIntervalMs 重连间隔
	 * @param svsHost             localemp.py 地址，例如 {@code 127.0.0.1:8064}
	 */
	public SvsLocalModelClient(URI serverUri, ResourceLock lock, boolean autoReconnect, long reconnectIntervalMs,
			String svsHost) {
		super(serverUri, Map.of("authorization", "Bearer " + System.getProperty("localVoiceToken", "")),
				handler(lock, svsHost == null || svsHost.trim().isEmpty() ? DEFAULT_SVS_HOST : svsHost.trim()),
				Set.of(ModelType.SVS), autoReconnect, reconnectIntervalMs);
	}

	private static RequestHandler handler(ResourceLock lock, String svsHost) {
		final ResourceLock effectiveLock = lock == null ? new ResourceLock(SVS_RESOURCE_AMOUNT) : lock;
		return request -> handle(request, effectiveLock, svsHost);
	}

	/** 把服务器的 SVS 请求转给 localemp.py；结果由 localemp 回调完成本地任务后再回复。 */
	private static Response handle(JsonObject request, ResourceLock lock, String svsHost) {
		try (ResourcePermit permit = lock.acquire(SVS_RESOURCE_AMOUNT)) {
			String url = string(request, "url");
			if (url.isEmpty()) {
				throw new IllegalArgumentException("missing svs url");
			}
			String voice = firstNonEmpty(string(request, "voice"), string(request, "timbre"));
			if (voice.isEmpty()) {
				throw new IllegalArgumentException("missing svs voice");
			}
			String reqid = string(request, "reqid");
			if (reqid.isEmpty()) {
				throw new IllegalArgumentException("missing svs reqid");
			}
			// 与 MusicMcp 一样用 LocalModel.registerTask 等回调，但必须用独立的本地 reqid：
			// 服务器已经用原始 reqid 注册了等待任务，同一个 key 会把它覆盖，导致 MCP 一直等到超时。
			String localReqid = reqid + "-svs-" + UUID.randomUUID();
			CompletableFuture<ModelGenerationResult> callback = LocalModel.registerTask(localReqid);
			String name = string(request, "name");
			int pitch = integer(request, "pitch", 0);
			int filterRadius = integer(request, "filter_radius", integer(request, "filter", 0));
			double protect = number(request, "protect", number(request, "strength", 0d));
			boolean doSplit = bool(request, "do_split", true);

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
			String response = HttpRequestBuilder.create("http", svsHost).url(query.toString())
					.get().readString();
			System.out.println("[SVS-LocalModelClient] " + response);
			// localemp 只在回调成功后返回 200，此时本地任务应已由回调完成
			ModelGenerationResult result = callback.get(5, TimeUnit.MINUTES);
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

	private static String string(JsonObject request, String key) {
		if (request == null || !request.has(key) || request.get(key).isJsonNull()) {
			return "";
		}
		return request.get(key).getAsString().trim();
	}

	private static String firstNonEmpty(String first, String second) {
		return first != null && !first.isEmpty() ? first : second == null ? "" : second;
	}

	private static int integer(JsonObject request, String key, int fallback) {
		String value = string(request, key);
		if (value.isEmpty()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("invalid svs " + key + ": " + value, ex);
		}
	}

	private static double number(JsonObject request, String key, double fallback) {
		String value = string(request, key);
		if (value.isEmpty()) {
			return fallback;
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("invalid svs " + key + ": " + value, ex);
		}
	}

	private static boolean bool(JsonObject request, String key, boolean fallback) {
		String value = string(request, key).toLowerCase();
		if (value.isEmpty()) {
			return fallback;
		}
		switch (value) {
		case "true":
		case "1":
		case "yes":
		case "on":
			return true;
		case "false":
		case "0":
		case "no":
		case "off":
			return false;
		default:
			throw new IllegalArgumentException("invalid svs " + key + ": " + value);
		}
	}

	private static String encode(String value) throws IOException {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
	}
}
