package com.khjxiaogu.aiwuxia.voice.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 本地模型通道二进制分块文件协议。
 * <p>
 * 结果文件不再包在文本 JSON 的 Base64 里，而是按下面的二进制帧分块发送，每块内容体最大
 * {@value #DEFAULT_FILE_CHUNK_SIZE} 字节（5 MiB），超过时分成多块：
 * </p>
 * <pre>
 * +--------+----------+--------+-----------+----------+---------+---------+---------+
 * | magic  | reqidLen | reqid  | formatLen | format   | current | total   | content |
 * | 4 'LMF1' | uint16 | UTF-8  | uint16    | UTF-8    | uint32  | uint32  | 剩余字节 |
 * +--------+----------+--------+-----------+----------+---------+---------+---------+
 * </pre>
 * <p>
 * {@code reqid} 与 {@code format} 长度不固定，因此都带长度前缀；{@code current} 从 1 开始，
 * {@code total} 为分包总数。服务端收齐 {@code 1..total} 后才拼接并完成请求，超时或分包不完整
 * 视为失败并丢弃已收到的块（见 {@link FileChunkAssembler}）。
 * </p>
 * <p>
 * 文本报文（请求、响应、进度、能力集合）仍然走 JSON，只有文件内容走本协议。
 * </p>
 */
public final class LocalModelProtocol {

	/** 报文类型字段。 */
	public static final String FIELD_TYPE = "type";
	/** 请求 ID 字段。 */
	public static final String FIELD_REQID = "reqid";
	/** 响应数据（Base64）字段。 */
	public static final String FIELD_DATA = "data";
	/** 响应格式字段。 */
	public static final String FIELD_FORMAT = "format";
	/** 错误说明字段。 */
	public static final String FIELD_ERROR = "error";
	/** 能力集合字段。 */
	public static final String FIELD_MODEL_TYPES = "model_types";
	/** 任务状态字段。 */
	public static final String FIELD_STATUS = "status";
	/** 进度比例字段（0~1，可选）。 */
	public static final String FIELD_PROGRESS = "progress";
	/** 进度说明字段（可选）。 */
	public static final String FIELD_MESSAGE = "message";

	/** 报文类型：进度上报。 */
	public static final String TYPE_PROGRESS = "progress";
	/** 报文类型：能力集合变更/回执。 */
	public static final String TYPE_CAPABILITIES = "capabilities";

	/** 任务状态：排队中（正在等待本地资源锁）。 */
	public static final String STATUS_QUEUED = "queued";
	/** 任务状态：执行中（请求已经下发给对应的本地软件）。 */
	public static final String STATUS_RUNNING = "running";

	/** 二进制文件分块魔数。 */
	public static final byte[] FILE_MAGIC = { 'L', 'M', 'F', '1' };
	/** 每块内容体上限：5 MiB。 */
	public static final int DEFAULT_FILE_CHUNK_SIZE = 5 * 1024 * 1024;
	/** 请求 ID 的 UTF-8 长度上限。 */
	public static final int MAX_REQID_BYTES = 512;
	/** 格式名的 UTF-8 长度上限。 */
	public static final int MAX_FORMAT_BYTES = 64;

	private LocalModelProtocol() {
	}

	/**
	 * 解析一条文本报文；不是合法 JSON 对象时返回 null（调用方自行忽略）。
	 *
	 * @param raw 原始报文
	 * @return JSON 对象或 null
	 */
	public static JsonObject parse(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		try {
			JsonElement element = JsonParser.parseString(raw);
			return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** 报文类型（{@code type} 字段），没有时返回空串。 */
	public static String typeOf(JsonObject message) {
		if (message == null || !message.has(FIELD_TYPE) || message.get(FIELD_TYPE).isJsonNull()) {
			return "";
		}
		String type = message.get(FIELD_TYPE).getAsString();
		return type == null ? "" : type.trim();
	}

	/** 是否是进度上报报文。 */
	public static boolean isProgress(JsonObject message) {
		return TYPE_PROGRESS.equalsIgnoreCase(typeOf(message));
	}

	/** 是否是能力集合报文（变更或回执）。 */
	public static boolean isCapabilities(JsonObject message) {
		return TYPE_CAPABILITIES.equalsIgnoreCase(typeOf(message));
	}

	/** 请求 ID；没有时返回空串。 */
	public static String reqid(JsonObject message) {
		if (message == null || !message.has(FIELD_REQID) || message.get(FIELD_REQID).isJsonNull()) {
			return "";
		}
		String reqid = message.get(FIELD_REQID).getAsString();
		return reqid == null ? "" : reqid.trim();
	}

	/** 请求里的模型类型（{@code type} 字段），没有或不是纯类型名时返回空串。 */
	public static String requestModelType(JsonObject request) {
		String type = typeOf(request);
		if (type.isEmpty() || TYPE_PROGRESS.equalsIgnoreCase(type) || TYPE_CAPABILITIES.equalsIgnoreCase(type)) {
			return "";
		}
		return type;
	}

	/** 能力集合转字符串列表。 */
	public static List<String> modelTypes(JsonObject message) {
		List<String> types = new ArrayList<>();
		if (message == null || !message.has(FIELD_MODEL_TYPES) || !message.get(FIELD_MODEL_TYPES).isJsonArray()) {
			return types;
		}
		for (JsonElement element : message.getAsJsonArray(FIELD_MODEL_TYPES)) {
			if (element != null && element.isJsonPrimitive()) {
				String value = element.getAsString();
				if (value != null && !value.trim().isEmpty()) {
					types.add(value.trim());
				}
			}
		}
		return types;
	}

	/**
	 * 构造进度报文。
	 *
	 * @param reqid    请求 ID
	 * @param status   状态（{@link #STATUS_QUEUED} / {@link #STATUS_RUNNING}）
	 * @param progress 进度比例，可为 null
	 * @param detail   说明，可为 null
	 * @return 报文
	 */
	public static JsonObject progress(String reqid, String status, Double progress, String detail) {
		JsonObject message = new JsonObject();
		message.addProperty(FIELD_TYPE, TYPE_PROGRESS);
		message.addProperty(FIELD_REQID, reqid);
		message.addProperty(FIELD_STATUS, status == null || status.isEmpty() ? STATUS_RUNNING : status);
		if (progress != null) {
			message.addProperty(FIELD_PROGRESS, Math.max(0d, Math.min(1d, progress)));
		}
		if (detail != null && !detail.trim().isEmpty()) {
			message.addProperty(FIELD_MESSAGE, detail.trim());
		}
		return message;
	}

	/**
	 * 构造能力集合报文（变更或回执）。
	 *
	 * @param modelTypes 模型类型名（小写）
	 * @return 报文
	 */
	public static JsonObject capabilities(Collection<String> modelTypes) {
		JsonObject message = new JsonObject();
		message.addProperty(FIELD_TYPE, TYPE_CAPABILITIES);
		JsonArray array = new JsonArray();
		if (modelTypes != null) {
			for (String type : modelTypes) {
				if (type != null && !type.trim().isEmpty()) {
					array.add(type.trim());
				}
			}
		}
		message.add(FIELD_MODEL_TYPES, array);
		return message;
	}

	/**
	 * 构造成功响应（文本 Base64 形式，旧路径保留用于兼容）。
	 *
	 * @param reqid  请求 ID
	 * @param data   结果字节
	 * @param format 结果格式
	 * @return 报文
	 */
	public static JsonObject reply(String reqid, byte[] data, String format) {
		JsonObject message = new JsonObject();
		message.addProperty(FIELD_REQID, reqid);
		message.addProperty(FIELD_DATA, Base64.getEncoder().encodeToString(data == null ? new byte[0] : data));
		message.addProperty(FIELD_FORMAT, format == null || format.isEmpty() ? "json" : format);
		return message;
	}

	/**
	 * 构造错误响应。
	 *
	 * @param reqid 请求 ID
	 * @param error 错误说明
	 * @return 报文
	 */
	public static JsonObject error(String reqid, String error) {
		JsonObject message = new JsonObject();
		message.addProperty(FIELD_REQID, reqid);
		message.addProperty(FIELD_ERROR, error == null ? "unknown error" : error);
		return message;
	}

	// ==================== 二进制文件分块 ====================

	/**
	 * 计算文件需要分成多少块。
	 *
	 * @param totalBytes 文件字节数，可为 0
	 * @param chunkSize  每块内容体上限，非正数时使用 {@value #DEFAULT_FILE_CHUNK_SIZE}
	 * @return 分块数，至少 1
	 */
	public static int fileChunkCount(int totalBytes, int chunkSize) {
		int contentMax = chunkSize > 0 ? Math.min(chunkSize, DEFAULT_FILE_CHUNK_SIZE)
				: DEFAULT_FILE_CHUNK_SIZE;
		return totalBytes <= 0 ? 1 : (totalBytes + contentMax - 1) / contentMax;
	}

	/**
	 * 构造单个二进制分块帧（便于大文件逐块发送，避免一次性持有全部帧）。
	 *
	 * @param reqid   请求 ID
	 * @param format  结果格式
	 * @param data    完整文件字节
	 * @param offset  本块在文件中的偏移
	 * @param length  本块内容长度，必须不超过 5 MiB
	 * @param current 当前块序号，从 1 开始
	 * @param total   总块数
	 * @return 二进制帧
	 * @throws IllegalArgumentException 参数超出协议限制
	 */
	public static byte[] encodeFileChunk(String reqid, String format, byte[] data, int offset, int length,
			int current, int total) {
		String id = reqid == null ? "" : reqid;
		String fmt = format == null || format.trim().isEmpty() ? "bin" : format.trim();
		byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
		byte[] fmtBytes = fmt.getBytes(StandardCharsets.UTF_8);
		if (idBytes.length == 0 || idBytes.length > MAX_REQID_BYTES) {
			throw new IllegalArgumentException("reqid 长度非法：" + idBytes.length);
		}
		if (fmtBytes.length > MAX_FORMAT_BYTES) {
			throw new IllegalArgumentException("format 长度非法：" + fmtBytes.length);
		}
		if (total < 1 || total > FileChunkAssembler.MAX_CHUNKS || current < 1 || current > total) {
			throw new IllegalArgumentException("分块序号非法：" + current + "/" + total);
		}
		if (length < 0 || length > DEFAULT_FILE_CHUNK_SIZE) {
			throw new IllegalArgumentException("分块内容长度非法：" + length);
		}
		byte[] payload = data == null ? new byte[0] : data;
		if (offset < 0 || offset + length > payload.length) {
			throw new IllegalArgumentException("分块偏移越界：" + offset + "+" + length);
		}
		int size = FILE_MAGIC.length + 2 + idBytes.length + 2 + fmtBytes.length + 4 + 4 + length;
		ByteBuffer buffer = ByteBuffer.allocate(size);
		buffer.put(FILE_MAGIC);
		buffer.putShort((short) idBytes.length);
		buffer.put(idBytes);
		buffer.putShort((short) fmtBytes.length);
		buffer.put(fmtBytes);
		buffer.putInt(current);
		buffer.putInt(total);
		if (length > 0) {
			buffer.put(payload, offset, length);
		}
		return buffer.array();
	}

	/**
	 * 把一个结果文件切成若干二进制帧。
	 * <p>
	 * 小文件一般用不到本方法；连接器发送大文件时应按 {@link #fileChunkCount(int, int)} 逐块调用
	 * {@link #encodeFileChunk(String, String, byte[], int, int, int, int)}，避免一次性复制整个文件。
	 * </p>
	 *
	 * @param reqid     请求 ID（UTF-8 不超过 {@value #MAX_REQID_BYTES} 字节）
	 * @param format    结果格式（如 {@code mp4} / {@code png} / {@code mp3} / {@code json}）
	 * @param data      完整文件字节，可为 null/空
	 * @param chunkSize 每块内容体上限，非正数时使用 {@value #DEFAULT_FILE_CHUNK_SIZE}
	 * @return 二进制帧列表，至少一帧
	 * @throws IllegalArgumentException 参数超出协议限制
	 */
	public static List<byte[]> encodeFileChunks(String reqid, String format, byte[] data, int chunkSize) {
		byte[] payload = data == null ? new byte[0] : data;
		if (payload.length > FileChunkAssembler.MAX_TOTAL_BYTES) {
			throw new IllegalArgumentException("文件超过 " + FileChunkAssembler.MAX_TOTAL_BYTES + " 字节上限");
		}
		int contentMax = chunkSize > 0 ? Math.min(chunkSize, DEFAULT_FILE_CHUNK_SIZE)
				: DEFAULT_FILE_CHUNK_SIZE;
		int total = fileChunkCount(payload.length, contentMax);
		List<byte[]> frames = new ArrayList<>(total);
		for (int current = 1; current <= total; current++) {
			int offset = (current - 1) * contentMax;
			int length = Math.min(contentMax, payload.length - offset);
			frames.add(encodeFileChunk(reqid, format, payload, offset, Math.max(0, length), current, total));
		}
		return frames;
	}

	/**
	 * 是否是二进制文件分块帧。
	 *
	 * @param frame 二进制报文字节
	 * @return true 表示魔数匹配
	 */
	public static boolean isFileChunk(byte[] frame) {
		if (frame == null || frame.length < FILE_MAGIC.length) {
			return false;
		}
		for (int i = 0; i < FILE_MAGIC.length; i++) {
			if (frame[i] != FILE_MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 解析一条二进制文件分块帧；格式非法时返回 null。
	 *
	 * @param frame 二进制报文字节
	 * @return 分块对象或 null
	 */
	public static FileChunk parseFileChunk(byte[] frame) {
		if (!isFileChunk(frame) || frame.length < FILE_MAGIC.length + 2 + 2 + 4 + 4) {
			return null;
		}
		try {
			ByteBuffer buffer = ByteBuffer.wrap(frame);
			buffer.position(FILE_MAGIC.length);
			int idLength = Short.toUnsignedInt(buffer.getShort());
			if (idLength <= 0 || idLength > MAX_REQID_BYTES || buffer.remaining() < idLength + 2) {
				return null;
			}
			byte[] idBytes = new byte[idLength];
			buffer.get(idBytes);
			int fmtLength = Short.toUnsignedInt(buffer.getShort());
			if (fmtLength <= 0 || fmtLength > MAX_FORMAT_BYTES || buffer.remaining() < fmtLength + 8) {
				return null;
			}
			byte[] fmtBytes = new byte[fmtLength];
			buffer.get(fmtBytes);
			int current = buffer.getInt();
			int total = buffer.getInt();
			if (current < 1 || total < 1 || current > total || total > FileChunkAssembler.MAX_CHUNKS) {
				return null;
			}
			int contentLength = buffer.remaining();
			if (contentLength > DEFAULT_FILE_CHUNK_SIZE) {
				return null;
			}
			byte[] content = new byte[contentLength];
			buffer.get(content);
			return new FileChunk(new String(idBytes, StandardCharsets.UTF_8),
					new String(fmtBytes, StandardCharsets.UTF_8), current, total, content);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** 一个二进制文件分块。 */
	public static final class FileChunk {
		/** 请求 ID。 */
		public final String reqid;
		/** 结果格式。 */
		public final String format;
		/** 当前块序号，从 1 开始。 */
		public final int current;
		/** 总块数。 */
		public final int total;
		/** 内容体。 */
		public final byte[] content;

		/**
		 * @param reqid   请求 ID
		 * @param format  结果格式
		 * @param current 当前块序号
		 * @param total   总块数
		 * @param content 内容体
		 */
		public FileChunk(String reqid, String format, int current, int total, byte[] content) {
			this.reqid = reqid;
			this.format = format;
			this.current = current;
			this.total = total;
			this.content = content == null ? new byte[0] : content;
		}

		/** 是否与另一块的 reqid/format/total 匹配。 */
		public boolean sameFile(FileChunk other) {
			return other != null && reqid.equals(other.reqid) && format.equals(other.format)
					&& total == other.total;
		}

		@Override
		public String toString() {
			return "FileChunk[" + reqid + "," + format + "," + current + "/" + total + ","
					+ content.length + "B]";
		}
	}
}
