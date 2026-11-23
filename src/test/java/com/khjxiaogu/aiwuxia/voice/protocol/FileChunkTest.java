package com.khjxiaogu.aiwuxia.voice.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.khjxiaogu.aiwuxia.modelproxy.protocol.FileChunkAssembler;
import com.khjxiaogu.aiwuxia.modelproxy.protocol.LocalModelProtocol;
import com.khjxiaogu.aiwuxia.modelproxy.protocol.LocalModelProtocol.FileChunk;

/**
 * 二进制分块文件协议的自检：
 * 变长 reqid/format 编码、分包、重组、重复块、错配与超时语义。
 * <p>
 * 不依赖 JUnit，直接用 {@code java} 运行本类的 {@link #main(String[])}。
 * </p>
 */
public class FileChunkTest {

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 */
	public static void main(String[] args) {
		testRoundTrip();
		testChunkingAndAssemble();
		testDuplicateAndMismatch();
		testTimeout();
		testInvalidFrames();
		System.out.println("FileChunkTest: 全部用例通过");
	}

	/** 变长 reqid（含中文）与变长 format 的编解码。 */
	private static void testRoundTrip() {
		byte[] data = "hello 视频".getBytes(StandardCharsets.UTF_8);
		List<byte[]> frames = LocalModelProtocol.encodeFileChunks("请求-1234567890", "mp4",
				data, LocalModelProtocol.DEFAULT_FILE_CHUNK_SIZE);
		check(frames.size() == 1, "小文件应为 1 块，实际：" + frames.size());
		FileChunk chunk = LocalModelProtocol.parseFileChunk(frames.get(0));
		check(chunk != null, "分块应能解析");
		check("请求-1234567890".equals(chunk.reqid), "reqid 应一致，实际：" + chunk.reqid);
		check("mp4".equals(chunk.format), "format 应一致");
		check(chunk.current == 1 && chunk.total == 1, "序号应为 1/1");
		check(new String(chunk.content, StandardCharsets.UTF_8).equals("hello 视频"), "内容应一致");
	}

	/** 按小块切分、逐块接收并重组。 */
	private static void testChunkingAndAssemble() {
		byte[] data = new byte[11];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) i;
		}
		List<byte[]> frames = LocalModelProtocol.encodeFileChunks("r", "bin", data, 4);
		check(frames.size() == 3, "11 字节按 4 字节切分应为 3 块，实际：" + frames.size());
		FileChunkAssembler assembler = null;
		for (int i = 0; i < frames.size(); i++) {
			FileChunk chunk = LocalModelProtocol.parseFileChunk(frames.get(i));
			check(chunk != null, "第 " + (i + 1) + " 块应能解析");
			check(chunk.current == i + 1 && chunk.total == 3, "序号应为 " + (i + 1) + "/3");
			if (assembler == null) {
				assembler = FileChunkAssembler.start(chunk, System.currentTimeMillis() + 60_000);
			} else {
				assembler.accept(chunk);
			}
			check(assembler.received() == i + 1, "已收块数应为 " + (i + 1));
			check(assembler.isComplete() == (i == frames.size() - 1),
					"只有最后一块收齐后才算完成");
		}
		check(assembler != null && java.util.Arrays.equals(data, assembler.assemble()),
				"重组结果应与原文件一致");
	}

	/** 重复块幂等；format/total 不一致要报错。 */
	private static void testDuplicateAndMismatch() {
		byte[] data = new byte[8];
		List<byte[]> frames = LocalModelProtocol.encodeFileChunks("dup", "mp4", data, 4);
		FileChunk first = LocalModelProtocol.parseFileChunk(frames.get(0));
		FileChunk second = LocalModelProtocol.parseFileChunk(frames.get(1));
		FileChunkAssembler assembler = FileChunkAssembler.start(first, System.currentTimeMillis() + 60_000);
		assembler.accept(second);
		check(assembler.isComplete(), "两块后应完成");
		assembler.accept(first);
		check(assembler.received() == 2, "重复块不应重复计数，实际：" + assembler.received());

		FileChunkAssembler other = FileChunkAssembler.start(
				LocalModelProtocol.parseFileChunk(
						LocalModelProtocol.encodeFileChunks("dup", "mp4", data, 4).get(0)),
				System.currentTimeMillis() + 60_000);
		try {
			other.accept(new FileChunk("dup", "png", 2, 2, new byte[] { 1 }));
			check(false, "format 不一致应报错");
		} catch (IllegalArgumentException expected) {
			// 预期
		}
		try {
			other.accept(new FileChunk("dup", "mp4", 2, 3, new byte[] { 1 }));
			check(false, "total 不一致应报错");
		} catch (IllegalArgumentException expected) {
			// 预期
		}
	}

	/** 截止时间只增不减，过期后可判定失败。 */
	private static void testTimeout() {
		FileChunk chunk = LocalModelProtocol.parseFileChunk(
				LocalModelProtocol.encodeFileChunks("t", "mp4", new byte[8], 4).get(0));
		long now = System.currentTimeMillis();
		FileChunkAssembler assembler = FileChunkAssembler.start(chunk, now + 1000);
		check(!assembler.isExpired(now), "刚创建不应过期");
		check(assembler.isExpired(now + 1001), "超过截止时间应判定过期");
		assembler.touch(now + 5000);
		check(assembler.deadline() == now + 5000, "截止时间应被推后");
		assembler.touch(now + 3000);
		check(assembler.deadline() == now + 5000, "截止时间不应被提前");
	}

	/** 魔数错误、截断、超出单块上限的帧都应被拒绝。 */
	private static void testInvalidFrames() {
		check(LocalModelProtocol.parseFileChunk(new byte[] { 1, 2, 3 }) == null, "过短的帧应拒绝");
		byte[] frame = LocalModelProtocol.encodeFileChunks("r", "mp4", new byte[4], 4).get(0);
		frame[0] = 'X';
		check(LocalModelProtocol.parseFileChunk(frame) == null, "魔数错误应拒绝");
		byte[] truncated = java.util.Arrays.copyOf(
				LocalModelProtocol.encodeFileChunks("r", "mp4", new byte[4], 4).get(0), 8);
		check(LocalModelProtocol.parseFileChunk(truncated) == null, "截断帧应拒绝");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
