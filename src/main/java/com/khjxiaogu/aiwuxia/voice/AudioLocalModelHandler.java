package com.khjxiaogu.aiwuxia.voice;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient.Response;

/**
 * 本地语音合成（TTS）的请求处理器。
 * <p>
 * 收到服务器下发的朗读请求后，把请求 JSON 原样 POST 给本机 TTS HTTP 服务，再把音频回传；
 * 不持有网络连接，连接与进度上报由 {@link LocalModelClient} 统一管理。请求/响应契约与本地模型
 * 终端页面（{@code save/localmodel.html} 的“语音合成”卡片）一致：
 * </p>
 * <ul>
 *   <li>请求：服务器下发的完整 JSON（{@code chara}、{@code content}、{@code reqid} 等）直接作为
 *       POST body，{@code Content-Type: application/json}；</li>
 *   <li>响应：原始音频字节，或 JSON（在 {@code data} / {@code audio} / {@code audio_base64} /
 *       {@code audioBase64} / {@code base64} 里放 Base64；支持 {@code data:audio/...;base64,} 前缀）。</li>
 * </ul>
 * <p>
 * 地址取 {@code -DttsUrl=http://127.0.0.1:9880}（也可用环境变量 {@code TTS_URL}），路径
 * {@code -DttsPath=/}（默认 {@code /}），回传格式 {@code -DttsFormat=mp3}。未配置 {@code ttsUrl}
 * 时不注册 audio 能力，语音合成继续由外部 {@code model_type=audio} 连接器或 MiMo / 火山提供。
 * </p>
 */
public final class AudioLocalModelHandler {

	/** 本机 TTS 服务默认地址（与本地模型终端页面的占位一致）。 */
	public static final String DEFAULT_TTS_URL = "http://127.0.0.1:9880";
	/** TTS 地址系统属性名。 */
	public static final String URL_PROPERTY = "ttsUrl";
	/** TTS 路径系统属性名。 */
	public static final String PATH_PROPERTY = "ttsPath";
	/** 回传音频格式系统属性名。 */
	public static final String FORMAT_PROPERTY = "ttsFormat";
	/** 连接本机 TTS 的超时。 */
	private static final int CONNECT_TIMEOUT_MS = 5_000;
	/** 等待 TTS 合成的超时；长文本可能较慢。 */
	private static final int READ_TIMEOUT_MS = 120_000;
	/** 允许从 JSON 响应里取音频的字段名。 */
	private static final String[] AUDIO_FIELDS = { "data", "audio", "audio_base64", "audioBase64", "base64" };

	private AudioLocalModelHandler() {
	}

	/**
	 * 创建处理器。
	 *
	 * @param ttsUrl 本机 TTS 地址，空串表示按 {@code -DttsUrl} / 环境变量
	 * @param ttsPath 请求路径，空值按 {@code /} 处理
	 * @param format 回传格式，空值按 {@code mp3} 处理
	 * @return 请求处理器
	 */
	public static RequestHandler create(String ttsUrl, String ttsPath, String format) {
		final String base = normalizeBase(ttsUrl == null || ttsUrl.trim().isEmpty() ? resolveUrl() : ttsUrl);
		final String path = ttsPath == null || ttsPath.isEmpty() ? "/" : ttsPath;
		final String fallbackFormat = format == null || format.isEmpty() ? "mp3" : format;
		return new RequestHandler() {
			@Override
			public Response handle(JsonObject request) {
				return AudioLocalModelHandler.handle(request, base, path, fallbackFormat, ProgressReporter.NOOP);
			}

			@Override
			public Response handle(JsonObject request, ProgressReporter progress) {
				return AudioLocalModelHandler.handle(request, base, path, fallbackFormat, progress);
			}
		};
	}

	/**
	 * 是否配置了本机 TTS 地址（{@code -DttsUrl} 或环境变量 {@code TTS_URL}）。
	 *
	 * @return 已配置返回 true
	 */
	public static boolean isConfigured() {
		return !resolveUrl().isEmpty();
	}

	/**
	 * 读取 TTS 地址：系统属性优先，其次环境变量，未配置时为空串。
	 *
	 * @return 原始地址（可能为空）
	 */
	public static String resolveUrl() {
		String value = System.getProperty(URL_PROPERTY, "").trim();
		if (value.isEmpty()) {
			String env = System.getenv("TTS_URL");
			value = env == null ? "" : env.trim();
		}
		return value;
	}

	/** 读取 TTS 请求路径，空值按 {@code /} 处理。 */
	public static String resolvePath() {
		String value = System.getProperty(PATH_PROPERTY, "").trim();
		if (value.isEmpty()) {
			return "/";
		}
		return value.charAt(0) == '/' ? value : "/" + value;
	}

	/** 读取回传格式，空值按 {@code mp3} 处理。 */
	public static String resolveFormat() {
		String value = System.getProperty(FORMAT_PROPERTY, "").trim();
		return value.isEmpty() ? "mp3" : value.toLowerCase();
	}

