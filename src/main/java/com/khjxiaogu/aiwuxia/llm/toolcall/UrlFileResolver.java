package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;

/**
 * URL 到对象存储 ID 的默认解析器。
 * <p>
 * 支持 {@code http://}、{@code https://} 与 Base64 的 {@code data:} URL；下载后按
 * Content-Type / URL 后缀补扩展名，写入构造时传入的对象存储。非 URL 值原样返回，
 * 因此已经使用 72 位对象存储 ID 的调用不受影响。
 * </p>
 * <p>
 * 参数值是逗号分隔的引用列表时，只切分“逗号后是 URL 或 72 位 ID”的位置，
 * 不会把 URL 查询串里的普通逗号误当成分隔符。
 * </p>
 * <p>
 * 大小上限默认 128 MiB，可用 {@code -DtoolUrlAttachmentMaxMb=...} 调整；
 * 同时兼容旧的 {@code -DmcpUrlAttachmentMaxMb=...}。连接超时 10 秒、读取超时 60 秒。
 * </p>
 */
public final class UrlFileResolver implements ToolFileResolver {

	/** 逗号分隔的引用列表切分规则。 */
	private static final Pattern MULTI_VALUE = Pattern.compile(
			"\\s*,\\s*(?=(?:https?://|data:|[0-9a-fA-F]{72}(?:\\.[0-9A-Za-z]{1,16})?\\s*(?:,|$)))");
	/** 默认下载上限：128 MiB。 */
	private static final int DEFAULT_MAX_MB = 128;
	/** 连接超时。 */
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	/** 读取超时。 */
	private static final int READ_TIMEOUT_MS = 60_000;

	private final ObjectStorageProvider storage;
	private final boolean urlContext;
	private final long maxBytes;

	/**
	 * @param storage    对象存储，不能为 null
	 * @param urlContext true 表示该管理器的工具说明使用 URL 上下文（MCP 服务端）；
	 *                   false 表示对象存储上下文（内部 AI）
	 */
	public UrlFileResolver(ObjectStorageProvider storage, boolean urlContext) {
		if (storage == null) {
			throw new IllegalArgumentException("对象存储不能为空");
		}
		this.storage = storage;
		this.urlContext = urlContext;
		long mb = DEFAULT_MAX_MB;
		String raw = System.getProperty("toolUrlAttachmentMaxMb", "").trim();
		if (raw.isEmpty()) {
			raw = System.getProperty("mcpUrlAttachmentMaxMb", "").trim();
		}
		if (!raw.isEmpty()) {
			try {
				mb = Long.parseLong(raw);
			} catch (NumberFormatException ignored) {
				// 配置非法时使用默认值
			}
		}
		this.maxBytes = mb <= 0 ? 0 : mb * 1024L * 1024L;
	}

	@Override
	public boolean isUrlContext() {
		return urlContext;
	}

	@Override
	public String resolve(String value) throws IOException {
		if (value == null || value.trim().isEmpty()) {
			return value;
		}
		String text = value.trim();
		String[] parts = MULTI_VALUE.split(text);
		if (parts.length <= 1) {
			return resolveOne(text, value);
		}
		boolean changed = false;
		List<String> resolved = new ArrayList<>(parts.length);
		for (String part : parts) {
			String token = part == null ? "" : part.trim();
			if (token.isEmpty()) {
				resolved.add("");
				continue;
			}
			String one = resolveOne(token, token);
			if (!one.equals(token)) {
				changed = true;
			}
			resolved.add(one);
		}
		return changed ? String.join(",", resolved) : value;
	}

	/** 单个引用：URL 下载/解码后转存，其它值原样返回。 */
	private String resolveOne(String ref, String original) throws IOException {
		if (ref.regionMatches(true, 0, "data:", 0, 5)) {
			return store(decodeDataUri(ref), ref);
		}
		if (!isHttpUrl(ref)) {
			return original;
		}
		return store(fetchHttp(ref), ref);
	}

	/** 写入对象存储并返回内容 ID。 */
	private String store(Fetched fetched, String source) throws IOException {
		if (maxBytes > 0 && fetched.data.length > maxBytes) {
			throw new IOException("附件超过大小上限 " + (maxBytes / (1024L * 1024L)) + " MiB");
		}
		String id = storage.uploadIfNotExists(fetched.data, fetched.extension, null);
		System.out.println("[ToolFile] URL 附件已转存：" + shorten(source) + " -> " + id
				+ "（" + fetched.data.length + "B）");
		return id;
	}

