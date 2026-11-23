package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * localemp.py（SVS / RVC 歌声转换）的 API 调用集合。
 * <p>
 * 从 {@code mcp.SvsMcp} 中抽出：MCP 只负责工具参数与结果展示，真正拼装 localemp 请求、
 * 注册回调任务并等待回调结果的逻辑集中在这里，由模型侧的 {@link SvsLocalModelHandler} 使用。
 * 聊天进程里的 SVS 工具不再直接访问 localemp，而是统一走 {@link LocalModel} 的 {@code svs}
 * 模型通道，由本类在模型服务进程内完成实际调用。
 * </p>
 * <p>
 * 请求参数按 {@code localemp_api.md}：{@code url}、{@code voice}（兼容旧 {@code timbre}）、
 * {@code reqid}、{@code name}（可选）、{@code pitch}（-24~24）、
 * {@code filter_radius}（兼容旧 {@code filter}，0~7）、{@code protect}（兼容旧 {@code strength}，
 * 0~1）、{@code do_split}（是否伴奏分离，默认 true）。
 * </p>
 * <p>
 * 服务地址取构造参数或 {@code -DsvsHost=host:port}，默认 {@value #DEFAULT_HOST}。
 * </p>
 */
public final class SvsApi {

	/** localemp.py 默认地址。 */
	public static final String DEFAULT_HOST = "127.0.0.1:8064";
	/** 等 localemp 回调的超时。 */
	private static final long CALLBACK_TIMEOUT_MINUTES = 5;

	/** localemp.py 地址，形如 {@code 127.0.0.1:8064}。 */
	private final String host;

	/** 使用 {@code -DsvsHost} 或默认地址。 */
	public SvsApi() {
		this(null);
	}

	/**
	 * @param host localemp.py 地址；为空时取 {@code -DsvsHost}，再回落到默认地址
	 */
	public SvsApi(String host) {
		String value = host == null || host.trim().isEmpty()
				? System.getProperty("svsHost", DEFAULT_HOST) : host;
		value = value == null ? "" : value.trim();
		this.host = value.isEmpty() ? DEFAULT_HOST : value;
	}

	/** @return 实际使用的 localemp.py 地址 */
	public String getHost() {
		return host;
	}

	/**
	 * 按语义请求调用 localemp.py 并等待回调结果。
	 * <p>
	 * 接受服务器下发的请求 JSON；{@code reqid} 只用于拼装本地回调 ID，避免与服务器已注册的
	 * 等待任务重名。
	 * </p>
	 *
	 * @param request 语义请求，至少包含 {@code url} 与 {@code voice}/{@code timbre}
	 * @return 转换后的音频
	 * @throws Exception 参数缺失、HTTP 失败、回调超时或回调没有音频
	 */
	public ModelGenerationResult convert(JsonObject request) throws Exception {
		if (request == null) {
			throw new IllegalArgumentException("missing svs request");
		}
		String url = JsonConfig.getTrimmed(request, "url");
		if (url.isEmpty()) {
			throw new IllegalArgumentException("missing svs url");
		}
		String voice = firstNonEmpty(JsonConfig.getTrimmed(request, "voice"),
				JsonConfig.getTrimmed(request, "timbre"));
		if (voice.isEmpty()) {
			throw new IllegalArgumentException("missing svs voice");
		}
		String name = JsonConfig.getTrimmed(request, "name");
		int pitch = JsonConfig.getInt(request, "pitch", 0);
		int filterRadius = JsonConfig.getInt(request, "filter_radius",
				JsonConfig.getInt(request, "filter", 0));
		double protect = JsonConfig.getDouble(request, "protect",
				JsonConfig.getDouble(request, "strength", 0d));
		boolean doSplit = JsonConfig.getBool(request, "do_split", true);
		return convert(url, voice, name, pitch, filterRadius, protect, doSplit,
				JsonConfig.getTrimmed(request, "reqid"));
	}

	/**
	 * 调用 localemp.py 并等待回调结果。
	 *
	 * @param url          要转换的音频链接
	 * @param voice        音色（localemp 模型名）
	 * @param name         输出文件名，可为空（为空时不传，使用临时文件）
	 * @param pitch        变调半音
	 * @param filterRadius 中值滤波半径
	 * @param protect      防撕裂保护
	 * @param doSplit      是否执行伴奏分离与合成
	 * @param requestId    服务器请求 ID，仅用于生成本地回调 ID 与日志
	 * @return 转换后的音频
	 * @throws Exception HTTP 失败、回调超时或回调没有音频
	 */
	public ModelGenerationResult convert(String url, String voice, String name, int pitch, int filterRadius,
			double protect, boolean doSplit, String requestId) throws Exception {
		// 必须用独立的本地 reqid：服务器已经用原始 reqid 注册了等待任务，
		// 同一个 key 会把它覆盖，导致 MCP 一直等到超时。
		String localReqid = (requestId == null || requestId.isEmpty() ? "svs" : requestId)
				+ "-svs-" + UUID.randomUUID();
		CompletableFuture<ModelGenerationResult> callback = LocalModel.registerTask(localReqid);
		String response;
		try {
			StringBuilder query = new StringBuilder("?url=").append(encode(url))
					.append("&voice=").append(encode(voice))
					.append("&reqid=").append(encode(localReqid));
			if (name != null && !name.isEmpty()) {
				query.append("&name=").append(encode(name));
			}
			query.append("&pitch=").append(pitch)
					.append("&filter_radius=").append(filterRadius)
					.append("&protect=").append(protect)
					.append("&do_split=").append(doSplit);
			response = HttpRequestBuilder.create("http", host).url(query.toString())
					.get().readString();
		} catch (Exception ex) {
			// HTTP 失败时服务不会回调，主动让等待方结束，避免本地任务一直挂着
			callback.completeExceptionally(ex);
			throw ex;
		}
		System.out.println("[SVS] localemp 响应：" + response);
		// localemp 只在回调成功后返回 200，此时本地任务应已由回调完成
		ModelGenerationResult result = callback.get(CALLBACK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("local SVS callback returned no audio");
		}
		return result;
	}

	private static String firstNonEmpty(String first, String second) {
		return first != null && !first.isEmpty() ? first : second == null ? "" : second;
	}

	private static String encode(String value) throws IOException {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
	}
}
