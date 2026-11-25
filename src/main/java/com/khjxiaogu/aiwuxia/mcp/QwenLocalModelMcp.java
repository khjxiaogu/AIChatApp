/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelAttachments;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCall;
import com.khjxiaogu.aiwuxia.modelproxy.api.QwenParams;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.vision.QwenImageApi;

/**
 * 通过本地模型通道调用 Qwen-Image 2.1 的 MCP 工具。
 * <p>
 * 与直接访问 ComfyUI 的 {@link QwenImageMcp} 不同：本工具只把请求交给
 * {@link LocalModel#require(ModelType, String, JsonObject)}，真正的生成发生在
 * {@code LocalModelConnectionService} 一侧的 {@code model_type=qwen} 客户端
 * （见 {@link com.khjxiaogu.aiwuxia.vision.QwenLocalModelHandler}）。因此：
 * </p>
 * <ul>
 *   <li>工具进程不需要直接访问 ComfyUI，也不需要本机显卡；</li>
 *   <li>Qwen 与 SDXL / SVS 共用模型服务里的资源锁，不会同时抢显存；</li>
 *   <li>模型服务没启动时工具会直接提示“本地模型服务未连接”，而不是卡住。</li>
 * </ul>
 * <p>
 * 工具名与 {@link QwenImageMcp} 保持一致（{@value #TOOL_GENERATE} / {@value #TOOL_EDIT}），
 * 但两者不要注册进同一个注册表。
 * </p>
 */
public class QwenLocalModelMcp implements ToolProvider {

	/** 文生图工具名。 */
	public static final String TOOL_GENERATE = QwenImageMcp.TOOL_GENERATE;
	/** 改图/参考图工具名。 */
	public static final String TOOL_EDIT = QwenImageMcp.TOOL_EDIT;
	/** 单次生成最长等待时间（分钟）。 */
	private static final long GENERATE_TIMEOUT_MINUTES = 10L;

	private final ObjectStorageProvider tos;
	private final List<ToolData> tools = new ArrayList<>();

	/**
	 * @param tos 生成图片的对象存储，可为 null（此时工具返回失败）
	 */
	public QwenLocalModelMcp(ObjectStorageProvider tos) {
		this.tos = tos;
		tools.add(buildGenerateTool(tos, true));
		tools.add(createEditTool());
	}

	/**
	 * 只构建文生图工具，供其它子 agent（例如 SDXLAgent）作为参考底图生成器使用。
	 * <p>
	 * 该工具是同步的：调用后会等待图片生成并返回图片 ID，子 agent 可以立即把 ID
	 * 用作后续图生图/分区出图的参考图。
	 * </p>
	 *
	 * @param tos 对象存储
	 * @return 同步文生图工具
	 */
	public static ToolData createTextToImageTool(ObjectStorageProvider tos) {
		return buildGenerateTool(tos, false);
	}

	@Override
	public Collection<ToolData> getTools() {
		return tools;
	}

