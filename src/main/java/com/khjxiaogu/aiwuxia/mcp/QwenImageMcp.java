package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.vision.QwenImageApi;

/**
 * Qwen-Image 2.1（本地 ComfyUI）MCP 工具。
 * <p>
 * 提供两个工具：
 * </p>
 * <ul>
 *   <li>{@code qwen_gen_image}：文生图/极速草稿；</li>
 *   <li>{@code qwen_edit_image}：单图改图或多图参考（参考图用对象存储 ID 传入）。</li>
 * </ul>
 * <p>
 * 生成结果先上传对象存储，再把图片 ID 与 {@link ImageContent} 放进工具结果，由会话按需发送。
 * 所有生成操作都通过 {@link QwenImageApi} 的 {@link ResourceLock} 串行化；资源锁默认占用 6 份，
 * 可与 SDXL 等本地模型共用同一把锁，按容量协调显存。
 * </p>
 */
public class QwenImageMcp implements ToolProvider {

	/** 文生图工具名。 */
	public static final String TOOL_GENERATE = "qwen_gen_image";
	/** 改图/参考图工具名。 */
	public static final String TOOL_EDIT = "qwen_edit_image";

	private final QwenImageApi api;
	private final ObjectStorageProvider tos;
	private final List<ToolData> tools = new ArrayList<>();

	/**
	 * @param api Qwen Image 客户端（已配置资源锁）
	 * @param tos 生成图片的对象存储
	 */
	public QwenImageMcp(QwenImageApi api, ObjectStorageProvider tos) {
		this.api = api;
		this.tos = tos;
		tools.add(buildGenerateTool(api, tos, true));
		tools.add(createEditTool());
	}

	/**
	 * 只构建文生图工具，供其它子 agent（例如 SDXLAgent）作为“参考底图生成器”使用。
	 * <p>
	 * 该工具是<b>同步</b>的：调用后会等待图片生成并返回图片 ID，子 agent 可以立即把 ID
	 * 用作后续图生图/分区出图的参考图。独立的 Qwen MCP 工具集里则使用异步版本。
	 * </p>
	 *
	 * @param api Qwen Image 客户端
	 * @param tos 对象存储
	 * @return 同步文生图工具
	 */
	public static ToolData createTextToImageTool(QwenImageApi api, ObjectStorageProvider tos) {
		return buildGenerateTool(api, tos, false);
	}

	/**
	 * 使用默认地址创建，并与传入的资源锁串行化。
	 *
	 * @param tos    对象存储
	 * @param lock   资源锁，null 时新建一个容量 6 的锁
	 * @param amount 每次生成占用的资源量，建议 6
	 * @return MCP 工具集
	 */
	public static QwenImageMcp create(ObjectStorageProvider tos, ResourceLock lock, int amount) {
		return create(tos, null, lock, amount);
	}

	/**
	 * 创建 Qwen Image MCP 工具集。
	 *
	 * @param tos     对象存储
	 * @param baseUrl ComfyUI 地址，null 时使用系统属性/默认地址
	 * @param lock    资源锁，null 时新建一个容量 6 的锁
	 * @param amount  每次生成占用的资源量，建议 6
	 * @return MCP 工具集
	 */
	public static QwenImageMcp create(ObjectStorageProvider tos, String baseUrl, ResourceLock lock, int amount) {
		ResourceLock effectiveLock = lock == null ? new ResourceLock(6) : lock;
		int effectiveAmount = lock == null ? 6 : amount;
		QwenImageApi api = baseUrl == null || baseUrl.trim().isEmpty()
				? new QwenImageApi()
				: new QwenImageApi(baseUrl);
		api.setResourceLock(effectiveLock, effectiveAmount);
		return new QwenImageMcp(api, tos);
	}

	/** 使用默认地址和独立锁（6 份资源）创建。 */
	public static QwenImageMcp create(ObjectStorageProvider tos) {
		return create(tos, null, 6);
	}

	@Override
	public Collection<ToolData> getTools() {
		return tools;
	}

