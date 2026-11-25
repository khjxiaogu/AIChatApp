package com.khjxiaogu.aiwuxia.utils;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 内容寻址键工具。
 * <p>
 * 键 = SHA-256(内容) 的 64 位十六进制 + 内容长度（4 字节大端）的 8 位十六进制，共 72 位，
 * 例如 {@code fca846c4...0018044a}。服务端对象存储、语音 id 与本地模型部署侧的磁盘缓存
 * 都用同一套键，因此“服务端对象 id”可以直接当作“部署侧缓存键”使用。
 * </p>
 */
public final class ContentHash {

	/** 键长度：64 位摘要 + 8 位长度。 */
	public static final int KEY_LENGTH = 72;

	/** 完整的键：72 位小写十六进制，可带媒体扩展名（不含 {@code .caption} 这类附属文件后缀）。 */
	private static final Pattern KEY_PATTERN = Pattern.compile(
			"[0-9a-f]{72}(?:\\.(?:png|jpe?g|gif|webp|svg|bmp|mp4|webm|mov|mkv|mp3|wav|ogg|m4a|flac))?");

	private ContentHash() {
	}

	/**
	 * 计算内容键。
	 *
	 * @param data 内容，不能为 null
	 * @return 72 位十六进制键
	 */
	public static String keyOf(byte[] data) {
		if (data == null) {
			throw new IllegalArgumentException("data 不能为空");
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return hex(digest.digest(data)) + hex(ByteBuffer.allocate(4).putInt(data.length).array());
		} catch (NoSuchAlgorithmException ex) {
			// 所有 JVM 都必须支持 SHA-256
			throw new IllegalStateException("JVM 不支持 SHA-256", ex);
		}
	}

	/**
	 * 把“对象 id”归一化成缓存键：忽略大小写与末尾的媒体扩展名。
	 * <p>
	 * 对象存储 id 可能是裸键（{@code <72 位哈希>}），也可能带扩展名
	 * （{@code <72 位哈希>.mp4}）。缓存文件统一用裸键命名。
	 * </p>
	 *
	 * @param value 对象 id、附件里的内容键或任意字符串
	 * @return 72 位小写十六进制键；不是内容键时返回 {@code null}
	 */
	public static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String text = value.trim().toLowerCase(Locale.ROOT);
		if (text.length() < KEY_LENGTH || !KEY_PATTERN.matcher(text).matches()) {
			return null;
		}
		return text.substring(0, KEY_LENGTH);
	}

	/**
	 * 是否是内容键（允许带媒体扩展名）。
	 *
	 * @param value 待判断字符串
	 * @return 是内容键时返回 true
	 */
	public static boolean isKey(String value) {
		return normalize(value) != null;
	}

	/**
	 * 字节数组转小写十六进制。
	 *
	 * @param bytes 字节
	 * @return 十六进制字符串
	 */
	public static String hex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(Character.forDigit((value >> 4) & 0xF, 16));
			builder.append(Character.forDigit(value & 0xF, 16));
		}
		return builder.toString();
	}
}
