package com.khjxiaogu.aiwuxia.modelproxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.utils.ContentHash;
import com.khjxiaogu.aiwuxia.utils.RemoteUrl;

/**
 * 本地模型请求附件的服务端注册表。
 * <p>
 * 服务器在构造调用时把附件“登记”进来（只登记字节来源，不内联进报文），客户端拿到
 * {@link AttachmentRef} 后按需在**同一条 WebSocket** 上拉取：
 * {@code {"type":"attachment.get","reqid":..,"id":..}}，服务器按 {@code LMA1} 二进制分块回传。
 * </p>
 * <p>
 * 登记时也可以只给一个 URL（见 {@link #registerUrl}）：报文里带上 {@code url}，客户端直接下载，
 * 服务端完全不必先把文件下载/转存一遍。URL 附件同时可以带内容键，客户端据此先查自己的磁盘缓存。
 * </p>
 * <p>
 * 安全与生命周期：
 * </p>
 * <ul>
 *   <li>id 是随机 UUID，作用域是“请求 + id”，两者都对不上时读不到；调用报文只有收到它的客户端能看到；</li>
 *   <li>附件在请求完成后 {@link #release(String)} 释放，未释放的按 TTL（默认 10 分钟）自动过期；</li>
 *   <li>字节来源是惰性求值的 {@link Supplier}，只有真正被拉取时才读对象存储/文件；</li>
 *   <li>只有 URL、没有字节来源的附件不会在服务端下载；服务端直连模型（{@code localChannel}）
 *       需要字节时才会退回按 URL 下载一次。</li>
 * </ul>
 */
public final class LocalModelAttachments {

	/** 默认有效期：10 分钟。 */
	private static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;
	/** 服务端直连模型时按 URL 兜底下载的大小上限：256 MiB。 */
	private static final long FALLBACK_URL_MAX_BYTES = 256L * 1024 * 1024;

	private static final LocalModelAttachments SHARED = new LocalModelAttachments(DEFAULT_TTL_MS);

	/** 全局注册表。 */
	public static LocalModelAttachments shared() {
		return SHARED;
	}

	/** 一个附件的登记信息。 */
	private static final class Entry {
		final String reqid;
		final String uid;
		final String kind;
		final String format;
		final String filename;
		final String url;
		final Supplier<byte[]> source;
		final long expiresAt;

		Entry(String reqid, String uid, String kind, String format, String filename, String url,
				Supplier<byte[]> source, long expiresAt) {
			this.reqid = reqid;
			this.uid = uid;
			this.kind = kind;
			this.format = format;
			this.filename = filename;
			this.url = url;
			this.source = source;
			this.expiresAt = expiresAt;
		}
	}

	/** 有效期（毫秒）。 */
	private final long ttlMs;
	/** key = reqid + "|" + id */
	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	public LocalModelAttachments(long ttlMs) {
		this.ttlMs = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
	}

	private static String key(String reqid, String id) {
		return (reqid == null ? "" : reqid) + "|" + (id == null ? "" : id);
	}

	/**
	 * 登记一个附件。
	 *
	 * @param reqid    所属请求
	 * @param uid      所属用户，可为 null
	 * @param kind     种类（{@link AttachmentRef#KIND_IMAGE} 等）
	 * @param format   格式/扩展名
	 * @param filename 建议文件名
	 * @param source   字节来源（惰性）
	 * @return 附件引用，供写进调用报文
	 */
	public AttachmentRef register(String reqid, String uid, String kind, String format, String filename,
			Supplier<byte[]> source) {
		return register(reqid, uid, kind, format, filename, null, source);
	}

	/**
	 * 登记一个附件，并带上内容键。
	 * <p>
	 * 内容键就是对象存储 id（72 位十六进制，可带扩展名）时，接收端可以直接在本地缓存里
	 * 找到同一份内容，不需要再发 {@code attachment.get}。来源不是按内容寻址的存储时传 null。
	 * </p>
	 *
	 * @param reqid      所属请求
	 * @param uid        所属用户，可为 null
	 * @param kind       种类（{@link AttachmentRef#KIND_IMAGE} 等）
	 * @param format     格式/扩展名
	 * @param filename   建议文件名
	 * @param contentKey 内容键，可为 null
	 * @param source     字节来源（惰性）
	 * @return 附件引用，供写进调用报文
	 */
	public AttachmentRef register(String reqid, String uid, String kind, String format, String filename,
			String contentKey, Supplier<byte[]> source) {
		return registerInternal(reqid, uid, kind, format, filename, contentKey, null, source);
	}

