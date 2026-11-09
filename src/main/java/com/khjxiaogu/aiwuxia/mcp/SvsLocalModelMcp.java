package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.LocalModelHandshaker;
import com.khjxiaogu.aiwuxia.voice.LocalSVSUsage;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;

/**
 * 使用 {@link LocalModel} WebSocket 通道的 SVS MCP。
 * <p>
 * 与直接调用 localemp HTTP 的 {@link SvsMcp} 不同：本工具通过 {@code LocalModel.requireSvs}
 * 把请求发给已接入的 {@code model_type=svs} 本地模型客户端（见
 * {@link com.khjxiaogu.aiwuxia.voice.SvsLocalModelClient}），结果由本地模型回传。
 * 角色名 → 音频 ID 仍读取 {@code config/svs-voices.json}，每次调用重新读取。
 * </p>
 * <p>
 * 工具名与 {@link SvsMcp} 保持一致，便于前端/提示词复用；但两者不要注册进同一个注册表。
 * </p>
 */
public class SvsLocalModelMcp implements ToolProvider {

	/** 工具名：把音频链接转换为指定角色音色。 */
	public static final String TOOL_CONVERT = "svs_convert";
	/** 工具名：列出可用音色角色。 */
	public static final String TOOL_LIST_VOICES = "list_svs_voices";
	/** 默认变调。 */
	public static final int DEFAULT_PITCH = 0;
	/** 默认中值滤波半径。 */
	public static final int DEFAULT_FILTER_RADIUS = 0;
	/** 默认防撕裂保护。 */
	public static final double DEFAULT_PROTECT = 0d;
	/** 默认启用伴奏分离。 */
	public static final boolean DEFAULT_DO_SPLIT = true;

	private final ObjectStorageProvider tos;
	private final File configFile;

	/**
	 * @param tos        结果音频存储，可为 null（此时返回 data URL）
	 * @param configFile 角色名 → 音频 ID 配置文件
	 */
	public SvsLocalModelMcp(ObjectStorageProvider tos, File configFile) {
		this.tos = tos;
		this.configFile = configFile;
	}

	@Override
	public Collection<ToolData> getTools() {
		List<ToolData> tools = new ArrayList<>(2);
		tools.add(createListTool());
		tools.add(createConvertTool());
		return tools;
	}

	private ToolData createListTool() {
		return new ToolData.Builder(TOOL_LIST_VOICES,
				"列出 SVS 当前可用的音色角色（角色名 → 音频 ID）。调用 svs_convert 前用它确认 voice 参数。")
				.tool((data) -> {
					Map<String, String> voices = SvsMcp.loadVoices(configFile);
					if (voices.isEmpty()) {
						return "当前没有配置可用的 SVS 音色角色，请在 config/svs-voices.json 中配置。";
					}
					StringBuilder text = new StringBuilder("可用音色角色：\n");
					for (Map.Entry<String, String> entry : voices.entrySet()) {
						text.append("- ").append(entry.getKey()).append("（").append(entry.getValue()).append("）\n");
					}
					return text.toString();
				}).build();
	}

	private ToolData createConvertTool() {
		return new ToolData.Builder(TOOL_CONVERT,
				"通过已接入的本地 SVS 模型（LocalModel）把任意音频链接转换为指定角色音色的歌声。"
						+ "与音频平台无关：audio_url 传 http/https 音频地址，voice 传 " + TOOL_LIST_VOICES
						+ " 中的角色名；结果音频作为附件返回。")
				.putParam("audio_url", "要转换的音频链接，必须是 http/https，支持任意音频平台。", "音频链接")
				.putParam("voice", "角色名，必须是 " + TOOL_LIST_VOICES + " 中的一项；由配置映射到音频 ID。", "角色名")
				.putParam("pitch", "变调半音，-24~24，默认 0。正数升高、负数降低。", "变调")
				.putParam("filter_radius", "中值滤波半径，0~7，默认 0；0~2 不滤波，3~7 启用滤波。", "滤波半径")
				.putParam("protect", "防撕裂保护，0~1，默认 0；0.33 为常用折中值，>=0.5 相当于关闭保护。", "保护强度")
				.putParam("do_split", "是否执行伴奏分离与合成，默认 true；false 时直接转换整段音频、不混伴奏，额度按 1/3 计。", "伴奏分离")
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
						Map<String, String> voices = SvsMcp.loadVoices(configFile);
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

	private CompletableFuture<ToolResult> convert(String audioUrl, String audioId, int pitch,
			int filterRadius, double protect, boolean doSplit, AIOutput output) {
		String reqid = UUID.randomUUID().toString();
		LocalModelHandshaker.SvsOptions options;
		try {
			options = LocalModelHandshaker.SvsOptions.builder()
					.voice(audioId)
					// 同时带旧字段，兼容仍只读 timbre / filter / strength 的本地实现
					.timbre(audioId)
					.pitch(pitch)
					.filter(filterRadius)
					.filterRadius(filterRadius)
					.strength(protect)
					.protect(protect)
					.doSplit(doSplit);
		} catch (IllegalArgumentException ex) {
			return CompletableFuture.completedFuture(ToolResult.text("参数错误：" + ex.getMessage()));
		}
		// 不传 name：本地服务必须使用临时文件
		return LocalModel.requireSvs(reqid, audioUrl, null, options)
				.thenApply(result -> uploadResult(result, doSplit, output))
				.exceptionally(ex -> ToolResult.failure("SVS 生成失败：" + rootMessage(ex)));
	}

	/** 上传结果音频并构造附件结果。 */
	private ToolResult uploadResult(ModelGenerationResult result, boolean doSplit, AIOutput output) {
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			return ToolResult.failure("SVS 生成失败：本地模型没有返回音频数据。");
		}
		if (output != null) {
			output.addUsage(new LocalSVSUsage(result.bodyData.length, doSplit));
		}
		String audioId;
		try {
			audioId = tos == null ? FileUtil.toDataUrl(result.bodyData)
					: tos.uploadIfNotExists(result.bodyData, output == null ? null : output::addUsage);
		} catch (IOException ex) {
			ex.printStackTrace();
			return ToolResult.failure("SVS 生成失败：音频保存失败，" + rootMessage(ex));
		}
		String displayName = "SVS 歌声";
		return ToolResult.of("SVS 生成成功，音频id为：" + audioId + "。",
				new AudioContent(audioId, displayName));
	}

	private static boolean isHttpUrl(String value) {
		return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
				|| value.regionMatches(true, 0, "https://", 0, 8));
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

