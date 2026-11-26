package com.khjxiaogu.aiwuxia.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远程 URL 的工具方法：识别、对象 id 提取、扩展名/MIME 推断与受限下载。
 * <p>
 * 本地模型链路需要区分“裸对象 id”“项目自己的媒体链接”“外部 http(s) 链接”：
 * </p>
 * <ul>
 *   <li>裸 id：72 位内容哈希，可带媒体扩展名；</li>
 *   <li>项目链接：{@code /painter/image?image_id=...}、{@code /aichat/mcp/media?file_id=...}
 *       这类参数里带对象 id 的链接；</li>
 *   <li>外部链接：其它 http(s) URL，只能由本地模型部署侧下载。</li>
 * </ul>
 * <p>
 * 这些判断在服务端（决定是否把 URL 交给部署侧）和部署侧（决定缓存键、跳过下载）都要用，
 * 因此统一放在这里，避免两处规则不一致。
 * </p>
 */
public final class RemoteUrl {

	/** 裸对象 id：72 位十六进制，可带扩展名。 */
	private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-fA-F]{72}(?:\\.[0-9A-Za-z]{1,16})?");
	/** 媒体链接里携带对象 id 的查询参数名。 */
	private static final String[] ID_PARAMS = { "file_id=", "image_id=", "video_id=", "audio_id=",
			"picture_id=" };
	/** URL 路径末尾的扩展名。 */
	private static final Pattern PATH_EXTENSION = Pattern.compile("\\.([0-9A-Za-z]{1,6})$");
	/** 下载请求的 User-Agent。 */
	private static final String USER_AGENT = "AIWuxia-LocalModel/1.0";
	/** 默认连接超时。 */
	public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
	/** 默认读取超时（大视频可能较慢）。 */
	public static final int DEFAULT_READ_TIMEOUT_MS = 120_000;

	private RemoteUrl() {
	}

	/**
	 * 是否是 http(s) 链接。
	 *
	 * @param value 待判断字符串，可为 null
	 * @return http(s) 链接时返回 true
	 */
	public static boolean isHttp(String value) {
		if (value == null) {
			return false;
		}
		String text = value.trim();
		return text.regionMatches(true, 0, "http://", 0, 7) || text.regionMatches(true, 0, "https://", 0, 8);
	}

	/**
	 * 是否是 Base64 的 data URL。
	 *
	 * @param value 待判断字符串，可为 null
	 * @return data URL 时返回 true
	 */
	public static boolean isDataUrl(String value) {
		return value != null && value.trim().regionMatches(true, 0, "data:", 0, 5);
	}

	/**
	 * 从引用里取出对象 id。
	 * <p>
	 * 接受裸 id（72 位十六进制，可带扩展名）以及参数里带 id 的项目媒体链接
	 * （{@code file_id}/{@code image_id}/{@code video_id}/{@code audio_id}/{@code picture_id}），
	 * 最后再试 URL 路径末尾的 72 位字符串。
	 * </p>
	 *
	 * @param reference 引用，可为 null
	 * @return 对象 id（保留扩展名）；无法识别时返回 null
	 */
	public static String objectIdOf(String reference) {
		if (reference == null) {
			return null;
		}
		String value = reference.trim();
		if (value.isEmpty()) {
			return null;
		}
		if (OBJECT_ID.matcher(value).matches()) {
			return value;
		}
		for (String param : ID_PARAMS) {
			String id = queryParam(value, param);
			if (id != null && OBJECT_ID.matcher(id).matches()) {
				return id;
			}
		}
		String path = value;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int hash = path.indexOf('#');
		if (hash >= 0) {
			path = path.substring(0, hash);
		}
		int slash = path.lastIndexOf('/');
		if (slash >= 0 && slash < path.length() - 1) {
			String last = decode(path.substring(slash + 1));
			if (OBJECT_ID.matcher(last).matches()) {
				return last;
			}
		}
		return null;
	}

	/** 取查询参数值并做 URL 解码；参数不存在时返回 null。 */
	private static String queryParam(String value, String param) {
		int index = value.indexOf(param);
		if (index < 0) {
			return null;
		}
		String id = value.substring(index + param.length());
		int amp = id.indexOf('&');
		if (amp >= 0) {
			id = id.substring(0, amp);
		}
		id = decode(id).trim();
		return id.isEmpty() ? null : id;
	}

	/** URL 解码；非法编码时原样返回。 */
	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (Exception ex) {
			return value;
		}
	}

	/**
	 * 从 URL/引用里猜扩展名（小写、不带点）。
	 *
	 * @param reference URL、对象 id 或文件名
	 * @return 扩展名；猜不到时返回空串
	 */
	public static String extensionOf(String reference) {
		if (reference == null) {
			return "";
		}
		String path = reference.trim();
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int hash = path.indexOf('#');
		if (hash >= 0) {
			path = path.substring(0, hash);
		}
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		if (dot <= slash || dot >= path.length() - 1) {
			return "";
		}
		Matcher matcher = PATH_EXTENSION.matcher(path.substring(dot));
		if (!matcher.matches()) {
			return "";
		}
		return matcher.group(1).toLowerCase(Locale.ROOT);
	}

	/**
	 * 扩展名 → MIME。
	 *
	 * @param extension 扩展名，可带点
	 * @return MIME；未知时返回空串
	 */
	public static String mimeOfExtension(String extension) {
		if (extension == null) {
			return "";
		}
		String ext = extension.startsWith(".") ? extension.substring(1) : extension;
		switch (ext.toLowerCase(Locale.ROOT)) {
			case "png":
				return "image/png";
			case "jpg":
			case "jpeg":
				return "image/jpeg";
			case "gif":
				return "image/gif";
			case "webp":
				return "image/webp";
			case "bmp":
				return "image/bmp";
			case "svg":
				return "image/svg+xml";
			case "mp4":
			case "m4v":
				return "video/mp4";
			case "webm":
				return "video/webm";
			case "mov":
				return "video/quicktime";
			case "mkv":
				return "video/x-matroska";
			case "mp3":
				return "audio/mpeg";
			case "wav":
				return "audio/wav";
			case "ogg":
				return "audio/ogg";
			case "m4a":
				return "audio/mp4";
			case "flac":
				return "audio/flac";
			default:
				return "";
		}
	}

	/**
	 * 只发 HEAD 探测 Content-Type，不下载内容。
	 * <p>
	 * 用于判断一个 URL 是图片还是视频（例如 H3 的首/尾帧来源），服务端不需要把整个文件拉回来。
	 * 失败时返回空串，由调用方退回扩展名判断。
	 * </p>
	 *
	 * @param url 目标 URL
	 * @return 小写 MIME（不含 {@code ;charset=...}）；失败时返回空串
	 */
	public static String probeContentType(String url) {
		if (!isHttp(url)) {
			return "";
		}
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url.trim()).openConnection();
			connection.setRequestMethod("HEAD");
			connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
			connection.setInstanceFollowRedirects(true);
			connection.setUseCaches(false);
			connection.setRequestProperty("User-Agent", USER_AGENT);
			int code = connection.getResponseCode();
			if (code < 200 || code >= 400) {
				return "";
			}
			String type = connection.getContentType();
			return normalizeMime(type);
		} catch (Exception ex) {
			return "";
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** 去掉 MIME 参数并转小写。 */
	private static String normalizeMime(String type) {
		if (type == null) {
			return "";
		}
		String mime = type.trim().toLowerCase(Locale.ROOT);
		int semicolon = mime.indexOf(';');
		if (semicolon >= 0) {
			mime = mime.substring(0, semicolon).trim();
		}
		return mime;
	}

	/**
	 * 下载 http(s) URL，并执行大小上限检查。
	 *
	 * @param url      目标 URL
	 * @param maxBytes 大小上限（字节）；非正数表示不限制
	 * @return 内容字节
	 * @throws IOException 网络错误、非 2xx 响应或超过上限
	 */
	public static byte[] download(String url, long maxBytes) throws IOException {
		return download(url, maxBytes, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
	}

	/**
	 * 下载 http(s) URL，并执行大小上限检查。
	 *
	 * @param url              目标 URL
	 * @param maxBytes         大小上限（字节）；非正数表示不限制
	 * @param connectTimeoutMs 连接超时
	 * @param readTimeoutMs    读取超时
	 * @return 内容字节
	 * @throws IOException 网络错误、非 2xx 响应或超过上限
	 */
	public static byte[] download(String url, long maxBytes, int connectTimeoutMs, int readTimeoutMs)
			throws IOException {
		if (!isHttp(url)) {
			throw new IOException("不是 http(s) 链接：" + shortUrl(url));
		}
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url.trim()).openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(connectTimeoutMs <= 0 ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs);
			connection.setReadTimeout(readTimeoutMs <= 0 ? DEFAULT_READ_TIMEOUT_MS : readTimeoutMs);
			connection.setInstanceFollowRedirects(true);
			connection.setUseCaches(false);
			connection.setRequestProperty("Accept", "*/*");
			connection.setRequestProperty("Accept-Encoding", "identity");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			int code = connection.getResponseCode();
			if (code < 200 || code >= 300) {
				throw new IOException("HTTP " + code);
			}
			long length = connection.getContentLengthLong();
			if (maxBytes > 0 && length > maxBytes) {
				throw new IOException("文件超过大小上限 " + (maxBytes / (1024L * 1024L)) + " MiB");
			}
			return readLimited(connection.getInputStream(), maxBytes);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** 读取响应体并执行大小上限检查。 */
	private static byte[] readLimited(InputStream input, long maxBytes) throws IOException {
		try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream(16384)) {
			byte[] buffer = new byte[8192];
			int read;
			long total = 0;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (maxBytes > 0 && total > maxBytes) {
					throw new IOException("文件超过大小上限 " + (maxBytes / (1024L * 1024L)) + " MiB");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	/**
	 * 解码 data URL；支持 {@code ;base64} 与百分号编码两种载荷。
	 *
	 * @param value data URL
	 * @return 解码后的字节
	 * @throws IOException 格式非法
	 */
	public static byte[] decodeDataUrl(String value) throws IOException {
		if (value == null) {
			throw new IOException("data URL 为空");
		}
		int comma = value.indexOf(',');
		if (comma < 0) {
			throw new IOException("data URL 缺少逗号分隔符");
		}
		String header = value.substring(5, comma).toLowerCase(Locale.ROOT);
		String payload = value.substring(comma + 1);
		try {
			if (header.contains(";base64")) {
				return Base64.getDecoder().decode(payload.replaceAll("\\s+", ""));
			}
			return URLDecoder.decode(payload, StandardCharsets.UTF_8.name()).getBytes(StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			throw new IOException("data URL 内容不合法", ex);
		}
	}

	/**
	 * data URL 头里的 MIME（小写、不含参数）。
	 *
	 * @param value data URL
	 * @return MIME；没有时返回空串
	 */
	public static String mimeOfDataUrl(String value) {
		if (value == null || !isDataUrl(value)) {
			return "";
		}
		int comma = value.indexOf(',');
		if (comma < 0) {
			return "";
		}
		String header = value.substring(5, comma);
		int semicolon = header.indexOf(';');
		if (semicolon >= 0) {
			header = header.substring(0, semicolon);
		}
		return header.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 日志用截断 URL，避免刷屏与泄露过长参数。
	 *
	 * @param value URL
	 * @return 最长 160 字符
	 */
	public static String shortUrl(String value) {
		if (value == null) {
			return "";
		}
		return value.length() > 160 ? value.substring(0, 160) + "..." : value;
	}
}