	/**
	 * 登记一个“只给 URL”的附件：接收端直接下载 URL，服务端不预先下载转存。
	 *
	 * @param reqid      所属请求
	 * @param uid        所属用户，可为 null
	 * @param kind       种类（{@link AttachmentRef#KIND_IMAGE} 等）
	 * @param format     格式/扩展名
	 * @param filename   建议文件名
	 * @param url        可下载地址（http(s)）
	 * @param contentKey 内容键（能从 URL 解析出对象 id 时传入），可为 null
	 * @param fallback   服务端兜底字节来源；客户端 URL 下载失败、改用 {@code attachment.get} 时才会用到，可为 null
	 * @return 附件引用，供写进调用报文
	 */
	public AttachmentRef registerUrl(String reqid, String uid, String kind, String format, String filename,
			String url, String contentKey, Supplier<byte[]> fallback) {
		return registerInternal(reqid, uid, kind, format, filename, contentKey, url, fallback);
	}

	private AttachmentRef registerInternal(String reqid, String uid, String kind, String format, String filename,
			String contentKey, String url, Supplier<byte[]> source) {
		purge();
		String id = UUID.randomUUID().toString().replace("-", "");
		String key = ContentHash.normalize(contentKey);
		Entry entry = new Entry(reqid, uid, kind, format, filename, url, source,
				System.currentTimeMillis() + ttlMs);
		entries.put(key(reqid, id), entry);
		return new AttachmentRef(id, kind, format, filename, -1L, key, url);
	}

	/**
	 * 读取附件字节（完整内容）。
	 * <p>
	 * 只有 URL、没有字节来源的附件（服务端直连模型时）会按 URL 下载一次；这也是客户端
	 * 下载失败后改用 {@code attachment.get} 的兜底路径。
	 * </p>
	 *
	 * @param reqid 所属请求
	 * @param id    附件 id
	 * @return 字节；不存在/已过期/下载失败时返回 null
	 */
	public byte[] read(String reqid, String id) {
		purge();
		Entry entry = entries.get(key(reqid, id));
		if (entry == null || System.currentTimeMillis() > entry.expiresAt) {
			return null;
		}
		try {
			if (entry.source != null) {
				return entry.source.get();
			}
			if (entry.url != null && !entry.url.isEmpty()) {
				System.out.println("[LocalModel] 附件没有本地字节来源，按 URL 兜底下载：reqid=" + reqid
						+ " id=" + id + " url=" + RemoteUrl.shortUrl(entry.url));
				return RemoteUrl.download(entry.url, FALLBACK_URL_MAX_BYTES);
			}
			return null;
		} catch (IOException ex) {
			System.err.println("[LocalModel] 按 URL 下载附件失败：reqid=" + reqid + " id=" + id + " 错误=" + ex);
			return null;
		} catch (RuntimeException ex) {
			System.err.println("[LocalModel] 读取附件失败：reqid=" + reqid + " id=" + id + " 错误=" + ex);
			return null;
		}
	}

	/**
	 * 读取附件信息（不含字节）。
	 *
	 * @param reqid 所属请求
	 * @param id    附件 id
	 * @return 登记信息；不存在时返回 null
	 */
	public AttachmentInfo info(String reqid, String id) {
		purge();
		Entry entry = entries.get(key(reqid, id));
		if (entry == null || System.currentTimeMillis() > entry.expiresAt) {
			return null;
		}
		return new AttachmentInfo(entry.kind, entry.format, entry.filename);
	}

	/**
	 * 释放某个请求的全部附件。
	 *
	 * @param reqid 请求 ID
	 */
	public void release(String reqid) {
		String prefix = (reqid == null ? "" : reqid) + "|";
		Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getKey().startsWith(prefix)) {
				it.remove();
			}
		}
	}

	/** 已登记的附件数量（测试用）。 */
	public int size() {
		purge();
		return entries.size();
	}

	/** 清理过期条目。 */
	private void purge() {
		long now = System.currentTimeMillis();
		List<String> expired = new ArrayList<>();
		for (Map.Entry<String, Entry> entry : entries.entrySet()) {
			if (now > entry.getValue().expiresAt) {
				expired.add(entry.getKey());
			}
		}
		for (String key : expired) {
			entries.remove(key);
		}
	}

	/** 附件元信息。 */
	public static final class AttachmentInfo {
		/** 种类。 */
		public final String kind;
		/** 格式。 */
		public final String format;
		/** 文件名。 */
		public final String filename;

		AttachmentInfo(String kind, String format, String filename) {
			this.kind = kind;
			this.format = format;
			this.filename = filename;
		}
	}
}
