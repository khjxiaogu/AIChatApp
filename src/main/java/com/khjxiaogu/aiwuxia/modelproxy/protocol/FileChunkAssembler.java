package com.khjxiaogu.aiwuxia.modelproxy.protocol;

/**
 * 二进制文件分块的接收端组装器。
 * <p>
 * 服务端每收到一块就交给本对象校验、缓存；只有 {@code 1..total} 全部到齐
 * （{@link #isComplete()}）才允许 {@link #assemble()} 拼接。分包数量、格式或总大小不一致时抛出
 * {@link IllegalArgumentException}，调用方应视为失败并丢弃已收到的块。
 * </p>
 * <p>
 * 进度协商用 {@link #touch(long)} 续期：每收到一块就把接收截止时间向后推，超过截止时间仍不完整
 * 时由服务端判定超时（见 {@code LocalModelHandshaker}），失败并清空缓存。
 * </p>
 */
public final class FileChunkAssembler {

	/** 分块数量上限，防止异常请求占满内存。 */
	public static final int MAX_CHUNKS = 100_000;
	/** 单个文件的总字节上限：1 GiB。 */
	public static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

	private final String reqid;
	private final String format;
	private final int total;
	private final byte[][] chunks;
	private int received;
	private long bytes;
	private volatile long deadline;
	private boolean complete;

	private FileChunkAssembler(String reqid, String format, int total, long deadline) {
		this.reqid = reqid;
		this.format = format;
		this.total = total;
		this.chunks = new byte[total][];
		this.deadline = deadline;
	}

	/**
	 * 用第一块开始组装。
	 *
	 * @param first    第一块
	 * @param deadline 接收截止时间（毫秒时间戳）
	 * @return 组装器
	 * @throws IllegalArgumentException 第一块非法
	 */
	public static FileChunkAssembler start(LocalModelProtocol.FileChunk first, long deadline) {
		if (first == null || first.total < 1 || first.total > MAX_CHUNKS) {
			throw new IllegalArgumentException("invalid first chunk");
		}
		FileChunkAssembler assembler = new FileChunkAssembler(first.reqid, first.format, first.total, deadline);
		assembler.accept(first);
		return assembler;
	}

	/**
	 * 接收一块。
	 * <p>
	 * 重复块会被忽略（幂等）；reqid/format/total 不一致、块序号越界或总大小超限时抛出
	 * {@link IllegalArgumentException}。
	 * </p>
	 *
	 * @param chunk 分块
	 */
	public synchronized void accept(LocalModelProtocol.FileChunk chunk) {
		if (chunk == null) {
			throw new IllegalArgumentException("chunk is null");
		}
		if (!reqid.equals(chunk.reqid)) {
			throw new IllegalArgumentException("reqid mismatch");
		}
		if (!format.equals(chunk.format)) {
			throw new IllegalArgumentException("format mismatch");
		}
		if (chunk.total != total) {
			throw new IllegalArgumentException("total mismatch");
		}
		if (chunk.current < 1 || chunk.current > total) {
			throw new IllegalArgumentException("chunk index out of range: " + chunk.current);
		}
		if (chunks[chunk.current - 1] != null) {
			// 重复块：幂等忽略
			return;
		}
		bytes += chunk.content.length;
		if (bytes > MAX_TOTAL_BYTES) {
			throw new IllegalArgumentException("file too large");
		}
		chunks[chunk.current - 1] = chunk.content;
		received++;
		complete = received == total;
	}

	/** 是否已收齐全部分块。 */
	public synchronized boolean isComplete() {
		return complete;
	}

	/**
	 * 拼接完整文件；未收齐时抛出 {@link IllegalStateException}。
	 *
	 * @return 完整字节
	 */
	public synchronized byte[] assemble() {
		if (!complete) {
			throw new IllegalStateException("chunk set incomplete: " + received + "/" + total);
		}
		byte[] data = new byte[(int) bytes];
		int offset = 0;
		for (byte[] chunk : chunks) {
			if (chunk == null || chunk.length == 0) {
				continue;
			}
			System.arraycopy(chunk, 0, data, offset, chunk.length);
			offset += chunk.length;
		}
		return data;
	}

	/**
	 * 把接收截止时间向后推，只增不减。
	 *
	 * @param target 新的截止时间（毫秒时间戳）
	 */
	public synchronized void touch(long target) {
		if (target > deadline) {
			deadline = target;
		}
	}

	/** 当前接收截止时间。 */
	public long deadline() {
		return deadline;
	}

	/** 在指定时刻是否已超时。 */
	public boolean isExpired(long now) {
		return now >= deadline;
	}

	/** 结果格式。 */
	public String format() {
		return format;
	}

	/** 已收到的块数。 */
	public synchronized int received() {
		return received;
	}

	/** 总分块数。 */
	public int total() {
		return total;
	}
}