	/** 下载 http(s) URL，并尽量从 Content-Type / 路径推导扩展名。 */
	private Fetched fetchHttp(String url) throws IOException {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setInstanceFollowRedirects(true);
			connection.setUseCaches(false);
			connection.setRequestProperty("Accept", "*/*");
			connection.setRequestProperty("Accept-Encoding", "identity");
			connection.setRequestProperty("User-Agent", "AIWuxia-Tool/1.0");
			int code = connection.getResponseCode();
			if (code < 200 || code >= 300) {
				throw new IOException("HTTP " + code);
			}
			long length = connection.getContentLengthLong();
			if (maxBytes > 0 && length > maxBytes) {
				throw new IOException("附件超过大小上限 " + (maxBytes / (1024L * 1024L)) + " MiB");
			}
			String contentType = connection.getContentType();
			byte[] data = readLimited(connection.getInputStream());
			return new Fetched(data, extensionFor(contentType, url));
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** 读取响应体并执行大小上限检查。 */
	private byte[] readLimited(InputStream input) throws IOException {
		try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			long total = 0;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (maxBytes > 0 && total > maxBytes) {
					throw new IOException("附件超过大小上限 " + (maxBytes / (1024L * 1024L)) + " MiB");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	/** 解析 data URI 的 Base64 内容。 */
	private Fetched decodeDataUri(String ref) throws IOException {
		int comma = ref.indexOf(',');
		if (comma < 0) {
			throw new IOException("data URL 缺少逗号分隔符");
		}
		String meta = ref.substring(5, comma);
		if (!meta.toLowerCase(Locale.ROOT).contains(";base64")) {
			throw new IOException("只支持 Base64 编码的 data URL");
		}
		byte[] data;
		try {
			data = Base64.getDecoder().decode(ref.substring(comma + 1).replaceAll("\\s+", ""));
		} catch (IllegalArgumentException ex) {
			throw new IOException("Base64 内容不合法", ex);
		}
		String mime = meta;
		int semicolon = mime.indexOf(';');
		if (semicolon >= 0) {
			mime = mime.substring(0, semicolon);
		}
		return new Fetched(data, extensionFor(mime, ""));
	}

	/** 根据 Content-Type 或 URL 路径猜扩展名，猜不到返回空串。 */
	private static String extensionFor(String contentType, String url) {
		String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
		int semicolon = type.indexOf(';');
		if (semicolon >= 0) {
			type = type.substring(0, semicolon).trim();
		}
		switch (type) {
			case "image/png":
				return ".png";
			case "image/jpeg":
			case "image/jpg":
				return ".jpg";
			case "image/webp":
				return ".webp";
			case "image/gif":
				return ".gif";
			case "image/svg+xml":
				return ".svg";
			case "audio/mpeg":
			case "audio/mp3":
				return ".mp3";
			case "audio/wav":
			case "audio/x-wav":
				return ".wav";
			case "audio/ogg":
				return ".ogg";
			case "audio/mp4":
			case "audio/m4a":
				return ".m4a";
			case "audio/aac":
				return ".aac";
			case "audio/flac":
				return ".flac";
			case "video/mp4":
				return ".mp4";
			case "video/webm":
				return ".webm";
			case "video/quicktime":
				return ".mov";
			default:
				break;
		}
		if (url == null || url.isEmpty()) {
			return "";
		}
		String path = url;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		if (dot > slash && dot < path.length() - 1) {
			String extension = path.substring(dot);
			if (extension.matches("\\.[0-9A-Za-z]{1,6}")) {
				return extension.toLowerCase(Locale.ROOT);
			}
		}
		return "";
	}

	/** 只认 http(s) URL。 */
	private static boolean isHttpUrl(String value) {
		return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
				|| value.regionMatches(true, 0, "https://", 0, 8));
	}

	/** 日志里截断过长的 URL，避免刷屏。 */
	private static String shorten(String value) {
		if (value == null) {
			return "";
		}
		return value.length() > 200 ? value.substring(0, 200) + "..." : value;
	}

	/** 下载结果：内容字节与推导出的扩展名。 */
	private static final class Fetched {
		final byte[] data;
		final String extension;

		Fetched(byte[] data, String extension) {
			this.data = data == null ? new byte[0] : data;
			this.extension = extension == null ? "" : extension;
		}
	}
}