	/** TTS 健康检查；未配置地址时返回 null。 */
	public static LocalModelClient.HealthCheck healthCheck() {
		String base = normalizeBase(resolveUrl());
		if (base.isEmpty()) {
			return null;
		}
		return LocalModelClient.httpHealthCheck(joinUrl(base, resolvePath()), CONNECT_TIMEOUT_MS);
	}

	/** 把一条朗读请求转发给本机 TTS，并把音频回传服务器。 */
	private static Response handle(JsonObject request, String base, String path, String fallbackFormat,
			ProgressReporter progress) {
		if (base.isEmpty()) {
			throw new IllegalStateException("未配置本机 TTS 地址（-D" + URL_PROPERTY + "）");
		}
		// TTS 不占资源锁，没有排队阶段：正式发起请求时直接进入“执行中”
		progress.running("已下发本机 TTS");
		String target = joinUrl(base, path);
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(target).openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setDoInput(true);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			connection.setRequestProperty("Accept", "*/*");
			try (OutputStream out = connection.getOutputStream()) {
				out.write(request.toString().getBytes(StandardCharsets.UTF_8));
			}
			int code = connection.getResponseCode();
			byte[] body = FileUtil.readAll(code >= 200 && code < 300 ? connection.getInputStream()
					: connection.getErrorStream());
			if (code < 200 || code >= 300) {
				throw new IOException("本机 TTS 返回 HTTP " + code + "：" + preview(body));
			}
			if (body == null || body.length == 0) {
				throw new IOException("本机 TTS 返回空响应");
			}
			String contentType = connection.getContentType() == null ? ""
					: connection.getContentType().toLowerCase();
			if (contentType.contains("json") || isJson(body)) {
				return decodeJson(body, fallbackFormat);
			}
			return new Response(body, sniffFormat(contentType, fallbackFormat));
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** 从 JSON 响应中取 Base64 音频。 */
	private static Response decodeJson(byte[] body, String fallbackFormat) {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException("本机 TTS 返回的 JSON 无法解析", ex);
		}
		if (parsed == null || !parsed.isJsonObject()) {
			throw new IllegalArgumentException("本机 TTS 返回的不是 JSON 对象");
		}
		JsonObject root = parsed.getAsJsonObject();
		JsonObject dataObject = root.has("data") && root.get("data").isJsonObject()
				? root.getAsJsonObject("data")
				: null;
		JsonObject[] candidates = dataObject == null ? new JsonObject[] { root }
				: new JsonObject[] { root, dataObject };
		for (JsonObject candidate : candidates) {
			for (String field : AUDIO_FIELDS) {
				if (!candidate.has(field) || candidate.get(field).isJsonNull()) {
					continue;
				}
				String value = candidate.get(field).getAsString();
				if (value == null || value.trim().isEmpty()) {
					continue;
				}
				String data = value.trim();
				String format = candidate.has("format") && !candidate.get("format").isJsonNull()
						? candidate.get("format").getAsString()
						: fallbackFormat;
				if (data.regionMatches(true, 0, "data:audio/", 0, 11)) {
					int semicolon = data.indexOf(';');
					int comma = data.indexOf(',');
					if (semicolon > 11 && comma > semicolon) {
						format = data.substring(11, semicolon);
						data = data.substring(comma + 1);
					}
				}
				data = data.replaceAll("\\s+", "");
				try {
					return new Response(Base64.getDecoder().decode(data), format);
				} catch (IllegalArgumentException ex) {
					throw new IllegalArgumentException("本机 TTS 返回的音频不是合法的 Base64", ex);
				}
			}
		}
		throw new IllegalArgumentException("本机 TTS 返回的 JSON 中没有音频字段（支持 " + String.join(" / ",
				AUDIO_FIELDS) + "）");
	}

	/** 根据 Content-Type 猜扩展名，猜不到时用配置值。 */
	private static String sniffFormat(String contentType, String fallback) {
		if (contentType.contains("wav")) {
			return "wav";
		}
		if (contentType.contains("mpeg") || contentType.contains("mp3")) {
			return "mp3";
		}
		if (contentType.contains("ogg")) {
			return "ogg";
		}
		return fallback;
	}

	/** 粗略判断响应体是不是 JSON（TTS 服务可能不返回 Content-Type）。 */
	private static boolean isJson(byte[] body) {
		for (byte value : body) {
			if (value == '{' || value == '[') {
				return true;
			}
			if (value != ' ' && value != '\r' && value != '\n' && value != '\t') {
				return false;
			}
		}
		return false;
	}

	/** 规整地址：补协议、去掉末尾斜杠；空值返回空串。 */
	private static String normalizeBase(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			return "";
		}
		if (!value.startsWith("http://") && !value.startsWith("https://")) {
			value = "http://" + value;
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	/** 拼接 base 与路径；路径为空时使用 {@code /}。 */
	private static String joinUrl(String base, String path) {
		if (path == null || path.isEmpty()) {
			return base + "/";
		}
		return base + (path.charAt(0) == '/' ? path : "/" + path);
	}

	/** 只截取错误响应前 200 字，避免日志被大段文本刷屏。 */
	private static String preview(byte[] body) {
		if (body == null) {
			return "";
		}
		String text = new String(body, StandardCharsets.UTF_8);
		return text.length() > 200 ? text.substring(0, 200) : text;
	}
}