	private static ToolData buildGenerateTool(QwenImageApi api, ObjectStorageProvider tos, boolean async) {
		ToolData.Builder builder = new ToolData.Builder(TOOL_GENERATE,
				"使用 Qwen-Image 2.1 根据提示词生成图片，可以用于生成参考底图：先生成构图、风格或人物姿态的底图，"
						+ "再把返回的图片id作为后续 image_to_image / regional_image 的参考图。"
						+ "支持文生图和 8 步极速草稿。")
				.putParam("prompt", "画面提示词，支持中文自然语言描述。", "提示词")
				.putParam("negative", "负面提示词，可留空使用默认中文负面词。", "负面提示词")
				.putParam("resolution", "画面比例，必须是 16:9/9:16/3:4/4:3/1:1 之一；默认 3:4。", "画面比例")
				.putParam("fast", "是否使用 8 步加速 LoRA，默认 false。", "极速草稿")
				.presenter(ToolCallPresenter.ofArgument("Qwen 生图", "prompt"));
		if (async) {
			builder.async();
		}
		return builder.tool((data, output, manager) -> {
			CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
				try {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					String prompt = stringField(jo, "prompt", "");
					if (prompt.isEmpty()) {
						return ToolResult.text("参数错误：缺少提示词 prompt。");
					}
					boolean fast = boolField(jo, "fast", false);
					String ratio = stringField(jo, "resolution", "3:4");
					int[] size = dimensions(ratio, fast);
					// 步数/CFG/种子固定，不由模型设置
					int steps = fast ? QwenImageApi.FAST_STEPS : QwenImageApi.DEFAULT_STEPS;
					float cfg = QwenImageApi.DEFAULT_CFG;
					long seed = QwenImageApi.randomSeed();
					List<QwenImageApi.ImageResult> images = api.textToImage(prompt,
							stringField(jo, "negative", ""), size[0], size[1], steps, cfg, seed, 1024, fast, true);
					return uploadResults(tos, images, "图片生成成功", output);
				} catch (IllegalArgumentException ex) {
					return ToolResult.text("参数错误：" + ex.getMessage());
				} catch (Throwable ex) {
					return ToolResult.failure("Qwen Image 生成失败：" + rootMessage(ex));
				}
			});
			// 同步工具直接等待结果，异步工具由 ToolCallManager 登记为后台任务
			return async ? future : CompletableFuture.completedFuture(future.join());
		}).build();
	}

	private ToolData createEditTool() {
		return new ToolData.Builder(TOOL_EDIT,
				"使用 Qwen-Image 2.1 对参考图进行改图，或参考多张图生成新图。"
						+ "参考图用对象存储 ID 传入，最多 10 张，按顺序对应图1、图2…；"
						+ "生成后需要使用发送图片工具发给用户。")
				.putParam("reference", "参考图对象存储 ID，多张用英文逗号分隔，最多 10 张。", "参考图")
				.putParam("prompt", "编辑指令或画面提示词，支持中文，例如“把外套换成蓝色”。", "提示词")
				.putParam("negative", "负面提示词，可留空使用默认中文负面词。", "负面提示词")
				.putParam("fast", "是否使用 8 步加速 LoRA，默认 false。", "极速草稿")
				.presenter(ToolCallPresenter.ofArgument("Qwen 改图", "prompt"))
				.async()
				.tool((data, output, manager) -> CompletableFuture.supplyAsync(() -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String prompt = stringField(jo, "prompt", "");
						if (prompt.isEmpty()) {
							return ToolResult.text("参数错误：缺少提示词 prompt。");
						}
						List<String> references = splitReferences(stringField(jo, "reference", ""));
						if (references.isEmpty()) {
							return ToolResult.text("参数错误：缺少参考图 reference。");
						}
						if (references.size() > QwenImageApi.MAX_REFERENCE_IMAGES) {
							return ToolResult.text("参数错误：参考图最多 " + QwenImageApi.MAX_REFERENCE_IMAGES + " 张。");
						}
						if (tos == null) {
							return ToolResult.failure("图片存储不可用，无法读取参考图。");
						}
						List<QwenImageApi.InputImage> inputs = new ArrayList<>(references.size());
						for (String id : references) {
							if (!tos.exists(id, output == null ? null : output::addUsage)) {
								return ToolResult.text("参数错误：参考图" + id + "已清理或不存在。");
							}
							inputs.add(new QwenImageApi.InputImage(tos.download(id,
									output == null ? null : output::addUsage), id + ".png"));
						}
						boolean fast = boolField(jo, "fast", false);
						int steps = fast ? QwenImageApi.FAST_STEPS : QwenImageApi.DEFAULT_STEPS;
						float cfg = QwenImageApi.DEFAULT_CFG;
						long seed = QwenImageApi.randomSeed();
						List<QwenImageApi.ImageResult> images;
						if (inputs.size() == 1) {
							images = api.edit(inputs.get(0), prompt, stringField(jo, "negative", ""), steps, cfg,
									seed, 1024, fast, true);
						} else {
							images = api.reference(inputs, prompt, stringField(jo, "negative", ""), steps, cfg,
									seed, 1024, fast, true);
						}
						return uploadResults(tos, images, "图片生成成功", output);
					} catch (IllegalArgumentException ex) {
						return ToolResult.text("参数错误：" + ex.getMessage());
					} catch (Throwable ex) {
						return ToolResult.failure("Qwen Image 生成失败：" + rootMessage(ex));
					}
				}))
				.build();
	}

	/**
	 * 画面比例 → 实际尺寸。普通模式长边 1024，极速模式长边 768，避免默认图过大。
	 */
	private static int[] dimensions(String ratio, boolean fast) {
		String key = ratio == null ? "" : ratio.trim();
		if (fast) {
			switch (key) {
			case "16:9":
				return new int[] { 768, 432 };
			case "9:16":
				return new int[] { 432, 768 };
			case "3:4":
				return new int[] { 576, 768 };
			case "4:3":
				return new int[] { 768, 576 };
			case "1:1":
				return new int[] { 768, 768 };
			default:
				throw new IllegalArgumentException("不支持的比例 " + key + "，必须是 16:9/9:16/3:4/4:3/1:1 之一");
			}
		}
		switch (key) {
		case "16:9":
			return new int[] { 1024, 576 };
		case "9:16":
			return new int[] { 576, 1024 };
		case "3:4":
			return new int[] { 768, 1024 };
		case "4:3":
			return new int[] { 1024, 768 };
		case "1:1":
			return new int[] { 1024, 1024 };
		default:
			throw new IllegalArgumentException("不支持的比例 " + key + "，必须是 16:9/9:16/3:4/4:3/1:1 之一");
		}
	}

	/** 上传生成图片并把 ID 与图片内容写入工具结果。 */
	private static ToolResult uploadResults(ObjectStorageProvider tos, List<QwenImageApi.ImageResult> images,
			String prefix, AIOutput output) throws IOException {
		if (images == null || images.isEmpty()) {
			return ToolResult.failure(prefix + "失败：服务没有返回图片。");
		}
		if (tos == null) {
			return ToolResult.failure(prefix + "失败：图片存储不可用。");
		}
		MutableMessageContents content = new MutableMessageContents();
		for (QwenImageApi.ImageResult image : images) {
			if (image == null || image.data == null || image.data.length == 0) {
				continue;
			}
			String id = tos.uploadIfNotExists(image.data, output == null ? null : output::addUsage);
			content.append(prefix + "，图片id为：" + id + "。");
			content.addRaw(new ImageContent(id));
		}
		if (content.isEmpty()) {
			return ToolResult.failure(prefix + "失败：没有可保存的图片数据。");
		}
		return ToolResult.content(content);
	}

	/** 拆分参考图 ID。 */
	private static List<String> splitReferences(String raw) {
		List<String> references = new ArrayList<>();
		if (raw == null) {
			return references;
		}
		for (String part : raw.split(",")) {
			String value = part.trim();
			if (!value.isEmpty()) {
				references.add(value);
			}
		}
		return references;
	}

	private static String stringField(JsonObject json, String key, String fallback) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return fallback;
		}
		String value = json.get(key).getAsString();
		return value == null ? fallback : value.trim();
	}

	private static boolean boolField(JsonObject json, String key, boolean fallback) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			String raw = json.get(key).getAsString().trim();
			if (raw.isEmpty()) {
				return fallback;
			}
			return Boolean.parseBoolean(raw) || "1".equals(raw) || "是".equals(raw);
		} catch (RuntimeException ex) {
			return fallback;
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
