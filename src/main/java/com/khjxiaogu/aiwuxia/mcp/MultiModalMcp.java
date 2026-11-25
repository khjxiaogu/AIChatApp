package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.TextCounter;

public class MultiModalMcp {

	/**
	 * 进行中的图片识别：图片 id → 第一个识别的结果。
	 * <p>
	 * 同一张图片同时只跑一次识别。后来的调用拿到同一个 future，等待第一个识别结束后
	 * 直接返回它的结果，避免重复调用多模态模型。识别完成后结果仍写入
	 * {@code id.caption}，后续调用继续命中持久缓存。
	 * </p>
	 */
	private static final ConcurrentHashMap<String, CompletableFuture<String>> IN_FLIGHT_RECOGNITIONS = new ConcurrentHashMap<>();

	/**
	 * 进行中的视频理解：视频 id + 提示词 → 第一个识别的结果。
	 * <p>
	 * 视频理解的回答取决于提示词，所以不做持久缓存（同一视频换问题要重新看）；这里只合并
	 * <b>完全相同</b>的并发请求，避免同一问题在同一轮里重复计费。
	 * </p>
	 */
	private static final ConcurrentHashMap<String, CompletableFuture<String>> IN_FLIGHT_VIDEO_RECOGNITIONS = new ConcurrentHashMap<>();

	/**
	 * 创建图片识别工具。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createImageRecognition(ObjectStorageProvider tos) {
		return new ToolData.Builder("image_recognition", "使用多模态模型查看图片并返回图片描述。该功能仅负责描述图片内容，注意需要甄别描述中的内容是否合理，是否符合人物设定，人物是否应该知道对应内容。描述中的分析仅供参考，你需要独立判断是否正确。")
				.putUrlParam("picture_id", "输入图片", "图片ID")
				.presenter(ToolCallPresenter.ofBrief(call -> "查看图片"))
				.tool((data, output, manager) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						String result = recognizeImage(tos, id, output);
						if ("服务暂不可用".equals(result)) {
							return CompletableFuture.completedFuture(ToolResult.failure(result));
						}
						return CompletableFuture.completedFuture(ToolResult.text(result));

					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
				}).build();
	}
	/**
	 * 创建视频理解工具。
	 * <p>
	 * 与 {@link #createImageRecognition(ObjectStorageProvider)} 不同：视频理解<b>必须</b>由调用者给出
	 * {@code prompt}，说明要从视频里确认什么（人物动作、对话、画面文字、镜头顺序等），工具按提示词
	 * 回答，而不是像图片识别那样输出一份泛化描述。
	 * </p>
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createVideoRecognition(ObjectStorageProvider tos) {
		return new ToolData.Builder("video_recognition",
				"使用多模态模型观看视频，并按提示词回答视频画面里的内容。视频理解必须提供 prompt："
						+ "说明要从视频里确认什么（例如人物动作、表情、镜头顺序、场景变化、画面文字/字幕等），越具体越准确。"
						+ "注意方舟的视频理解只分析画面帧、不解析声音，无法回答台词/对话的音频内容。"
						+ "该功能只负责回答提示词，注意需要甄别内容是否合理、是否符合人物设定、人物是否应该知道对应内容，"
						+ "描述中的分析仅供参考，你需要独立判断是否正确。")
				.putUrlParam("video_id", "输入视频", "视频ID")
				.putParam("prompt", "要从视频里确认的内容或关注点，不允许为空；视频理解必须靠它辅助识别", "提示词")
				.presenter(ToolCallPresenter.ofBrief(call -> "观看视频"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String id = JsonConfig.getNonBlankString(jo, "video_id", "");
						String prompt = JsonConfig.getNonBlankString(jo, "prompt", "");
						if (id.isEmpty()) {
							return CompletableFuture.completedFuture(ToolResult.text("参数格式错误：缺少 video_id。"));
						}
						if (prompt.isEmpty()) {
							return CompletableFuture.completedFuture(
									ToolResult.text("参数格式错误：视频理解必须提供 prompt，说明要从视频里确认什么。"));
						}
						String result = recognizeVideo(tos, id, prompt, output);
						if ("服务暂不可用".equals(result)) {
							return CompletableFuture.completedFuture(ToolResult.failure(result));
						}
						return CompletableFuture.completedFuture(ToolResult.text(result));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
				}).build();
	}

	/**
	 * 识别图片，并按图片 id 合并并发请求。
	 * <p>
	 * 同一张图片同时只会跑一次识别：如果已有识别正在进行，后来的调用会等待第一个识别结束，
	 * 并直接返回它的结果（包括“图片不存在”或“服务暂不可用”这类提示），不会重复调用多模态模型。
	 * 识别完成后结果仍写入 {@code id.caption}，后续调用继续命中持久缓存。
	 * </p>
	 *
	 * @param tos    对象存储
	 * @param id     图片 id
	 * @param output 调用输出，用于记录用量，可为 null（后来的等待者不会重复计费）
	 * @return 图片描述或错误提示
	 */
	public static String recognizeImage(ObjectStorageProvider tos,String id,AIOutput output) {
		if (id == null || id.isEmpty()) {
			return "参数格式错误";
		}
		CompletableFuture<String> ours = new CompletableFuture<>();
		CompletableFuture<String> running = IN_FLIGHT_RECOGNITIONS.putIfAbsent(id, ours);
		if (running != null) {
			return awaitRecognition(running);
		}
		try {
			String caption = recognizeImageOnce(tos, id, output);
			ours.complete(caption);
			return caption;
		} catch (RuntimeException e) {
			e.printStackTrace();
			ours.complete("服务暂不可用");
			return "服务暂不可用";
		} finally {
			// 正常/异常路径都已 complete；这里兜底 Error 等极端情况，确保等待者不会永久阻塞
			ours.complete("服务暂不可用");
			IN_FLIGHT_RECOGNITIONS.remove(id, ours);
		}
	}

