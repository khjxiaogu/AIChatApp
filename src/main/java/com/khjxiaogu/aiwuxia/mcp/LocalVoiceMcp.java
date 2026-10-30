package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;

/**
 * 本地语音合成的 MCP 工具。
 * <p>
 * 只依赖本地语音模型与本地存储，暴露两个工具：
 * </p>
 * <ul>
 *   <li>{@code list_voice_speakers}：列出本地语音配置中的全部可用说话人；</li>
 *   <li>{@code generate_voice}：输入角色名称与话语内容，先经过 {@code VoiceTagger}
 *       分句与情感标注，再调用本地语音模型合成，最后把音频写入 MCP 统一本地存储并返回音频 id。</li>
 * </ul>
 * <p>
 * 音频 id 由音频内容哈希 + 扩展名组成，生成结果以 {@link AudioContent} 放进工具结果内容；
 * MCP 服务端会把其中的 id 统一转换成 {@code /aichat/mcp/media?file_id=...} 访问入口。
 * </p>
 */
public final class LocalVoiceMcp implements ToolProvider {

	/** 工具名：列出可用说话人。 */
	public static final String TOOL_LIST_SPEAKERS = "list_voice_speakers";
	/** 工具名：生成语音。 */
	public static final String TOOL_GENERATE_VOICE = "generate_voice";

	/** 音频存储。 */
	private final ObjectStorageProvider storage;

	/**
	 * 创建本地语音 MCP 模块。
	 *
	 * @param storage MCP 统一本地存储（通常是 {@code save/mcp_files}），可为 null
	 */
	public LocalVoiceMcp(ObjectStorageProvider storage) {
		this.storage = storage;
	}

	@Override
	public Collection<ToolData> getTools() {
		return create(storage);
	}

	/**
	 * 创建本地语音 MCP 工具列表。
	 *
	 * @param storage MCP 统一本地存储（通常是 {@code save/mcp_files}），可为 null
	 * @return 工具列表
	 */
	public static List<ToolData> create(ObjectStorageProvider storage) {
		List<ToolData> tools = new ArrayList<>();
		tools.add(listSpeakers());
		tools.add(generateVoice(storage));
		return tools;
	}

	/**
	 * 获取所有可用说话人的工具。
	 *
	 * @return 工具定义
	 */
	public static ToolData listSpeakers() {
		return new ToolData.Builder(TOOL_LIST_SPEAKERS,
				"获取本地语音合成所有可用说话人（角色名称）列表。调用 generate_voice 时 role 参数必须从中选择。")
				.presenter(ToolCallPresenter.ofBrief(call -> "获取语音说话人"))
				.tool((data, output, manager) -> {
					List<String> speakers = VoiceModelHandler.listLocalSpeakers();
					if (speakers.isEmpty()) {
						return CompletableFuture.completedFuture(ToolResult.text("本地语音当前没有可用的说话人。"));
					}
					return CompletableFuture.completedFuture(
							ToolResult.text("可用说话人：\n" + String.join("\n", speakers)));
				}).build();
	}

	/**
	 * 生成语音的工具：输入角色名称与话语内容，经过 VoiceTagger 后交给本地语音模型合成，
	 * 并把音频保存到传入的本地存储。
	 *
	 * @param storage 音频存储，可为 null（此时只生成不保存，直接返回错误）
	 * @return 工具定义
	 */
	public static ToolData generateVoice(ObjectStorageProvider storage) {
		return new ToolData.Builder(TOOL_GENERATE_VOICE,
				"使用本地语音模型生成角色语音。只需提供角色名称和话语内容；系统会先用 VoiceTagger 分句与情感标注，再合成并保存到 MCP 统一本地存储，返回音频 id。")
				.putParam("role", "角色名称，必须是 list_voice_speakers 返回的可用说话人之一", "角色名称")
				.putParam("text", "要朗读的话语内容", "话语内容")
				.presenter(ToolCallPresenter.ofArgument("生成语音", "text"))
				.tool((data, output, manager) -> {
					String role;
					String text;
					try {
						JsonObject json = JsonParser.parseString(data).getAsJsonObject();
						role = stringField(json, "role");
						text = stringField(json, "text");
					} catch (Throwable ex) {
						return CompletableFuture.completedFuture(ToolResult.text("参数格式错误：需要 JSON 对象。"));
					}
					if (role.isEmpty()) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少角色名称 role。"));
					}
					if (text.isEmpty()) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少话语内容 text。"));
					}
					if (storage == null) {
						return CompletableFuture.completedFuture(ToolResult.text("本地语音存储不可用，无法保存生成的音频。"));
					}
					Consumer<UsageIntf<?>> usage = output == null ? null : u -> output.addUsage(u);
					try {
						return VoiceModelHandler
								.generateLocalAudio(role, text, UUID.randomUUID().toString(), usage)
								.thenApply(result -> saveAndBuildResult(storage, result, usage))
								.exceptionally(ex -> ToolResult.text("语音生成失败：" + rootMessage(ex)));
					} catch (Throwable ex) {
						return CompletableFuture.completedFuture(ToolResult.text("语音生成失败：" + rootMessage(ex)));
					}
				}).build();
	}

	/** 保存音频并构造工具结果。 */
	private static ToolResult saveAndBuildResult(ObjectStorageProvider storage, ModelGenerationResult result,
			Consumer<UsageIntf<?>> usage) {
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			return ToolResult.text("语音生成失败：本地语音模型没有返回音频数据。");
		}
		String format = normalizeFormat(result.format);
		String audioId = audioId(result.bodyData, format);
		try {
			if (!storage.exists(audioId, usage)) {
				storage.upload(audioId, result.bodyData, usage);
			}
		} catch (IOException ex) {
			ex.printStackTrace();
			return ToolResult.text("语音保存失败：" + rootMessage(ex));
		}
		MutableMessageContents content = new MutableMessageContents();
		content.append("语音生成成功。");
		content.addRaw(new AudioContent(audioId));
		return ToolResult.content(content);
	}

	/** 生成音频 id：内容哈希 + 音频长度 + 扩展名，与图片 id 一样可直接作为存储文件名。 */
	private static String audioId(byte[] data, String format) {
		try {
			MessageDigest digest = ObjectStorageProvider.getSha256Digest();
			String hash = ObjectStorageProvider.bytesToHex(digest.digest(data))
					+ ObjectStorageProvider.bytesToHex(ByteBuffer.allocate(4).putInt(data.length).array());
			return hash + "." + format;
		} catch (NoSuchAlgorithmException ex) {
			// 所有 JVM 都必须支持 SHA-256
			throw new IllegalStateException("JVM 不支持 SHA-256", ex);
		}
	}

	/** 把模型返回的格式名归一化成常用音频扩展名。 */
	private static String normalizeFormat(String format) {
		String value = format == null ? "" : format.toLowerCase();
		if (value.contains("mpeg") || value.contains("mp3")) {
			return "mp3";
		}
		if (value.contains("wav")) {
			return "wav";
		}
		if (value.contains("ogg")) {
			return "ogg";
		}
		if (value.contains("m4a") || value.contains("mp4")) {
			return "m4a";
		}
		if (value.contains("flac")) {
			return "flac";
		}
		return "mp3";
	}

	/** 读取字符串参数，缺失或 null 时返回空串。 */
	private static String stringField(JsonObject json, String key) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return "";
		}
		return json.get(key).getAsString().trim();
	}

	/** 取最内层异常的可读信息。 */
	private static String rootMessage(Throwable ex) {
		if (ex == null) {
			return "未知错误";
		}
		Throwable cause = ex;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}
