package com.khjxiaogu.aiwuxia.multimedia;

import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.mcp.LocalVoiceMcp;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;

/**
 * 本地语音（voice）的通用多媒体 API 适配器。
 * <p>
 * 提示词作为要朗读的文本；可选参数 {@code role} 指定说话人，缺省时取本地语音配置中的第一个
 * 说话人。附件参数暂不参与语音合成，留作后续参考音频等扩展。内部复用
 * {@link LocalVoiceMcp#generateVoice}，不改变原工具行为。
 * </p>
 */
public class LocalVoiceMultimediaApi extends ToolBackedMultimediaApi {

	/**
	 * @param storage 音频存储
	 */
	public LocalVoiceMultimediaApi(ObjectStorageProvider storage) {
		super(LocalVoiceMcp.TOOL_GENERATE_VOICE, LocalVoiceMcp.generateVoice(storage), storage);
	}

	@Override
	protected JsonObject arguments(MultimediaRequest request) {
		String text = request.getPrompt() == null ? "" : request.getPrompt().trim();
		if (text.isEmpty()) {
			throw new IllegalArgumentException("缺少要朗读的文本 prompt");
		}
		String role = request.getOption("role");
		if (role == null || role.trim().isEmpty()) {
			List<String> speakers = VoiceModelHandler.listLocalSpeakers();
			if (speakers.isEmpty()) {
				throw new IllegalArgumentException("本地语音当前没有可用的说话人，请在参数 role 中指定");
			}
			role = speakers.get(0);
		}
		JsonObject args = new JsonObject();
		args.addProperty("role", role.trim());
		args.addProperty("text", text);
		return args;
	}

	@Override
	protected MultimediaResult toResult(ToolResult result, MultimediaRequest request) {
		MultimediaResult converted = MultimediaSupport.fromToolResult(result, request, false, request.getPrompt());
		if (MultimediaSupport.hasAudio(converted)) {
			return converted;
		}
		String error = MultimediaSupport.toolText(result);
		return MultimediaResult.failure(error.isEmpty() ? converted.getPrompt() : error);
	}
}
