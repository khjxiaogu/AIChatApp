package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.modelproxy.api.SvsOptions;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.voice.LocalSVSUsage;

/**
 * SVS（歌声转换 / RVC）MCP，统一走本地模型通道。
 * <p>
 * 输入任意 http/https 音频链接，通过 {@link LocalModel#requireSvs} 把请求交给已接入的
 * {@code model_type=svs} 本地模型客户端（见 {@code LocalModelConnectionService}），
 * 由模型侧的 {@code vision.SvsLocalModelHandler} 调用 localemp.py 完成变声，再把结果音频回传。
 * 本类不再直接访问 localemp（真正的 localemp HTTP 调用已抽到 {@code vision.SvsApi}），
 * 也不持有资源锁。
 * </p>
 * <p>
 * <b>无默认音色、无请求状态</b>：每次调用都由模型显式传入角色名，按
 * {@code config/svs-voices.json}（角色名 → 音频 ID）解析出 RVC 模型名。
 * 配置文件每次调用时重新读取，修改后无需重启。工具 {@value #TOOL_LIST_VOICES} 可列出全部可用角色。
 * </p>
 * <p>
 * {@code svs_convert} 参数：{@code audio_url}、{@code voice}、{@code pitch}（-24~24）、
 * {@code filter_radius}（0~7）、{@code protect}（0~1）、{@code do_split}（默认 true）。
 * 固定使用临时文件，不提供文件名参数。工具声明为异步，MCP 层会立即返回 {@code task_id}，
 * 再用通用 {@code mcp_task_result} 轮询获取音频。
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

	/**
	 * @param tos        结果音频存储，可为 null（此时返回 data URL）
	 * @param configFile 角色名 → 音频 ID 配置文件
	 */
	public SvsMcp(ObjectStorageProvider tos, File configFile) {
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
						String audioUrl = JsonConfig.getNonBlankString(jo, "audio_url", "");
						if (!isHttpUrl(audioUrl)) {
							return CompletableFuture.completedFuture(
									ToolResult.text("参数错误：audio_url 必须是 http/https 音频链接。"));
						}
						String role = JsonConfig.getNonBlankString(jo, "voice", "");
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
						boolean doSplit = JsonConfig.getBool(jo, "do_split", DEFAULT_DO_SPLIT);
						if (!LocalModel.hasOnlineService(ModelType.SVS)) {
							return CompletableFuture.completedFuture(ToolResult.failure(
									"本地模型服务未连接：请先启动并连接 LocalModelConnectionService（svs 模型）。"));
						}
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

	/** 通过本地模型通道转换音频，并把结果音频上传到对象存储。 */
	private CompletableFuture<ToolResult> convert(String audioUrl, String audioId, int pitch,
			int filterRadius, double protect, boolean doSplit, AIOutput output) {
		SvsOptions options;
		try {
			options = SvsOptions.builder()
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
		String reqid = UUID.randomUUID().toString();
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

	private static int intField(JsonObject json, String key, int fallback, int min, int max) {
		String raw = JsonConfig.getNonBlankString(json, key);
		if (raw == null) {
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
		String raw = JsonConfig.getNonBlankString(json, key);
		if (raw == null) {
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