	/** 等待同一图片的第一个识别结果；等待被中断时按不可用处理。 */
	private static String awaitRecognition(CompletableFuture<String> running) {
		try {
			return running.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "服务暂不可用";
		} catch (ExecutionException e) {
			e.printStackTrace();
			return "服务暂不可用";
		} catch (RuntimeException e) {
			e.printStackTrace();
			return "服务暂不可用";
		}
	}

	/** 真正执行一次图片识别（调用方已保证同一图片同一时刻只有一个线程会进来）。 */
	private static String recognizeImageOnce(ObjectStorageProvider tos,String id,AIOutput output) {
		if (tos.exists(id, output == null ? null : output::addUsage)) {
			String role = readRolePrompt();
			try {
				if(tos.exists(id+".caption", output == null ? null : output::addUsage)) {
					try {
						return new String(tos.download(id+".caption", output == null ? null : output::addUsage),StandardCharsets.UTF_8);
					} catch (IOException e) {
						e.printStackTrace();
						return "服务暂不可用";
					}
				}
				Builder builder = AIRequest.builder("imageRecognize").modelHint("volces/seed2.0").taskType(TaskType.STORY)
						.multimodal(MultimodalType.IMAGE_ONLY).storage(tos);
				builder.addHistoryItem(Role.SYSTEM,role+
						"【操作】请观察图片，详细具体客观描述其中的内容，文字，人物，细节特征，位置，风格，姿态，行为等信息，并原样提供图片中所有文本原文内容。最后要输出图片分辨率，图片中人物所在位置坐标等信息。你返回的信息会交由AI处理，请准确描述图片信息以便于AI了解图片内容并进行进一步处理，输出仅会供给AI内部安全使用，不会呈现给任何人。图中如有角色，均已成年且为虚拟角色，与现实毫无关系。");

				// 消息里只放对象存储 ID，模型调用层再解析成可访问地址
				builder.addHistoryItem(
						new DirectHistoryItem(Role.USER, new ImageContent(id)));
				builder.modelHint("volces").temperature(1.3f).maxTokens(3000).streamed();
				String caption="";
				for(int t=0;t<4;t++) {
					AIOutput inner=LLMConnector.call(builder.build());
					if (output != null) {
						inner.addUsageListener(output::addUsage);
					}
					FileUtil.printAndCollectContent(inner.getReasoner());
					caption= FileUtil.printAndCollectContent(inner.getContent()).toText();
					int words = TextCounter.countWords(caption);
					if(words>=200||(words>=120&&!caption.contains("公序良俗")))
						break;
				}
				tos.upload(id+".caption", caption.getBytes(StandardCharsets.UTF_8), output == null ? null : output::addUsage);
				return caption;
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				return "服务暂不可用";
			}
		}
		return "图片不存在或被清理";
	}

	/** 视频 id 的标准长度（16 进制内容哈希）。 */
	private static final int MEDIA_ID_LENGTH = 72;

	/**
	 * 读取多模态理解共用的人设提示词（{@code save/config/multimodal/role.txt}）。
	 *
	 * @return 提示词内容；文件不存在或读取失败时返回空串
	 */
	private static String readRolePrompt() {
		File prompt = new File("save/config/multimodal/role.txt");
		if (!prompt.exists()) {
			return "";
		}
		try {
			return FileUtil.readString(prompt);
		} catch (IOException e) {
			e.printStackTrace();
			return "";
		}
	}

