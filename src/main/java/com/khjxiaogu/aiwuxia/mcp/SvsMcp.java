package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.LocalSVSUsage;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;

/**
 * SVS（歌声转换 / RVC）MCP。
 * <p>
 * 从 {@link MusicMcp} 的 SVS 调用流程复制而来，但输入改为<b>音频链接</b>，与音乐平台无关：
 * 直接把任意 http/https 音频地址交给本地 localemp.py 服务，由它下载、变声并回调结果。
 * 原 {@link MusicMcp} 保持不变。
 * </p>
 * <p>
 * <b>无默认音色、无请求状态</b>：每次调用都由模型显式传入角色名，按
 * {@code config/svs-voices.json}（角色名 → 音频 ID）解析出 RVC 模型名。
 * 配置文件每次调用时重新读取，修改后无需重启。工具 {@value #TOOL_LIST_VOICES} 可列出全部可用角色。
 * </p>
 * <p>
 * 请求参数按 {@code localemp_api.md}：
 * {@code url}、{@code voice}、{@code reqid}、{@code name}（可选）、
 * {@code pitch}（-24~24）、{@code filter_radius}（0~7）、{@code protect}（0~1）、
 * {@code do_split}（是否伴奏分离，默认 true）。
 * 服务地址取 {@code -DsvsHost=host:port}；未配置时不提供转换工具，但仍可列出角色。
 * </p>
 */
public class SvsMcp implements ToolProvider {

	/** 工具名：把音频链接转换为指定角色音色。 */
	public static final String TOOL_CONVERT = "svs_convert";
	/** 工具名：列出可用音色角色。刻意与 {@code list_voice_speakers}、{@code list_characters} 区分。 */
	public static final String TOOL_LIST_VOICES = "list_svs_voices";
	/** 默认变调。 */
	public static final int DEFAULT_PITCH = 0;
	/** 默认中值滤波半径。 */
	public static final int DEFAULT_FILTER_RADIUS = 0;
	/** 默认防撕裂保护。 */
	public static final double DEFAULT_PROTECT = 0d;
	/** 默认启用伴奏分离。 */
	public static final boolean DEFAULT_DO_SPLIT = true;
	/** 结果音频存储。 */
	private final ObjectStorageProvider tos;
	/** 角色名 → 音频 ID 的配置文件。 */
	private final File configFile;
	/** 本地 SVS 服务地址，可为空。 */
	private final String svsHost;
	/** 与其它本地模型共享的资源锁。 */
	private final ResourceLock lock;

	/**
	 * @param tos        结果音频存储，可为 null（此时返回 data URL）
	 * @param lock       资源锁，null 时新建一个容量 4 的锁
	 * @param configFile 角色名 → 音频 ID 配置文件
	 */
	public SvsMcp(ObjectStorageProvider tos, ResourceLock lock, File configFile) {
		this(tos, lock, configFile, System.getProperty("svsHost"));
	}

	/**
	 * @param tos        结果音频存储，可为 null
	 * @param lock       资源锁
	 * @param configFile 角色名 → 音频 ID 配置文件
	 * @param svsHost    本地 SVS 服务地址，例如 {@code 127.0.0.1:8064}
	 */
	public SvsMcp(ObjectStorageProvider tos, ResourceLock lock, File configFile, String svsHost) {
		this.tos = tos;
		this.configFile = configFile;
		this.svsHost = svsHost == null ? "" : svsHost.trim();
		this.lock = lock == null ? new ResourceLock(4) : lock;
	}

	/** SVS 转换服务是否已配置。 */
	public boolean isConfigured() {
		return !svsHost.isEmpty();
	}

	@Override
	public Collection<ToolData> getTools() {
		List<ToolData> tools = new ArrayList<>(2);
		tools.add(createListTool());
		if (isConfigured()) {
			tools.add(createConvertTool());
		}
		return tools;
	}

	/** 列出可用音色角色。 */
	private ToolData createListTool() {
		return new ToolData.Builder(TOOL_LIST_VOICES,
				"列出 SVS 当前可用的音色角色（角色名 → 音频 ID）。调用 svs_convert 前用它确认 voice 参数。")
				.tool((data) -> {
					Map<String, String> voices = loadVoices();
					if (voices.isEmpty()) {
						return "当前没有配置可用的 SVS 音色角色，请在 config/svs-voices.json 中配置。";
					}
					StringBuilder text = new StringBuilder("可用音色角色：\n");
					for (Map.Entry<String, String> entry : voices.entrySet()) {
						text.append("- ").append(entry.getKey()).append("\n");
					}
					return text.toString();
				}).build();
	}

