package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 本地文件对象存储。
 * <p>
 * 对象键是内容哈希；媒体文件（图片/视频/音频）上传时**不带后缀**，例如 {@code <72位哈希>}
 * 而不是 {@code <72位哈希>.mp4}。后缀只在读取时按文件内容（魔数）推断 MIME，不再参与键。
 * </p>
 * <p>
 * 为避免破坏历史数据，读取/存在性判断同时接受带媒体后缀的旧键：先按无后缀查找，再按原键查找。
 * {@code .caption} 这类非媒体后缀不受影响，仍按原键保存（图片识别生成的说明文件与图片共用一个哈希，
 * 不能把后缀一起去掉）。
 * </p>
 */
public class LocalStorage implements ObjectStorageProvider {

	/** 会被去除的媒体后缀；只处理常见的图片/视频/音频，其它后缀（如 .caption）保留。 */
	private static final Pattern MEDIA_EXTENSION = Pattern
			.compile("\\.(png|jpe?g|gif|webp|svg|bmp|mp4|webm|mov|mkv|mp3|wav|ogg|m4a|flac)$",
					Pattern.CASE_INSENSITIVE);

	private final File storageDir;

	public LocalStorage(String path) {
		this(new File(path));
	}

	public LocalStorage(File path) {
		storageDir = path;
		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}
	}

	@Override
	public boolean exists(String fn, Consumer<UsageIntf<?>> addUsage) {
		return fileFor(fn).exists();
	}

	@Override
	public String upload(String fn, byte[] data, Consumer<UsageIntf<?>> addUsage) throws IOException {
		String key = stripMediaExtension(fn);
		File file = new File(storageDir, key);
		File parent = file.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(data);
		}
		return key;
	}

	/**
	 * 本地存储不保留媒体后缀：忽略扩展名，统一返回无后缀的内容哈希键。
	 * <p>
	 * 只忽略媒体后缀；{@code .caption} 等非媒体后缀仍按默认逻辑处理，避免覆盖同一图片的说明文件。
	 * </p>
	 */
	@Override
	public String uploadIfNotExists(byte[] data, String extension, Consumer<UsageIntf<?>> addUsage)
			throws IOException {
		String suffix = extension == null ? "" : extension.trim();
		if (isMediaExtension(suffix)) {
			suffix = "";
		}
		return ObjectStorageProvider.super.uploadIfNotExists(data, suffix, addUsage);
	}

	@Override
	public String getDataUrl(String fn, Consumer<UsageIntf<?>> addUsage) throws IOException {
		byte[] data = FileUtil.readAll(fileFor(fn));
		String base64 = Base64.getEncoder().encodeToString(data);
		return "data:" + detectMimeType(data, fn) + ";base64," + base64;
	}

	// 本地存储没有公网地址：getHttpUrl 使用接口默认实现返回 null

	@Override
	public byte[] download(String fn, Consumer<UsageIntf<?>> addUsage) throws IOException {
		return FileUtil.readAll(fileFor(fn));
	}

	/**
	 * 只读文件头判断 MIME，避免为了判断图片/视频类型把整个文件读进内存。
	 */
	@Override
	public String contentType(String fn, Consumer<UsageIntf<?>> addUsage) throws IOException {
		File file = fileFor(fn);
		if (!file.isFile()) {
			return mimeFromExtension(fn);
		}
		byte[] head = new byte[512];
		int length;
		try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
			length = in.read(head);
		}
		if (length <= 0) {
			return mimeFromExtension(fn);
		}
		return detectMimeType(java.util.Arrays.copyOf(head, length), fn);
	}

	/**
	 * 查找实际文件：优先无媒体后缀的新键，再退回带后缀的旧键。
	 *
	 * @param fn 对象键
	 * @return 对应文件（可能不存在）
	 */
	private File fileFor(String fn) {
		String stripped = stripMediaExtension(fn);
		File bare = new File(storageDir, stripped);
		if (bare.exists()) {
			return bare;
		}
		if (!stripped.equals(fn)) {
			File legacy = new File(storageDir, fn);
			if (legacy.exists()) {
				return legacy;
			}
		}
		return bare;
	}

	/** 去掉媒体后缀；不是媒体后缀时原样返回。 */
	private static String stripMediaExtension(String fn) {
		if (fn == null || fn.isEmpty()) {
			return fn;
		}
		int slash = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
		String name = slash >= 0 ? fn.substring(slash + 1) : fn;
		Matcher matcher = MEDIA_EXTENSION.matcher(name);
		if (!matcher.find() || matcher.start() == 0) {
			return fn;
		}
		int dot = slash + 1 + matcher.start();
		return fn.substring(0, dot);
	}

	/** 是否是媒体扩展名（可带点，也可以是完整文件名）。 */
	private static boolean isMediaExtension(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		String suffix = value.startsWith(".") ? value
				: value.substring(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1);
		return MEDIA_EXTENSION.matcher(suffix).find();
	}

	/**
	 * 推断 MIME：优先看文件内容魔数，识别不出再退回扩展名。
	 * <p>
	 * 公开给需要按对象内容返回 Content-Type 的 HTTP 端点使用（例如绘画页的图片/视频取用接口）：
	 * 本地存储的对象键不带媒体后缀，只看扩展名会把 jpeg、mp4 一律当成 png。
	 * </p>
	 *
	 * @param data 文件内容，可为 null
	 * @param fn   对象键（用于旧键扩展名兜底）
	 * @return MIME
	 */
	public static String detectMimeType(byte[] data, String fn) {
		if (data != null && data.length > 0) {
			if (startsWith(data, 0x89, 0x50, 0x4E, 0x47)) {
				return "image/png";
			}
			if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
				return "image/jpeg";
			}
			if (startsWithText(data, 0, "GIF8")) {
				return "image/gif";
			}
			if (startsWithText(data, 0, "RIFF") && startsWithText(data, 8, "WEBP")) {
				return "image/webp";
			}
			if (startsWithText(data, 0, "<svg") || startsWithText(data, 0, "<?xml")) {
				return "image/svg+xml";
			}
			if (data.length >= 12 && startsWithText(data, 4, "ftyp")) {
				return "video/mp4";
			}
			// WebM/Matroska：EBML 魔数，浏览器 <video> 同样可以直接播放
			if (startsWith(data, 0x1A, 0x45, 0xDF, 0xA3)) {
				return "video/webm";
			}
			if (startsWithText(data, 0, "ID3") || startsWith(data, 0xFF, 0xFB) || startsWith(data, 0xFF, 0xF3)) {
				return "audio/mpeg";
			}
			if (startsWithText(data, 0, "RIFF") && startsWithText(data, 8, "WAVE")) {
				return "audio/wav";
			}
			if (startsWithText(data, 0, "OggS")) {
				return "audio/ogg";
			}
			int first = firstNonWhitespace(data);
			if (first >= 0 && (data[first] == '{' || data[first] == '[')) {
				return "application/json";
			}
		}
		return mimeFromExtension(fn);
	}

	/** 旧键/非媒体键的后缀兜底。 */
	private static String mimeFromExtension(String fn) {
		if (fn == null) {
			return "application/octet-stream";
		}
		String lower = fn.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
		if (lower.endsWith(".gif")) return "image/gif";
		if (lower.endsWith(".webp")) return "image/webp";
		if (lower.endsWith(".svg")) return "image/svg+xml";
		if (lower.endsWith(".mp3")) return "audio/mpeg";
		if (lower.endsWith(".wav")) return "audio/wav";
		if (lower.endsWith(".ogg")) return "audio/ogg";
		if (lower.endsWith(".mp4")) return "video/mp4";
		if (lower.endsWith(".webm")) return "video/webm";
		if (lower.endsWith(".mov")) return "video/quicktime";
		if (lower.endsWith(".json")) return "application/json";
		if (lower.endsWith(".txt")) return "text/plain";
		return "application/octet-stream";
	}

	private static boolean startsWith(byte[] data, int... prefix) {
		if (data == null || data.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if ((data[i] & 0xFF) != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean startsWithText(byte[] data, int offset, String text) {
		if (data == null || data.length < offset + text.length()) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if ((data[offset + i] & 0xFF) != (text.charAt(i) & 0xFF)) {
				return false;
			}
		}
		return true;
	}

	private static int firstNonWhitespace(byte[] data) {
		for (int i = 0; i < data.length; i++) {
			if (!Character.isWhitespace((char) (data[i] & 0xFF))) {
				return i;
			}
		}
		return -1;
	}
}
