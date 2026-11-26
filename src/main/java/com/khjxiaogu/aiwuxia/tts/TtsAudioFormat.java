package com.khjxiaogu.aiwuxia.tts;

import java.util.Locale;

/**
 * OpenAI 兼容 TTS 接口的音频格式工具。
 * <p>
 * 本项目只接入本地语音后端，本地模型的回传格式由模型端决定，常见为 {@code mp3} 或
 * {@code wav}。本类负责把请求里的 {@code response_format} 与后端回传格式统一成小写
 * 短名，并映射到正确的 MIME 类型，避免把 wav 声明成 mp3 这类错误标注。
 * </p>
 */
public final class TtsAudioFormat {

	/** MP3 短名。 */
	public static final String MP3 = "mp3";
	/** WAV 短名。 */
	public static final String WAV = "wav";

	private TtsAudioFormat() {
	}

	/**
	 * 规范化格式名：去空白、转小写，并把常见别名映射为短名。
	 *
	 * @param format 原始格式名，可为 null
	 * @return 规范化后的短名；null 或空白时返回 null
	 */
	public static String normalize(String format) {
		if (format == null) {
			return null;
		}
		String value = format.trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty()) {
			return null;
		}
		if ("mpeg".equals(value) || "mpeg3".equals(value) || "audio/mpeg".equals(value)) {
			return MP3;
		}
		if ("wave".equals(value) || "x-wav".equals(value) || "audio/wav".equals(value)
				|| "audio/x-wav".equals(value)) {
			return WAV;
		}
		return value;
	}

	/**
	 * 本地语音后端能否按该格式直接输出（服务端不做转码）。
	 *
	 * @param format 规范化后的格式名
	 * @return mp3 / wav 返回 true
	 */
	public static boolean isLocalSupported(String format) {
		return MP3.equals(format) || WAV.equals(format);
	}

	/**
	 * 格式名对应的 MIME 类型。
	 *
	 * @param format 规范化后的格式名
	 * @return MIME 类型；未知格式返回 {@code application/octet-stream}
	 */
	public static String mimeOf(String format) {
		if (MP3.equals(format)) {
			return "audio/mpeg";
		}
		if (WAV.equals(format)) {
			return "audio/wav";
		}
		return "application/octet-stream";
	}
}
