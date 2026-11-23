package com.khjxiaogu.aiwuxia.modelproxy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;

/**
 * 本地模型请求附件的服务端注册表。
 * <p>
 * 服务器在构造调用时把附件“登记”进来（只登记字节来源，不内联进报文），客户端拿到
 * {@link AttachmentRef} 后按需在**同一条 WebSocket** 上拉取：
 * {@code {"type":"attachment.get","reqid":..,"id":..}}，服务器按 {@code LMA1} 二进制分块回传。
 * </p>
 * <p>
 * 安全与生命周期：
 * </p>
 * <ul>
 *   <li>id 是随机 UUID，作用域是“请求 + id”，两者都对不上时读不到；调用报文只有收到它的客户端能看到；</li>
 *   <li>附件在请求完成后 {@link #release(String)} 释放，未释放的按 TTL（默认 10 分钟）自动过期；</li>
 *   <li>字节来源是惰性求值的 {@link Supplier}，只有真正被拉取时才读对象存储/文件。</li>
 * </ul>
 */
public final class LocalModelAttachments {

	/** 默认有效期：10 分钟。 */
	private static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;

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
		final Supplier<byte[]> source;
		final long expiresAt;

		Entry(String reqid, String uid, String kind, String format, String filename, Supplier<byte[]> source,
				long expiresAt) {
			this.reqid = reqid;
			this.uid = uid;
			this.kind = kind;
			this.format = format;
			this.filename = filename;
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
		purge();
		String id = UUID.randomUUID().toString().replace("-", "");
		Entry entry = new Entry(reqid, uid, kind, format, filename, source, System.currentTimeMillis() + ttlMs);
		entries.put(key(reqid, id), entry);
		return new AttachmentRef(id, kind, format, filename, -1L);
	}

	/**
	 * 读取附件字节（完整内容）。
	 *
	 * @param reqid 所属请求
	 * @param id    附件 id
	 * @return 字节；不存在/已过期时返回 null
	 */
	public byte[] read(String reqid, String id) {
		purge();
		Entry entry = entries.get(key(reqid, id));
		if (entry == null || System.currentTimeMillis() > entry.expiresAt) {
			return null;
		}
		try {
			return entry.source.get();
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
