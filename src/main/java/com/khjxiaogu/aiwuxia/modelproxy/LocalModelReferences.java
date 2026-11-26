package com.khjxiaogu.aiwuxia.modelproxy;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.ContentHash;
import com.khjxiaogu.aiwuxia.utils.RemoteUrl;

/**
 * 把工具参数里的“媒体引用”登记成本地模型附件。
 * <p>
 * 工具参数可能是以下任意一种写法，本类统一处理：
 * </p>
 * <ul>
 *   <li><b>对象存储 id</b>（72 位内容哈希，可带扩展名）：查对象存储是否存在，登记成按内容键
 *       惰性读取的附件；客户端本地缓存已有同一份内容时不会产生附件传输；</li>
 *   <li><b>项目自身的媒体链接</b>（例如 {@code /painter/image?image_id=...}、
 *       {@code /aichat/mcp/media?file_id=...}）：保留 URL，同时从链接中提取对象 id 作为内容键，
 *       部署侧先查本地缓存；服务端只在该对象存在时保留惰性兜底，客户端 URL 下载失败才回源；</li>
 *   <li><b>外部 http(s) 链接</b>：只登记 URL，正常路径由部署侧的本地模型客户端下载；
 *       只有旧客户端或客户端 URL 下载失败、改用 {@code attachment.get} 时，服务端才会懒加载兜底；</li>
 *   <li><b>data URL</b>：服务端解码后按内容寻址写入对象存储，再按内容键登记附件。</li>
 * </ul>
 * <p>
 * 返回 {@code null} 表示引用无法识别或对象已经被清理，调用方按“参数错误/已清理”处理。
 * </p>
 */
public final class LocalModelReferences {

	/** 单次 data URL 解码的大小上限：128 MiB。 */
	private static final long MAX_DATA_URL_BYTES = 128L * 1024 * 1024;
	/** 服务端兜底下载 URL 的大小上限：256 MiB（只兼容旧客户端/URL 下载失败）。 */
	private static final long MAX_FALLBACK_URL_BYTES = 256L * 1024 * 1024;

	private LocalModelReferences() {
	}

