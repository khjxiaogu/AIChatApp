package com.khjxiaogu.aiwuxia.vision;

import java.nio.charset.StandardCharsets;

/**
 * 轻量 MP4 时长读取。
 * <p>
 * Foley 计费只需要视频时长；直接在调用端解析 MP4 的 {@code moov/mvhd} 即可，不必为了
 * 拿时长额外向 ComfyUI 提交一次时长探测工作流。
 * </p>
 */
public final class Mp4Duration {

	private Mp4Duration() {
	}

	/**
	 * 读取 MP4 电影头时长。
	 *
	 * @param data MP4 字节
	 * @return 时长（秒）；无法解析时返回 0
	 */
	public static double seconds(byte[] data) {
		if (data == null || data.length < 16) {
			return 0d;
		}
		try {
			return findMovieHeader(data, 0, data.length, 0);
		} catch (RuntimeException ex) {
			return 0d;
		}
	}

	private static double findMovieHeader(byte[] data, int start, int end, int depth) {
		if (depth > 8) {
			return 0d;
		}
		int offset = start;
		while (offset + 8 <= end) {
			long size = readUInt32(data, offset);
			String type = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
			int headerSize = 8;
			if (size == 1) {
				if (offset + 16 > end) {
					return 0d;
				}
				size = readUInt64(data, offset + 8);
				headerSize = 16;
			} else if (size == 0) {
				size = end - offset;
			}
			if (size < headerSize || offset + size > end) {
				return 0d;
			}
			if ("mvhd".equals(type)) {
				return parseMovieHeader(data, offset + headerSize, (int) size - headerSize);
			}
			if ("moov".equals(type) || "trak".equals(type) || "mdia".equals(type) || "minf".equals(type)
					|| "stbl".equals(type)) {
				double found = findMovieHeader(data, offset + headerSize,
						(int) Math.min(end, offset + size), depth + 1);
				if (found > 0d) {
					return found;
				}
			}
			offset += (int) size;
		}
		return 0d;
	}

	private static double parseMovieHeader(byte[] data, int start, int length) {
		if (length < 20 || start + length > data.length) {
			return 0d;
		}
		int version = data[start] & 0xFF;
		int timescaleOffset = version == 1 ? start + 20 : start + 12;
		int durationOffset = version == 1 ? start + 24 : start + 16;
		if (durationOffset + (version == 1 ? 8 : 4) > data.length) {
			return 0d;
		}
		long timescale = readUInt32(data, timescaleOffset);
		long duration = version == 1 ? readUInt64(data, durationOffset) : readUInt32(data, durationOffset);
		if (timescale <= 0 || duration <= 0) {
			return 0d;
		}
		return (double) duration / (double) timescale;
	}

	private static long readUInt32(byte[] data, int offset) {
		return ((long) (data[offset] & 0xFF) << 24)
				| ((long) (data[offset + 1] & 0xFF) << 16)
				| ((long) (data[offset + 2] & 0xFF) << 8)
				| (data[offset + 3] & 0xFF);
	}

	private static long readUInt64(byte[] data, int offset) {
		return (readUInt32(data, offset) << 32) | readUInt32(data, offset + 4);
	}
}
