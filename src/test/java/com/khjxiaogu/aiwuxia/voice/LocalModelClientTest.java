package com.khjxiaogu.aiwuxia.voice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.khjxiaogu.aiwuxia.voice.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.voice.protocol.FileChunkAssembler;
import com.khjxiaogu.aiwuxia.voice.protocol.LocalModelProtocol;
import com.khjxiaogu.aiwuxia.voice.transport.LocalModelTransport;
import com.khjxiaogu.aiwuxia.voice.transport.LocalModelTransportFactory;

/**
 * {@link LocalModelClient} 的离线自检：用假传输验证
 * “连接与能力集合 / 请求按类型分发 / 进度上报 / 能力集合变更”这些协议行为，
 * 不建立真实网络连接。
 * <p>
 * 不依赖 JUnit，直接用 {@code java} 运行本类的 {@link #main(String[])}。
 * </p>
 */
public class LocalModelClientTest {

	private static final URI SERVER = URI.create("ws://127.0.0.1:8997/aichat/kh$localModelDeploy");

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 * @throws Exception 建连被中断
	 */
	public static void main(String[] args) throws Exception {
		testDispatch();
		testCapabilities();
		testProgressReporting();
		testFileChunk();
		testOutboxReplay();
		System.out.println("LocalModelClientTest: 全部用例通过");
	}

	/** 连接地址带能力集合；请求按 type 分发，未知类型回错误。 */
	private static void testDispatch() throws Exception {
		FakeFactory factory = new FakeFactory();
		LocalModelClient client = new LocalModelClient(factory, SERVER, Map.of(), false, 1000);
		AtomicReference<String> receivedPrompt = new AtomicReference<>();
		client.register(ModelType.H3, new RequestHandler() {
			@Override
			public LocalModelClient.Response handle(JsonObject request) {
				receivedPrompt.set(request.has("prompt") ? request.get("prompt").getAsString() : "");
				return new LocalModelClient.Response("ok".getBytes(StandardCharsets.UTF_8), "json");
			}
		});
		check(client.connectBlocking(), "假传输应连接成功");
		FakeTransport transport = factory.last;
		check(transport != null, "应当创建传输");
		check(transport.uri.toString().contains("model_type=h3"),
				"连接地址应带初始能力集合，实际：" + transport.uri);

		transport.receive("{\"reqid\":\"r1\",\"type\":\"h3\",\"prompt\":\"测试\"}");
		// 处理器现在跑在独立线程上（不占用 WebSocket 读线程），结果需要等待
		await("处理器应收到参数", () -> "测试".equals(receivedPrompt.get()));
		check("测试".equals(receivedPrompt.get()), "处理器应收到参数，实际：" + receivedPrompt.get());
		await("应当发送二进制文件分块", () -> !transport.binarySent.isEmpty());
		check(transport.sent.isEmpty(), "文件结果不应再发文本 Base64 回复");
		LocalModelProtocol.FileChunk chunk = LocalModelProtocol
				.parseFileChunk(transport.binarySent.get(0));
		check(chunk != null, "应当发送二进制文件分块");
		check("r1".equals(chunk.reqid), "分块应带 reqid，实际：" + chunk.reqid);
		check("json".equals(chunk.format), "分块应带 format，实际：" + chunk.format);
		check(new String(chunk.content, StandardCharsets.UTF_8).equals("ok"),
				"分块内容应为 ok，实际：" + new String(chunk.content, StandardCharsets.UTF_8));

		transport.sent.clear();
		transport.receive("{\"reqid\":\"r2\",\"type\":\"sdxl\",\"path\":\"/x\"}");
		await("未注册类型应回错误", () -> !transport.sent.isEmpty());
		JsonObject error = lastJson(transport.sent);
		check("r2".equals(LocalModelProtocol.reqid(error)), "错误回复应带 reqid");
		check(error.get("error").getAsString().contains("no handler"),
				"未注册类型应返回 no handler，实际：" + error);
		client.closeService();
	}

