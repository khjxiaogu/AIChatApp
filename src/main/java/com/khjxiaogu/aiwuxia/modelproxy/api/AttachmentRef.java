package com.khjxiaogu.aiwuxia.modelproxy.api;

/**
 * 附件引用：请求对象里只出现引用，真正的字节由接收端向服务端拉取。
 * <p>
 * {@code id} 是不透明标识，作用域是“调用 + 用户”；服务端不把它映射到任何可猜测的路径，
 * 客户端只能凭收到的调用报文里的 id 拉取。{@code source} 只在服务端注册时使用，
 * 编解码器不会把它写进报文。
 * </p>
 */
public final class AttachmentRef {

	/** 附件种类：图片。 */
	public static final String KIND_IMAGE = "image";
	/** 附件种类：视频。 */
	public static final String KIND_VIDEO = "video";
	/** 附件种类：音频。 */
	public static final String KIND_AUDIO = "audio";
	/** 附件种类：其它二进制。 */
	public static final String KIND_BINARY = "binary";

	/** 不透明标识（UUID）。 */
	public final String id;
	/** 种类：{@link #KIND_IMAGE} 等。 */
	public final String kind;
	/** 回传格式/扩展名，例如 png、mp4、wav。 */
	public final String format;
	/** 建议文件名，可为空。 */
	public final String filename;
	/** 字节数，未知时为 -1。 */
	public final long size;
	/**
	 * 内容键（形如 72 位十六进制，可带媒体扩展名），未知时为空串。
	 * <p>
	 * 服务端在附件来源本身就是按内容寻址的对象存储时填这个字段（值就是对象存储 id）。
	 * 接收端可以据此在自己的磁盘缓存里直接找到同一份内容，从而跳过
	 * {@code attachment.get} 拉取；它只用于缓存命中，不参与访问控制，
	 * 真正读取附件仍然需要调用报文里的 {@link #id}。
	 * </p>
	 */
	public final String key;

	public AttachmentRef(String id, String kind, String format, String filename, long size) {
		this(id, kind, format, filename, size, null);
	}

	public AttachmentRef(String id, String kind, String format, String filename, long size, String key) {
		this.id = id;
		this.kind = kind == null || kind.isEmpty() ? KIND_BINARY : kind;
		this.format = format == null ? "" : format;
		this.filename = filename == null ? "" : filename;
		this.size = size;
		this.key = key == null ? "" : key;
	}

	@Override
	public String toString() {
		return "AttachmentRef[id=" + id + ", kind=" + kind + ", format=" + format + ", size=" + size
				+ (key.isEmpty() ? "" : ", key=" + key) + "]";
	}
}
