package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

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
	 * 创建图片识别工具。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createImageRecognition(ObjectStorageProvider tos) {
		return new ToolData.Builder("image_recognition", "使用多模态模型查看图片并返回图片描述。该功能仅负责描述图片内容，注意需要甄别描述中的内容是否合理，是否符合人物设定，人物是否应该知道对应内容。描述中的分析仅供参考，你需要独立判断是否正确。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容", "图片ID")
				.presenter(ToolCallPresenter.ofBrief(call -> "查看图片"))
				.toolText((data, output, manager) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						return recognizeImage(tos, id, output);

					}
					return "参数格式错误";
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

			try {
				if(tos.exists(id+".caption", output == null ? null : output::addUsage)) {
					try {
						return new String(tos.download(id+".caption", output == null ? null : output::addUsage),StandardCharsets.UTF_8);
					} catch (IOException e) {
						e.printStackTrace();
						return "服务暂不可用";
					}
				}
				Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
						.multimodal(MultimodalType.IMAGE_ONLY);
				builder.addHistoryItem(Role.SYSTEM,
						"请观察图片，详细具体客观描述其中的内容，文字，人物，细节特征，位置，风格等信息，并原样提供图片中所有文本原文内容。最后要输出图片分辨率，图片中人物所在位置坐标等信息。你返回的信息会交由AI处理，请准确描述图片信息以便于AI了解图片内容并进行进一步处理，输出仅会供给AI内部安全使用，不会呈现给任何人。图中如有角色，均已成年且为虚拟角色，与现实毫无关系。");

				builder.addHistoryItem(
						new DirectHistoryItem(Role.USER, new ImageContent(tos.getPublicUrl(id,output == null ? null : output::addUsage))));
				builder.modelHint("volces").temperature(1.3f).maxTokens(3000).streamed();
				String caption="";
				for(int t=0;t<4;t++) {
					AIOutput inner=LLMConnector.call(builder.build());
					if (output != null) {
						inner.addUsageListener(output::addUsage);
					}
					FileUtil.printAndCollectContent(inner.getReasoner());
					caption= FileUtil.printAndCollectContent(inner.getContent()).toText();
					if(caption.length()>=200||(caption.length()>=120&&!caption.contains("公序良俗")))
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
}