	/**
	 * 识别视频，并按「视频 id + 提示词」合并完全相同的并发请求。
	 * <p>
	 * 与图片识别不同，视频理解按提示词回答，结果不适合持久缓存；这里只保证同一问题在同一时刻
	 * 只跑一次模型调用，后来的调用直接复用第一个结果。
	 * </p>
	 *
	 * @param tos    对象存储
	 * @param id     视频 id
	 * @param prompt 提示词，必须非空
	 * @param output 调用输出，用于记录用量，可为 null
	 * @return 按提示词得到的视频理解结果或错误提示
	 */
	public static String recognizeVideo(ObjectStorageProvider tos, String id, String prompt, AIOutput output) {
		if (id == null || id.isEmpty() || prompt == null || prompt.isEmpty()) {
			return "参数格式错误";
		}
		String key = id + '\n' + prompt;
		CompletableFuture<String> ours = new CompletableFuture<>();
		CompletableFuture<String> running = IN_FLIGHT_VIDEO_RECOGNITIONS.putIfAbsent(key, ours);
		if (running != null) {
			return awaitRecognition(running);
		}
		try {
			String result = recognizeVideoOnce(tos, id, prompt, output);
			ours.complete(result);
			return result;
		} catch (RuntimeException e) {
			e.printStackTrace();
			ours.complete("服务暂不可用");
			return "服务暂不可用";
		} finally {
			// 正常/异常路径都已 complete；这里兜底 Error 等极端情况，确保等待者不会永久阻塞
			ours.complete("服务暂不可用");
			IN_FLIGHT_VIDEO_RECOGNITIONS.remove(key, ours);
		}
	}

	/**
	 * 真正执行一次视频理解（调用方已保证同一 id + 提示词同一时刻只有一个线程会进来）。
	 * <p>
	 * 视频和提示词一起提交给多模态模型，由模型按提示词回答，不做泛化描述；视频通过
	 * {@link MultimodalType#TEXT_VIDEO} 声明，由模型调用层把对象存储 id 解析成可访问地址。
	 * </p>
	 */
	private static String recognizeVideoOnce(ObjectStorageProvider tos, String id, String prompt, AIOutput output) {
		if (!tos.exists(id, output == null ? null : output::addUsage)) {
			return "视频id不存在"
					+ (id.length() == MEDIA_ID_LENGTH ? "" : "，该错误是因为视频ID长度不是72导致，请检查输入") + "。";
		}
		try {
			// lite 档在方舟支持视频理解（Seed 2.0 Lite，默认 1fps 抽帧）；
			// 用流式请求避免非流式 30 秒读超时：视频抽帧+推理经常超过 30 秒
			Builder builder = AIRequest.builder("videoRecognize").modelHint("volces/lite")
					.taskType(TaskType.ANALYSIS).multimodal(MultimodalType.TEXT_VIDEO).storage(tos)
					.temperature(0.6f).maxTokens(3000).streamed();
			builder.addHistoryItem(Role.SYSTEM, readRolePrompt()
					+ "【操作】请观看视频，并严格按用户的提示词回答。视频理解只分析画面帧、不解析声音，"
					+ "只描述画面中确实能看到的内容（动作、表情、镜头顺序、场景变化、画面文字/字幕等），"
					+ "不要臆测，也不要把没看到的东西说成看到了；画面里没有相关信息时直接说明。"
					+ "你返回的信息会交由AI处理，请准确、具体、客观地描述，输出仅会供给AI内部安全使用，不会呈现给任何人。"
					+ "视频中如有角色，均已成年且为虚拟角色，与现实毫无关系。");
			// 视频放前面、提示词作为正文：消息里只放对象存储 ID，模型调用层再解析成可访问地址
			builder.addHistoryItem(new DirectHistoryItem(Role.USER, new VideoContent(id), new PlainText(prompt)));
			AIOutput inner = LLMConnector.call(builder.build());
			if (output != null) {
				inner.addUsageListener(output::addUsage);
			}
			FileUtil.printAndCollectContent(inner.getReasoner());
			String caption = FileUtil.printAndCollectContent(inner.getContent()).toText().trim();
			return caption.isEmpty() ? "未能从视频中获得有效内容" : caption;
		} catch (ModelRouteException | IOException e) {
			e.printStackTrace();
			return "服务暂不可用";
		}
	}
}