	/** 能力集合变更：只保留已注册类型，并发送 capabilities 报文；服务器回执被忽略。 */
	private static void testCapabilities() throws Exception {
		FakeFactory factory = new FakeFactory();
		LocalModelClient client = new LocalModelClient(factory, SERVER, Map.of(), false, 1000);
		client.register(ModelType.H3, request -> new LocalModelClient.Response(new byte[] { 1 }, "json"));
		client.register(ModelType.QWEN, request -> new LocalModelClient.Response(new byte[] { 1 }, "json"));
		check(client.connectBlocking(), "假传输应连接成功");
		FakeTransport transport = factory.last;
		transport.sent.clear();

		client.setModelTypes(Set.of(ModelType.H3, ModelType.SVS));
		JsonObject message = lastJson(transport.sent);
		check("capabilities".equals(LocalModelProtocol.typeOf(message)),
				"应发送 capabilities 报文，实际：" + message);
		List<String> types = LocalModelProtocol.modelTypes(message);
		check(types.equals(List.of("h3")), "未注册的 svs 应被忽略，实际：" + types);
		check(client.getModelTypes().equals(Set.of(ModelType.H3)), "本地能力集合应同步为 h3");

		transport.sent.clear();
		transport.receive("{\"type\":\"capabilities\",\"model_types\":[\"h3\"]}");
		check(transport.sent.isEmpty(), "服务器能力集合回执不应触发回复");
		client.closeService();
	}

	/** 处理器可以通过 ProgressReporter 主动上报排队/执行中。 */
	private static void testProgressReporting() throws Exception {
		FakeFactory factory = new FakeFactory();
		LocalModelClient client = new LocalModelClient(factory, SERVER, Map.of(), false, 1000);
		client.register(ModelType.H3, new RequestHandler() {
			@Override
			public LocalModelClient.Response handle(JsonObject request) {
				return new LocalModelClient.Response(new byte[] { 1 }, "json");
			}

			@Override
			public LocalModelClient.Response handle(JsonObject request,
					LocalModelClient.ProgressReporter progress) {
				progress.queued("排队");
				progress.running("执行中");
				progress.progress(0.5, "一半");
				return new LocalModelClient.Response(new byte[] { 1 }, "json");
			}
		});
		check(client.connectBlocking(), "假传输应连接成功");
		FakeTransport transport = factory.last;
		transport.sent.clear();
		transport.receive("{\"reqid\":\"r3\",\"type\":\"h3\",\"prompt\":\"x\"}");
		await("应当上报 3 条进度", () -> countProgress(transport.sent) >= 3);
		List<JsonObject> progress = new ArrayList<>();
		for (String sent : transport.sent) {
			JsonObject message = LocalModelProtocol.parse(sent);
			if (message != null && LocalModelProtocol.isProgress(message)) {
				progress.add(message);
			}
		}
		check(progress.size() == 3, "应上报 3 条进度，实际：" + progress.size());
		check("queued".equals(progress.get(0).get("status").getAsString()), "第一条应为 queued");
		check("running".equals(progress.get(1).get("status").getAsString()), "第二条应为 running");
		check(progress.get(2).has("progress") && Math.abs(progress.get(2).get("progress").getAsDouble() - 0.5) < 1e-6,
				"第三条应带 0.5 进度");
		client.closeService();
	}

	/** 大于 5 MiB 的结果应拆成多个二进制分块，并能被组装器还原。 */
	private static void testFileChunk() throws Exception {
		FakeFactory factory = new FakeFactory();
		LocalModelClient client = new LocalModelClient(factory, SERVER, Map.of(), false, 1000);
		byte[] payload = new byte[LocalModelProtocol.DEFAULT_FILE_CHUNK_SIZE + 10];
		java.util.Arrays.fill(payload, (byte) 7);
		client.register(ModelType.H3, request -> new LocalModelClient.Response(payload, "mp4"));
		check(client.connectBlocking(), "假传输应连接成功");
		FakeTransport transport = factory.last;
		transport.receive("{\"reqid\":\"big\",\"type\":\"h3\",\"prompt\":\"x\"}");
		await("超过 5 MiB 应拆成 2 块", () -> transport.binarySent.size() >= 2);
		check(transport.binarySent.size() == 2,
				"超过 5 MiB 应拆成 2 块，实际：" + transport.binarySent.size());
		LocalModelProtocol.FileChunk first = LocalModelProtocol
				.parseFileChunk(transport.binarySent.get(0));
		LocalModelProtocol.FileChunk second = LocalModelProtocol
				.parseFileChunk(transport.binarySent.get(1));
		check(first != null && second != null, "分块应能解析");
		check(first.current == 1 && first.total == 2, "第一块序号应为 1/2");
		check(second.current == 2 && second.total == 2, "第二块序号应为 2/2");
		check(first.content.length == LocalModelProtocol.DEFAULT_FILE_CHUNK_SIZE,
				"第一块内容应为 5 MiB，实际：" + first.content.length);
		check(second.content.length == 10, "第二块内容应为 10 字节，实际：" + second.content.length);
		check("mp4".equals(first.format), "分块应带 format");
		FileChunkAssembler assembler = FileChunkAssembler.start(first, System.currentTimeMillis() + 1000);
		assembler.accept(second);
		check(assembler.isComplete(), "两块收齐后应完成");
		check(java.util.Arrays.equals(payload, assembler.assemble()), "组装结果应与原文件一致");
		client.closeService();
	}