	private static ToolData buildGenerateTool(ObjectStorageProvider tos, boolean async) {
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
					String prompt = JsonConfig.getNonBlankString(jo, "prompt", "");
					if (prompt.isEmpty()) {
						return ToolResult.text("参数错误：缺少提示词 prompt。");
					}
					boolean fast = JsonConfig.getBool(jo, "fast", false);
					int[] size = QwenImageMcp.dimensions(JsonConfig.getNonBlankString(jo, "resolution", "3:4"), fast);
					String reqid = UUID.randomUUID().toString();
					QwenParams params = new QwenParams(QwenParams.TEXT2IMAGE, prompt,
							JsonConfig.getNonBlankString(jo, "negative", ""), size[0], size[1],
							fast ? QwenImageApi.FAST_STEPS : QwenImageApi.DEFAULT_STEPS, QwenImageApi.DEFAULT_CFG,
							QwenImageApi.randomSeed(), fast, List.of());
					List<QwenImageApi.ImageResult> images = generate(
							LocalModelCall.qwen(reqid, params, null));
					return QwenImageMcp.uploadResults(tos, images, "图片生成成功", output);
				} catch (IllegalArgumentException ex) {
					return ToolResult.text("参数错误：" + ex.getMessage());
				} catch (Throwable ex) {
					return ToolResult.failure("Qwen Image 生成失败：" + failureMessage(ex));
				}
			});
			// 同步工具直接等待结果，异步工具由 ToolCallManager 登记为后台任务
			return async ? future : CompletableFuture.completedFuture(future.join());
		}).build();
	}

	private ToolData createEditTool() {
		return new ToolData.Builder(TOOL_EDIT,
				"使用 Qwen-Image 2.1 对参考图进行改图，或参考多张图生成新图。"
						+ "参考图最多 10 张，按顺序对应图1、图2…；"
						+ "生成后需要使用发送图片工具发给用户。")
				.putUrlParam("reference", "参考图（多张用英文逗号分隔，最多 10 张）", "参考图")
				.putParam("prompt", "编辑指令或画面提示词，支持中文，例如“把外套换成蓝色”。", "提示词")
				.putParam("negative", "负面提示词，可留空使用默认中文负面词。", "负面提示词")
				.putParam("fast", "是否使用 8 步加速 LoRA，默认 false。", "极速草稿")
				.presenter(ToolCallPresenter.ofArgument("Qwen 改图", "prompt"))
				.async()
				.tool((data, output, manager) -> CompletableFuture.supplyAsync(() -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String prompt = JsonConfig.getNonBlankString(jo, "prompt", "");
						if (prompt.isEmpty()) {
							return ToolResult.text("参数错误：缺少提示词 prompt。");
						}
						List<String> references = QwenImageMcp.splitReferences(
								JsonConfig.getNonBlankString(jo, "reference", ""));
						if (references.isEmpty()) {
							return ToolResult.text("参数错误：缺少参考图 reference。");
						}
						if (references.size() > QwenImageApi.MAX_REFERENCE_IMAGES) {
							return ToolResult.text("参数错误：参考图最多 " + QwenImageApi.MAX_REFERENCE_IMAGES
									+ " 张。");
						}
						if (tos == null) {
							return ToolResult.failure("图片存储不可用，无法读取参考图。");
						}
						String reqid = UUID.randomUUID().toString();
						List<AttachmentRef> attachments = new ArrayList<>();
						List<String> imageIds = new ArrayList<>();
						for (String id : references) {
							if (!tos.exists(id, output == null ? null : output::addUsage)) {
								return ToolResult.text("参数错误：参考图" + id + "已清理或不存在。");
							}
							AttachmentRef ref = LocalModelAttachments.shared().register(reqid, null,
									AttachmentRef.KIND_IMAGE, "png", id + ".png", id,
									() -> download(tos, id, output));
							attachments.add(ref);
							imageIds.add(ref.id);
						}
						boolean fast = JsonConfig.getBool(jo, "fast", false);
						QwenParams params = new QwenParams(QwenParams.EDIT, prompt,
								JsonConfig.getNonBlankString(jo, "negative", ""), 1024, 1024,
								fast ? QwenImageApi.FAST_STEPS : QwenImageApi.DEFAULT_STEPS,
								QwenImageApi.DEFAULT_CFG, QwenImageApi.randomSeed(), fast, imageIds);
						List<QwenImageApi.ImageResult> results = generate(
								LocalModelCall.qwen(reqid, params, attachments));
						return QwenImageMcp.uploadResults(tos, results, "图片生成成功", output);
					} catch (IllegalArgumentException ex) {
						return ToolResult.text("参数错误：" + ex.getMessage());
					} catch (Throwable ex) {
						return ToolResult.failure("Qwen Image 生成失败：" + failureMessage(ex));
					}
				}))
				.build();
	}

	/**
	 * 通过本地模型通道执行一次 Qwen 请求。
	 *
	 * @param call 统一调用对象（参数 + 参考图附件）
	 * @return 生成的图片
	 * @throws Exception 本地模型离线、超时或生成失败
	 */
	static List<QwenImageApi.ImageResult> generate(LocalModelCall<QwenParams> call) throws Exception {
		long timeoutMillis = TimeUnit.MINUTES.toMillis(GENERATE_TIMEOUT_MINUTES);
		ModelGenerationResult result = LocalModel.lhs.send(null, call, timeoutMillis).get(timeoutMillis,
				TimeUnit.MILLISECONDS);
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw new IllegalStateException("本地 Qwen 模型没有返回图片");
		}
		String format = result.format == null ? "" : result.format.trim().toLowerCase();
		if (!isImageFormat(format)) {
			throw new IllegalStateException("本地 Qwen 模型返回了非图片结果：" + format);
		}
		return List.of(new QwenImageApi.ImageResult("qwen." + extensionOf(format), "", "output", result.bodyData));
	}

	/** 把对象存储里的文件读成字节，供附件惰性拉取。 */
	private static byte[] download(ObjectStorageProvider tos, String id, AIOutput output) {
		try {
			return tos.download(id, output == null ? null : output::addUsage);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	/** 是否是图片格式名（png/jpeg/... 或 image/*）。 */
	private static boolean isImageFormat(String format) {
		switch (format) {
		case "png":
		case "jpg":
		case "jpeg":
		case "webp":
		case "gif":
		case "bmp":
			return true;
		default:
			return format.startsWith("image/");
		}
	}

	/** 图片扩展名。 */
	private static String extensionOf(String format) {
		switch (format) {
		case "jpeg":
		case "jpg":
			return "jpg";
		case "image/jpeg":
			return "jpg";
		case "image/png":
			return "png";
		case "image/webp":
			return "webp";
		case "gif":
		case "image/gif":
			return "gif";
		case "bmp":
		case "image/bmp":
			return "bmp";
		default:
			return "png";
		}
	}

	/** 把底层异常转成便于模型理解的提示。 */
	private static String failureMessage(Throwable ex) {
		String message = QwenImageMcp.rootMessage(ex);
		if (message != null && message.contains("no local model found")) {
			return "本地模型服务未连接（Qwen 通道），请先启动 LocalModelConnectionService。";
		}
		return message;
	}
}