	private ToolData createConvertTool() {
		return new ToolData.Builder(TOOL_CONVERT,
				"把任意音频链接转换为指定角色音色的歌声（SVS/RVC）。传入可访问的 http/https 音频地址，"
						+ "voice 传角色名（可用 " + TOOL_LIST_VOICES + " 查询），服务会下载音频、变声并把结果音频作为附件返回。")
				.putParam("audio_url", "要转换的音频链接，必须是 http/https，支持任意音频平台。", "音频链接")
				.putParam("voice", "角色名，必须是 " + TOOL_LIST_VOICES + " 中的一项；由配置映射到音频 ID。", "角色名")
				.putParam("pitch", "变调半音，-24~24，默认 0。正数升高、负数降低。", "变调")
				.putParam("filter_radius", "中值滤波半径，0~7，默认 0；0~2 不滤波，3~7 启用滤波。", "滤波半径")
				.putParam("protect", "防撕裂保护，0~1，默认 0，>=0.5 相当于关闭保护。", "保护强度")
				.putParam("do_split", "是否执行伴奏分离与合成，默认 true；false 时直接转换整段音频、不混伴奏，输出不含伴奏，额度按 1/3 计。", "伴奏分离")
				.async()
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String audioUrl = stringField(jo, "audio_url", "");
						if (!isHttpUrl(audioUrl)) {
							return CompletableFuture.completedFuture(
									ToolResult.text("参数错误：audio_url 必须是 http/https 音频链接。"));
						}
						String role = stringField(jo, "voice", "");
						if (role.isEmpty()) {
							return CompletableFuture.completedFuture(
									ToolResult.text("参数错误：缺少角色名 voice，可用 " + TOOL_LIST_VOICES + " 查询。"));
						}
						Map<String, String> voices = loadVoices();
						String audioId = voices.get(role);
						if (audioId == null || audioId.isEmpty()) {
							return CompletableFuture.completedFuture(ToolResult.text(
									"参数错误：未知角色「" + role + "」。可用角色："
											+ String.join("、", voices.keySet())));
						}
						int pitch = intField(jo, "pitch", DEFAULT_PITCH, -24, 24);
						int filterRadius = intField(jo, "filter_radius", DEFAULT_FILTER_RADIUS, 0, 7);
						double protect = doubleField(jo, "protect", DEFAULT_PROTECT, 0d, 1d);
						boolean doSplit = boolField(jo, "do_split", DEFAULT_DO_SPLIT);
						return convert(audioUrl, audioId, pitch, filterRadius, protect, doSplit, output);
					} catch (IllegalArgumentException ex) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：" + ex.getMessage()));
					} catch (Throwable ex) {
						return CompletableFuture.completedFuture(
								ToolResult.failure("SVS 生成失败：" + rootMessage(ex)));
					}
				}).build();
	}

	/** 读取角色名 → 音频 ID 配置；文件不存在或格式错误时返回空表。 */
	public Map<String, String> loadVoices() {
		return loadVoices(configFile);
	}

	/** 读取角色名 → 音频 ID 配置；文件不存在或格式错误时返回空表。 */
	public static Map<String, String> loadVoices(File configFile) {
		Map<String, String> voices = new LinkedHashMap<>();
		if (configFile == null || !configFile.exists()) {
			return voices;
		}
		try {
			JsonObject config = JsonParser.parseString(FileUtil.readString(configFile)).getAsJsonObject();
			for (String role : config.keySet()) {
				if (config.get(role).isJsonPrimitive()) {
					String audioId = config.get(role).getAsString().trim();
					if (!audioId.isEmpty()) {
						voices.put(role, audioId);
					}
				}
			}
		} catch (Throwable ex) {
			System.out.println("[SVS] 读取音色角色配置失败：" + ex.getMessage());
		}
		return voices;
	}

	/** 调用 localemp.py 并等待回调结果；始终使用临时文件，不传 name。 */
	private CompletableFuture<ToolResult> convert(String audioUrl, String audioId, int pitch,
			int filterRadius, double protect, boolean doSplit, com.khjxiaogu.aiwuxia.llm.AIOutput output) {
		if (!isSvsServiceOnline(svsHost)) {
			return CompletableFuture
					.completedFuture(ToolResult.failure("本地歌声合成服务不在线，请先启动 localemp.py。"));
		}
		String reqid = UUID.randomUUID().toString();
		CompletableFuture<ModelGenerationResult> callback = LocalModel.registerTask(reqid);
		CompletableFuture.runAsync(() -> {
			try (ResourcePermit permit = lock.acquire(4)) {
				StringBuilder url = new StringBuilder("?url=").append(enc(audioUrl))
						.append("&voice=").append(enc(audioId))
						.append("&reqid=").append(enc(reqid))
						.append("&pitch=").append(pitch)
						.append("&filter_radius=").append(filterRadius)
						.append("&protect=").append(protect)
						.append("&do_split=").append(doSplit);
				String response = HttpRequestBuilder.create("http", svsHost).url(url.toString())
						.get().readString();
				System.out.println("[SVS] " + response);
			} catch (Throwable ex) {
				// HTTP 失败时服务不会回调，主动让等待方失败，避免工具永久挂起
				callback.completeExceptionally(ex);
			}
		});
		return callback.thenApply(result -> {
			if (result == null || result.bodyData == null || result.bodyData.length == 0) {
				return ToolResult.failure("SVS 生成失败：服务没有返回音频数据。");
			}
			if (output != null) {
				output.addUsage(new LocalSVSUsage(result.bodyData.length, doSplit));
			}
			String resultId;
			try {
				resultId = tos == null ? FileUtil.toDataUrl(result.bodyData)
						: tos.uploadIfNotExists(result.bodyData, output == null ? null : output::addUsage);
			} catch (IOException ex) {
				ex.printStackTrace();
				return ToolResult.failure("SVS 生成失败：音频保存失败，" + rootMessage(ex));
			}
			String displayName = "SVS 歌声";
			return ToolResult.of("SVS 生成成功，音频id为：" + resultId + "。",
					new AudioContent(resultId, displayName));
		});
	}

	private static boolean isHttpUrl(String value) {
		return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
				|| value.regionMatches(true, 0, "https://", 0, 8));
	}

	private static String enc(String value) throws IOException {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
	}

	/** 快速探测 localemp.py 是否在线，避免离线时长时间等待 HTTP 超时。 */
	public static boolean isSvsServiceOnline(String host) {
		if (host == null || host.trim().isEmpty()) {
			return false;
		}
		String value = host.trim();
		if (value.startsWith("http://")) {
			value = value.substring(7);
		} else if (value.startsWith("https://")) {
			value = value.substring(8);
		}
		int slash = value.indexOf('/');
		if (slash >= 0) {
			value = value.substring(0, slash);
		}
		String hostName = value;
		int port = 8064;
		int colon = value.lastIndexOf(':');
		if (colon > 0) {
			hostName = value.substring(0, colon);
			try {
				port = Integer.parseInt(value.substring(colon + 1));
			} catch (NumberFormatException ex) {
				return false;
			}
		}
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(hostName, port), 1000);
			return true;
		} catch (Throwable ex) {
			return false;
		}
	}

	private static String stringField(JsonObject json, String key, String fallback) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return fallback;
		}
		String value = json.get(key).getAsString();
		return value == null ? fallback : value.trim();
	}

	private static int intField(JsonObject json, String key, int fallback, int min, int max) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return fallback;
		}
		String raw = json.get(key).getAsString().trim();
		if (raw.isEmpty()) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(raw);
			if (value < min || value > max) {
				throw new IllegalArgumentException(key + " 必须在 " + min + "~" + max + " 之间");
			}
			return value;
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(key + " 必须是整数");
		}
	}

	private static double doubleField(JsonObject json, String key, double fallback, double min, double max) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return fallback;
		}
		String raw = json.get(key).getAsString().trim();
		if (raw.isEmpty()) {
			return fallback;
		}
		try {
			double value = Double.parseDouble(raw);
			if (value < min || value > max) {
				throw new IllegalArgumentException(key + " 必须在 " + min + "~" + max + " 之间");
			}
			return value;
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(key + " 必须是数字");
		}
	}

	private static boolean boolField(JsonObject json, String key, boolean fallback) {
		String raw = stringField(json, key, "").toLowerCase();
		if (raw.isEmpty()) {
			return fallback;
		}
		switch (raw) {
		case "true":
		case "1":
		case "yes":
		case "on":
			return true;
		case "false":
		case "0":
		case "no":
		case "off":
			return false;
		default:
			throw new IllegalArgumentException(key + " 必须是 true/false、1/0、yes/no 或 on/off");
		}
	}

	private static String rootMessage(Throwable ex) {
		Throwable cause = ex;
		while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		if (cause == null) {
			return "未知错误";
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}

