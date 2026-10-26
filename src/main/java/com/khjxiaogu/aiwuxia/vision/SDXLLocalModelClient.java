package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModelClient;
import com.khjxiaogu.aiwuxia.voice.ModelType;

public class SDXLLocalModelClient extends LocalModelClient {
	
	/** SD WebUI（A1111 / Forge）提供的轻量探活接口。 */
	private static final String HEALTH_PATH = "/internal/ping";
	/** 生成图片时 SD WebUI 可能较慢，单次探活给足超时，避免误判。 */
	private static final int HEALTH_TIMEOUT_MS = 10_000;
	
	public SDXLLocalModelClient(URI serverUri,ResourceLock lock, boolean autoReconnect, long reconnectIntervalMs) {
		super(serverUri, Map.of("authorization","Bearer "+System.getProperty("localVoiceToken", "")), d->{
			try(ResourcePermit l=lock.acquire(12))  {
				System.out.println(d.get("data"));
				Gson gs=new Gson();
				return new Response(HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; utf-8")
				.url(d.get("path").getAsString())
				.post()
				.send(gs.fromJson(d.get("data").toString(), String.class))
				.readBytes(),"json");
		
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}, Set.of(ModelType.SDXL), autoReconnect, reconnectIntervalMs,
		sdWebuiHealthCheck(System.getProperty("sdwebuiUrl")),
		DEFAULT_HEALTH_CHECK_INTERVAL_MS, DEFAULT_HEALTH_CHECK_FAILURE_THRESHOLD);
	}

	/**
	 * 根据 {@code sdwebuiUrl} 生成 SD WebUI 的健康检查。
	 * <p>
	 * 未配置 {@code sdwebuiUrl} 时返回 null，即不启用健康检查。
	 * </p>
	 *
	 * @param sdwebuiUrl SD WebUI 地址（可带 http:// / https:// 前缀）
	 * @return 健康检查，未配置时返回 null
	 */
	private static HealthCheck sdWebuiHealthCheck(String sdwebuiUrl) {
		if (sdwebuiUrl == null || sdwebuiUrl.trim().isEmpty())
			return null;
		String base = sdwebuiUrl.trim();
		if (!base.startsWith("http://") && !base.startsWith("https://"))
			base = "http://" + base;
		while (base.endsWith("/"))
			base = base.substring(0, base.length() - 1);
		return httpHealthCheck(base + HEALTH_PATH, HEALTH_TIMEOUT_MS);
	}

}
