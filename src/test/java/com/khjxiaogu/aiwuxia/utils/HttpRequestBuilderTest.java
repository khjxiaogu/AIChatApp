package com.khjxiaogu.aiwuxia.utils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link HttpRequestBuilder} 固定长度 POST 的自检：服务器应收到 Content-Length 和完整 body。
 */
public class HttpRequestBuilderTest {

	public static void main(String[] args) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicReference<String> length = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		server.createContext("/echo", exchange -> {
			length.set(exchange.getRequestHeaders().getFirst("Content-Length"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, resp.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(resp);
			}
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			String text = "{\"prompt\":\"带图片的请求体\",\"n\":12345}";
			String response = new HttpRequestBuilder("http://127.0.0.1:" + port).url("/echo")
					.header("Content-Type", "application/json").post().send(text).readString();
			check(response.contains("\"ok\":true"), "应返回服务器响应，实际：" + response);
			check(text.equals(body.get()), "服务器应收到完整 body，实际：" + body.get());
			check(length.get() != null && Integer.parseInt(length.get()) == text.getBytes(StandardCharsets.UTF_8).length,
					"应带正确的 Content-Length，实际：" + length.get());
			System.out.println("HttpRequestBuilderTest: 全部用例通过");
		} finally {
			server.stop(0);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
