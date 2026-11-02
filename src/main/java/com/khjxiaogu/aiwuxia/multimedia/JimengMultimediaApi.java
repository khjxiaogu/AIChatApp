package com.khjxiaogu.aiwuxia.multimedia;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.mcp.SeedreamMcp;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;

/**
 * 即梦（Seedream / Seedance）的通用多媒体 API 适配器。
 * <p>
 * 支持两种模式：
 * </p>
 * <ul>
 *   <li>{@link Mode#IMAGE}：输入参考图 + 提示词，输出图片附件 + 文本结果；</li>
 *   <li>{@link Mode#VIDEO}：输入参考图 + 提示词（可选 duration 参数），输出视频附件 + 提示词。</li>
 * </ul>
 * 内部复用 {@link SeedreamMcp#createImage} / {@link SeedreamMcp#createVideo}，不改变原工具行为。
 */
public class JimengMultimediaApi extends ToolBackedMultimediaApi {

	/** 生成的媒体类型。 */
	public enum Mode {
		/** 即梦图片。 */
		IMAGE,
		/** 即梦 Seedance 视频。 */
		VIDEO
	}

	private final Mode mode;

	private JimengMultimediaApi(Mode mode, JsonObject config, Map<String, String> refImages,
			ObjectStorageProvider tos, String systemPrompt) {
		super(mode == Mode.IMAGE ? "jimeng_image" : "jimeng_video",
				mode == Mode.IMAGE ? SeedreamMcp.createImage(config, refImages, tos)
						: SeedreamMcp.createVideo(config, refImages, tos, systemPrompt),
				tos);
		this.mode = mode;
	}

	/** 即梦图片 API。 */
	public static JimengMultimediaApi image(JsonObject config, Map<String, String> refImages,
			ObjectStorageProvider tos) {
		return new JimengMultimediaApi(Mode.IMAGE, config, refImages, tos, null);
	}

	/** 即梦视频 API。 */
	public static JimengMultimediaApi video(JsonObject config, Map<String, String> refImages,
			ObjectStorageProvider tos, String systemPrompt) {
		return new JimengMultimediaApi(Mode.VIDEO, config, refImages, tos, systemPrompt);
	}

	/** 当前模式。 */
	public Mode getMode() {
		return mode;
	}

	@Override
	protected JsonObject arguments(MultimediaRequest request) {
		String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
		if (prompt.isEmpty()) {
			throw new IllegalArgumentException("缺少提示词 prompt");
		}
		List<String> references = MultimediaSupport.imageReferences(request, storage());
		JsonObject args = new JsonObject();
		args.addProperty("prompt", prompt);
		args.addProperty("reference", MultimediaSupport.joinReferences(references));
		if (mode == Mode.VIDEO) {
			String duration = request.getOption("duration");
			if (duration != null && !duration.trim().isEmpty()) {
				args.addProperty("duration", duration.trim());
			}
		}
		return args;
	}

	@Override
	protected MultimediaResult toResult(ToolResult result, MultimediaRequest request) {
		MultimediaResult converted = MultimediaSupport.fromToolResult(result, request, mode == Mode.IMAGE,
				mode == Mode.VIDEO ? request.getPrompt() : null);
		boolean hasExpected = mode == Mode.IMAGE ? MultimediaSupport.hasImage(converted)
				: MultimediaSupport.hasVideo(converted);
		if (hasExpected) {
			return converted;
		}
		String error = MultimediaSupport.toolText(result);
		return MultimediaResult.failure(error.isEmpty() ? converted.getPrompt() : error);
	}
}
