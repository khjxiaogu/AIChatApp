package com.khjxiaogu.aiwuxia.llm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 模型调用层的附件解析器。
 * <p>
 * 消息里的图片/音频/视频只保存对象存储 ID。真正把 ID 变成模型可访问的地址、以及把输入消息里
 * 尚未入库的附件（data URL、http 链接、本地文件）上传成 ID，都统一在这里完成：
 * </p>
 * <ul>
 *   <li>{@link #resolveId(String)}：把任意附件引用规范化为对象存储 ID；</li>
 *   <li>{@link #resolveUrl(String)}：把附件引用解析成模型可访问的 URL（本地存储会返回修正
 *       MIME 的 data URL，远端存储返回公开链接）；</li>
 *   <li>{@link #resolveBase64(String)}：直接取附件字节的 base64，供只接受纯 base64 的接口使用。</li>
 * </ul>
 * <p>
 * 未配置对象存储时所有方法原样返回输入，保持旧行为；同一个附件在一次请求内只会上传一次。
 * 解析失败时尽量回退到原始引用，不阻断模型调用。
 * </p>
 */
public final class AttachmentResolver {

	/** 对象存储 ID：内容哈希（72 位十六进制），可带扩展名。 */
	private static final Pattern STORAGE_ID = Pattern.compile("[0-9a-fA-F]{72}(?:\\.[0-9A-Za-z]{1,16})?");

	/** 单次上传的附件大小上限，避免误把巨大文件整个读进内存。 */
	private static final int MAX_ATTACHMENT_BYTES = 64 * 1024 * 1024;

	private final ObjectStorageProvider storage;
	private final Consumer<UsageIntf<?>> addUsage;
	/** 原始引用 → 对象存储 ID，单次请求内避免重复上传。 */
	private final Map<String, String> resolved = new HashMap<>();

	/**
	 * @param storage  对象存储，可为 null（此时不做任何解析/上传）
	 * @param addUsage 上传/下载产生的用量接收方，可为 null
	 */
	public AttachmentResolver(ObjectStorageProvider storage, Consumer<UsageIntf<?>> addUsage) {
		this.storage = storage;
		this.addUsage = addUsage;
	}

	/** 为一次模型请求创建解析器。 */
	public static AttachmentResolver of(AIRequest request) {
		return new AttachmentResolver(request == null ? null : request.getStorage(), null);
	}

	/** 是否配置了对象存储。 */
	public boolean hasStorage() {
		return storage != null;
	}

	/**
	 * 把附件引用规范化为对象存储 ID。
	 * <p>
	 * 已经是 ID 的直接返回；data URL、http(s) 链接与本地文件会读取字节并上传，
	 * 返回内容寻址的 ID。未配置存储或解析失败时返回原值。
	 * </p>
	 *
	 * @param reference 附件引用，可为 null
	 * @return 对象存储 ID 或原始引用
	 */
	public String resolveId(String reference) {
		if (reference == null || reference.isEmpty() || storage == null) {
			return reference;
		}
		if (isStorageId(reference)) {
			return reference;
		}
		String cached = resolved.get(reference);
		if (cached != null) {
			return cached;
		}
		try {
			byte[] data = readBytes(reference);
			if (data == null || data.length == 0) {
				return reference;
			}
			String id = storage.uploadIfNotExists(data, addUsage);
			if (id == null || id.isEmpty()) {
				return reference;
			}
			resolved.put(reference, id);
			return id;
		} catch (Throwable ex) {
			// 附件解析失败不能让整轮模型调用失败，保留原引用交给提供者兜底
			return reference;
		}
	}

	/**
	 * 把附件引用解析为模型可访问的 URL。
	 * <p>
	 * 先规范化成对象存储 ID，再通过对象存储取公开地址。本地存储返回的 data URL 会用文件头
	 * 修正 MIME（对象存储 ID 不一定带扩展名），避免把图片当成 {@code application/octet-stream}。
	 * </p>
	 *
	 * @param reference 附件引用，可为 null
	 * @return 可访问 URL；解析失败时返回原引用
	 */
	public String resolveUrl(String reference) {
		String id = resolveId(reference);
		if (id == null || storage == null || !isStorageId(id)) {
			return id;
		}
		try {
			String url = storage.getPublicUrl(id, addUsage);
			if (url == null || url.isEmpty()) {
				return id;
			}
			if (url.startsWith("data:")) {
				// 本地存储按扩展名猜 MIME，无扩展名的内容哈希会得到 octet-stream，这里用真实字节修正
				byte[] data = storage.download(id, addUsage);
				if (data != null && data.length > 0) {
					return toDataUrl(data);
				}
			}
			return url;
		} catch (Throwable ex) {
			return id;
		}
	}

	/**
	 * 取附件字节的 base64；供只接受纯 base64、不接受 URL 的模型接口使用。
	 *
	 * @param reference 附件引用，可为 null
	 * @return base64 字符串；无法读取时返回原引用
	 */
	public String resolveBase64(String reference) {
		if (reference == null || reference.isEmpty()) {
			return reference;
		}
		try {
			byte[] data = null;
			if (storage != null && isStorageId(reference)) {
				data = storage.download(reference, addUsage);
			} else {
				data = readBytes(reference);
			}
			return data == null || data.length == 0 ? reference : Base64.getEncoder().encodeToString(data);
		} catch (Throwable ex) {
			return reference;
		}
	}

	/** 判断一个引用是否已经是对象存储 ID。 */
	public static boolean isStorageId(String value) {
		return value != null && STORAGE_ID.matcher(value).matches();
	}

	/** 读取附件原始字节：data URL、http(s) 链接或本地文件。 */
	private static byte[] readBytes(String reference) throws IOException {
		if (reference.regionMatches(true, 0, "data:", 0, 5)) {
			return decodeDataUrl(reference);
		}
		if (reference.regionMatches(true, 0, "http://", 0, 7)
				|| reference.regionMatches(true, 0, "https://", 0, 8)) {
			try (InputStream in = FileUtil.fetch(reference)) {
				return readLimited(in);
			}
		}
		File file = new File(reference);
		if (file.isFile()) {
			try (InputStream in = new FileInputStream(file)) {
				return readLimited(in);
			}
		}
		return null;
	}

	/** 解析 data URL；支持 base64 与百分号编码两种载荷。 */
	private static byte[] decodeDataUrl(String value) throws IOException {
		int comma = value.indexOf(',');
		if (comma < 0) {
			throw new IOException("invalid data url");
		}
		String header = value.substring(5, comma);
		String payload = value.substring(comma + 1);
		if (header.toLowerCase().contains(";base64")) {
			return Base64.getDecoder().decode(payload);
		}
		return URLDecoder.decode(payload, StandardCharsets.UTF_8.name()).getBytes(StandardCharsets.UTF_8);
	}

	/** 读取流并限制总大小。 */
	private static byte[] readLimited(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (out.size() + read > MAX_ATTACHMENT_BYTES) {
				throw new IOException("attachment too large");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	/** 把字节包装成带正确 MIME 的 data URL。 */
	private static String toDataUrl(byte[] data) {
		return "data:" + detectMime(data) + ";base64," + Base64.getEncoder().encodeToString(data);
	}

	/** 按文件头识别常见附件 MIME。 */
	private static String detectMime(byte[] data) {
		if (data == null || data.length < 4) {
			return "application/octet-stream";
		}
		if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
			return "image/png";
		}
		if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
			return "image/jpeg";
		}
		if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
			return "image/gif";
		}
		if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
			return "image/webp";
		}
		if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') {
			return "audio/wav";
		}
		if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
			return "audio/ogg";
		}
		if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
			return "audio/mpeg";
		}
		if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFB) {
			return "audio/mpeg";
		}
		if (data.length >= 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
			return "video/mp4";
		}
		return "application/octet-stream";
	}
}