	/**
	 * 登记一个媒体引用为本地模型附件。
	 *
	 * @param storage   对象存储，可为 null（此时只接受 URL/data URL）
	 * @param reqid     请求 ID
	 * @param uid       所属用户，可为 null
	 * @param reference 引用：对象 id、项目链接、外部链接或 data URL
	 * @param kind      附件种类（{@link AttachmentRef#KIND_IMAGE} 等）
	 * @param format    期望扩展名（可带点），可为空；为空时按引用/URL 推断
	 * @param filename  建议文件名，可为空
	 * @param addUsage  用量回调，可为 null
	 * @return 附件引用；无法识别或对象不存在时返回 null
	 * @throws IOException data URL 解码或对象存储上传失败
	 */
	public static AttachmentRef register(ObjectStorageProvider storage, String reqid, String uid, String reference,
			String kind, String format, String filename, Consumer<UsageIntf<?>> addUsage) throws IOException {
		if (reference == null) {
			return null;
		}
		String text = reference.trim();
		if (text.isEmpty()) {
			return null;
		}
		// data URL：内联内容只能由服务端解码入库
		if (RemoteUrl.isDataUrl(text)) {
			byte[] data = RemoteUrl.decodeDataUrl(text);
			if (data == null || data.length == 0 || data.length > MAX_DATA_URL_BYTES || storage == null) {
				return null;
			}
			String resolvedFormat = resolveFormat(format, text, kind);
			String extension = resolvedFormat.isEmpty() ? "" : "." + resolvedFormat;
			String storedId = storage.uploadIfNotExists(data, extension, addUsage);
			if (storedId == null || storedId.isEmpty()) {
				return null;
			}
			return LocalModelAttachments.shared().register(reqid, uid, kind, resolvedFormat,
					resolveFilename(filename, resolvedFormat), storedId, () -> data);
		}
		// http(s) 链接：服务端不下载，原样交给本地模型客户端
		if (RemoteUrl.isHttp(text)) {
			String objectId = RemoteUrl.objectIdOf(text);
			String contentKey = ContentHash.normalize(objectId);
			String resolvedFormat = resolveFormat(format, text, kind);
			Consumer<UsageIntf<?>> usage = addUsage;
			// 兜底字节来源只在客户端改用 attachment.get 时才会触发：
			// 项目对象存储链接优先读本地/对象存储；其它链接退回服务端下载（兼容旧客户端）
			java.util.function.Supplier<byte[]> fallback;
			if (objectId != null && storage != null && storage.exists(objectId, addUsage)) {
				final String storageId = objectId;
				fallback = () -> {
					try {
						return storage.download(storageId, usage);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				};
			} else {
				final String sourceUrl = text;
				fallback = () -> {
					try {
						return RemoteUrl.download(sourceUrl, MAX_FALLBACK_URL_BYTES);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				};
			}
			System.out.println("[LocalModel] 附件使用 URL 直发（服务端不下载）：kind=" + kind + " key="
					+ (contentKey == null ? "无" : shortKey(contentKey)) + " url=" + RemoteUrl.shortUrl(text));
			return LocalModelAttachments.shared().registerUrl(reqid, uid, kind, resolvedFormat,
					resolveFilename(filename, resolvedFormat), text, objectId, fallback);
		}
		// 对象存储 id：登记惰性读取附件
		String objectId = RemoteUrl.objectIdOf(text);
		if (objectId == null || storage == null) {
			return null;
		}
		if (!storage.exists(objectId, addUsage)) {
			return null;
		}
		String resolvedFormat = resolveFormat(format, objectId, kind);
		String storageId = objectId;
		Consumer<UsageIntf<?>> usage = addUsage;
		return LocalModelAttachments.shared().register(reqid, uid, kind, resolvedFormat,
				resolveFilename(filename, resolvedFormat), objectId, () -> {
					try {
						return storage.download(storageId, usage);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				});
	}

	/**
	 * 引用是否是本类能处理的形式（不做对象存在性检查）。
	 *
	 * @param reference 引用
	 * @return 可以处理时返回 true
	 */
	public static boolean isSupportedReference(String reference) {
		if (reference == null || reference.trim().isEmpty()) {
			return false;
		}
		String text = reference.trim();
		return RemoteUrl.isHttp(text) || RemoteUrl.isDataUrl(text) || RemoteUrl.objectIdOf(text) != null;
	}

	/** 扩展名：显式参数优先，其次引用/URL，最后按附件种类给默认值。 */
	private static String resolveFormat(String format, String reference, String kind) {
		String value = normalizeFormat(format);
		if (!value.isEmpty()) {
			return value;
		}
		value = RemoteUrl.extensionOf(reference);
		if (!value.isEmpty()) {
			return value;
		}
		return defaultFormat(kind);
	}

	/** 规范化扩展名：去点、转小写；不认识的扩展名原样保留（限制长度）。 */
	private static String normalizeFormat(String format) {
		if (format == null) {
			return "";
		}
		String value = format.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith(".")) {
			value = value.substring(1);
		}
		return value.matches("[0-9a-z]{1,16}") ? value : "";
	}

	/** 附件种类的默认扩展名。 */
	private static String defaultFormat(String kind) {
		if (AttachmentRef.KIND_IMAGE.equals(kind)) {
			return "png";
		}
		if (AttachmentRef.KIND_VIDEO.equals(kind)) {
			return "mp4";
		}
		if (AttachmentRef.KIND_AUDIO.equals(kind)) {
			return "mp3";
		}
		return "";
	}

	/** 建议文件名：显式给出优先，否则用通用名 + 扩展名。 */
	private static String resolveFilename(String filename, String format) {
		if (filename != null && !filename.trim().isEmpty()) {
			return filename.trim();
		}
		return format.isEmpty() ? "input" : "input." + format;
	}

	/** 日志里只显示内容键前 12 位。 */
	private static String shortKey(String key) {
		return key == null || key.length() <= 12 ? key : key.substring(0, 12) + "...";
	}
}
