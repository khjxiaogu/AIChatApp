package com.khjxiaogu.aiwuxia.modelproxy.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地模型一次调用的结果，独立的值对象。
 * <p>
 * 原来的 {@code data + format} 之外增加 {@code meta}，用于“格式携带的参数”
 * （例如视频时长），避免再把 {@code mp4;duration=3.2} 拼进 format 字符串。
 * </p>
 */
public final class LocalModelResult {

	/** 结果字节。 */
	public final byte[] data;
	/** 结果格式：png / jpg / mp4 / mp3 / wav / json。 */
	public final String format;
	/** 附加信息，只读。 */
	public final Map<String, String> meta;

	public LocalModelResult(byte[] data, String format, Map<String, String> meta) {
		this.data = data;
		this.format = format == null || format.isEmpty() ? "bin" : format;
		this.meta = meta == null || meta.isEmpty() ? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(meta));
	}

	/**
	 * 创建只有字节与格式的结果。
	 *
	 * @param data   结果字节
	 * @param format 结果格式
	 * @return 结果对象
	 */
	public static LocalModelResult of(byte[] data, String format) {
		return new LocalModelResult(data, format, null);
	}

	/**
	 * 创建带附加信息的结果。
	 *
	 * @param data   结果字节
	 * @param format 结果格式
	 * @param meta   附加信息，可为 null
	 * @return 结果对象
	 */
	public static LocalModelResult of(byte[] data, String format, Map<String, String> meta) {
		return new LocalModelResult(data, format, meta);
	}
}
