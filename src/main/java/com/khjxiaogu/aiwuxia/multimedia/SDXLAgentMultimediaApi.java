package com.khjxiaogu.aiwuxia.multimedia;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.mcp.SDXLAgentMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;

/**
 * SDXLAgent 的通用多媒体 API 适配器。
 * <p>
 * 输入附件作为参考图，提示词作为画面描述；输出提示词取子 agent 的文本结果，并从其中的
 * “图片id：...”提取生成图片作为附件。内部复用 {@link SDXLAgentMcp#createImage}，
 * 不改变原工具的参数与行为。
 * </p>
 */
public class SDXLAgentMultimediaApi extends ToolBackedMultimediaApi {

	/**
	 * @param path   子 agent 配置目录
	 * @param tos    对象存储
	 * @param lora   LoRA 配置
	 * @param charas 角色提示词
	 * @param isNsfw 是否允许不宜内容
	 * @param lock   资源锁
	 * @throws IOException 读取子 agent 配置失败
	 */
	public SDXLAgentMultimediaApi(File path, ObjectStorageProvider tos, Map<String, LoraConfigurations> lora,
			List<String> charas, boolean isNsfw, ResourceLock lock) throws IOException {
		super("sdxl_agent", SDXLAgentMcp.createImage(path, tos, lora, charas, isNsfw, lock), tos);
	}

	@Override
	protected JsonObject arguments(MultimediaRequest request) {
		String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
		if (prompt.isEmpty()) {
			throw new IllegalArgumentException("缺少提示词 prompt");
		}
		List<String> references = MultimediaSupport.imageReferences(request, storage());
		JsonObject args = new JsonObject();
		args.addProperty("reference", MultimediaSupport.joinReferences(references));
		args.addProperty("prompt", prompt);
		return args;
	}

	@Override
	protected MultimediaResult toResult(ToolResult result, MultimediaRequest request) {
		MultimediaResult converted = MultimediaSupport.fromToolResult(result, request, true, null);
		if (!MultimediaSupport.hasImage(converted)) {
			String error = MultimediaSupport.toolText(result);
			return MultimediaResult.failure(error.isEmpty() ? converted.getPrompt() : error);
		}
		return converted;
	}
}