	/**
	 * 连接断开时结果先进入缓冲；同一客户端重连（onOpen）后再补发到同一地址。
	 * <p>
	 * 覆盖的是“结果不能丢、也不能投给别的接收端”：缓冲属于本客户端，补发只走它自己的连接。
	 * </p>
	 */
	private static void testOutboxReplay() throws Exception {
		FakeFactory factory = new FakeFactory();
		LocalModelClient client = new LocalModelClient(factory, SERVER, Map.of(), false, 1000);
		byte[] payload = "buffered".getBytes(StandardCharsets.UTF_8);
		client.register(ModelType.H3, request -> new LocalModelClient.Response(payload, "json"));
		check(client.connectBlocking(), "假传输应连接成功");
		FakeTransport transport = factory.last;
		transport.sent.clear();
		transport.binarySent.clear();

		// 模拟“请求到达时连接已经断了”：此时结果不能丢，应当进缓冲区
		transport.markClosed();
		transport.receive("{\"reqid\":\"buf\",\"type\":\"h3\",\"prompt\":\"x\"}");
		await("结果应进入缓冲区", () -> client.getBufferedResultCount() == 1);
		check(transport.binarySent.isEmpty(), "断开时不应发出分块");
		check(transport.sent.isEmpty(), "断开时不应发出文本报文");

		// 模拟重连成功：客户端应把缓冲的结果补发到同一个地址
		transport.reopen();
		await("重连后应补发结果", () -> !transport.binarySent.isEmpty());
		LocalModelProtocol.FileChunk chunk = LocalModelProtocol
				.parseFileChunk(transport.binarySent.get(0));
		check(chunk != null, "补发的分块应能解析");
		check("buf".equals(chunk.reqid), "补发分块应保留 reqid，实际：" + chunk.reqid);
		check(java.util.Arrays.equals(payload, chunk.content), "补发内容应与原结果一致");
		check(client.getBufferedResultCount() == 0, "补发后缓冲区应清空");
		client.closeService();
	}

	/** 取最后一条消息的 JSON。 */
	private static JsonObject lastJson(List<String> sent) {
		check(!sent.isEmpty(), "应当发送过报文");
		JsonObject message = LocalModelProtocol.parse(sent.get(sent.size() - 1));
		check(message != null, "最后一条报文应为 JSON：" + sent.get(sent.size() - 1));
		return message;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/** 等待异步处理完成（处理器现在跑在独立线程上）；超时视为失败。 */
	private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(10L);
		}
		throw new AssertionError("等待超时：" + what);
	}

	/** 统计已发送的进度报文条数。 */
	private static int countProgress(List<String> sent) {
		int count = 0;
		for (String message : sent) {
			JsonObject parsed = LocalModelProtocol.parse(message);
			if (parsed != null && LocalModelProtocol.isProgress(parsed)) {
				count++;
			}
		}
		return count;
	}

	/** 只记录报文、不建立真实连接的假传输。 */
	private static final class FakeTransport implements LocalModelTransport {
		private final URI uri;
		/** 处理器在独立线程上运行，这里用线程安全的列表，便于主线程轮询结果。 */
		private final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final List<byte[]> binarySent = new java.util.concurrent.CopyOnWriteArrayList<>();
		private Listener listener;
		private boolean open;

		private FakeTransport(URI uri) {
			this.uri = uri;
		}

		@Override
		public void setListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public boolean connectBlocking() {
			open = true;
			if (listener != null) {
				listener.onOpen();
			}
			return true;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		/** 模拟连接断开（不触发回调），用于验证结果进入缓冲区。 */
		private void markClosed() {
			open = false;
		}

		/** 模拟重连成功：触发 onOpen，客户端应在这里补发缓冲的结果。 */
		private void reopen() {
			open = true;
			if (listener != null) {
				listener.onOpen();
			}
		}

		@Override
		public void send(String message) {
			sent.add(message);
		}

		@Override
		public void sendBinary(byte[] message) {
			binarySent.add(message);
		}

		@Override
		public void close() {
			open = false;
		}

		@Override
		public URI getUri() {
			return uri;
		}

		/** 模拟服务器下发一条报文。 */
		private void receive(String message) {
			if (listener != null) {
				listener.onMessage(message);
			}
		}
	}

	/** 最近一次创建的假传输。 */
	private static final class FakeFactory implements LocalModelTransportFactory {
		private FakeTransport last;

		@Override
		public LocalModelTransport create(URI uri, Map<String, String> headers) {
			last = new FakeTransport(uri);
			return last;
		}
	}
}
