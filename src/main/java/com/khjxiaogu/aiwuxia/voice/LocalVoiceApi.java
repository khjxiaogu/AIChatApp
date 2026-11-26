package com.khjxiaogu.aiwuxia.voice;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class LocalVoiceApi implements VoiceModel {
	VoiceTagger vt;
	Map<String,String> botids;
	Gson gs=new Gson();
	@SuppressWarnings("unchecked")
	public LocalVoiceApi(File file) throws JsonSyntaxException, IOException {
		super();
		Map<String, String> loaded = gs.fromJson(FileUtil.readString(ConfigPaths.localVoice(file)), Map.class);
		this.botids = loaded == null ? new HashMap<>() : loaded;
		vt=new VoiceTagger(file);
	}
	@Override
	public CompletableFuture<ModelGenerationResult> getAudioData(String roleName, String uid, String text,
			String messageId,Consumer<UsageIntf<?>> usageListener) {
		return generate(roleName, text, messageId, usageListener);
	}

	/**
	 * 用本地语音模型生成语音。
	 * <p>
	 * 先经过 {@link VoiceTagger} 把话语拆成带情感标注的片段，再交给本地语音模型合成。
	 * </p>
	 *
	 * @param roleName      说话人（角色）名称
	 * @param text          要朗读的话语内容
	 * @param messageId     请求唯一 ID
	 * @param usageListener 用量接收方，可为 null
	 * @return 生成的音频；说话人不存在时异常完成
	 */
	public CompletableFuture<ModelGenerationResult> generate(String roleName, String text, String messageId,
			Consumer<UsageIntf<?>> usageListener) {
		// 中间必须经过 VoiceTagger：分句 + 情感标注后再合成
		return extractTalk(roleName, text, usageListener)
				.thenCompose(content -> synthesizeSegments(roleName, content, messageId, usageListener));
	}

	/**
	 * 只执行朗读链路的第一阶段：分句 + 情感标注。
	 * <p>
	 * 供 OpenAI 兼容 TTS 接口使用：这一阶段失败属于“请求级”错误，调用方可以同步等待并返回
	 * 正确的 HTTP 状态码；随后的本地合成长则交给异步阶段。
	 * </p>
	 *
	 * @param roleName      说话人（角色）名称
	 * @param text          要朗读的话语内容
	 * @param usageListener 用量接收方，可为 null
	 * @return 带情感标注的分段数组；说话人不存在时异常完成
	 */
	public CompletableFuture<JsonArray> extractTalk(String roleName, String text,
			Consumer<UsageIntf<?>> usageListener) {
		return extractTalk(roleName, text, null, usageListener);
	}

	/**
	 * 只执行朗读链路的第一阶段，可携带朗读风格提示（OpenAI 兼容接口的 {@code instructions}）。
	 *
	 * @param roleName      说话人（角色）名称
	 * @param text          要朗读的话语内容
	 * @param styleHint     朗读风格提示，可为 null；只影响情感标注
	 * @param usageListener 用量接收方，可为 null
	 * @return 带情感标注的分段数组；说话人不存在时异常完成
	 */
	public CompletableFuture<JsonArray> extractTalk(String roleName, String text, String styleHint,
			Consumer<UsageIntf<?>> usageListener) {
		if (botids.get(roleName) == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("未知的本地语音说话人：" + roleName));
		}
		Consumer<UsageIntf<?>> listener = usageListener == null ? usage -> {
		} : usageListener;
		return vt.extractTalkContent(roleName, text, styleHint, listener);
	}

	/**
	 * 只执行朗读链路的第二阶段：把已标注的分段交给本地语音模型合成。
	 *
	 * @param roleName      说话人（角色）名称
	 * @param content       带情感标注的分段数组
	 * @param messageId     请求唯一 ID
	 * @param usageListener 用量接收方，可为 null
	 * @return 生成的音频；说话人不存在时异常完成
	 */
	public CompletableFuture<ModelGenerationResult> synthesizeSegments(String roleName, JsonArray content,
			String messageId, Consumer<UsageIntf<?>> usageListener) {
		String botid = botids.get(roleName);
		if (botid == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("未知的本地语音说话人：" + roleName));
		}
		Consumer<UsageIntf<?>> listener = usageListener == null ? usage -> {
		} : usageListener;
		return LocalModel.requireAudio(botid, messageId, content, listener);
	}

	/**
	 * 本地语音配置的全部说话人（角色名称），按名称排序。
	 *
	 * @return 说话人名称列表，永不为 null
	 */
	public List<String> getSpeakerNames() {
		List<String> names = new ArrayList<>(botids.keySet());
		Collections.sort(names);
		return names;
	}

	/**
	 * 某个说话人对应的本地语音模型音色 ID。
	 *
	 * @param roleName 说话人（角色）名称
	 * @return 音色 ID，不存在时返回 null
	 */
	public String getBotId(String roleName) {
		return botids.get(roleName);
	}
	@Override
	public boolean canProcessVoice(String roleName) {
		return botids.containsKey(roleName)&&LocalModel.hasOnlineService(ModelType.AUDIO);
	}
	@Override
	public boolean supportsVoice(String roleName) {
		// 只判断音色映射是否存在：本地服务是否在线由 canProcessVoice 另行判断
		return botids.containsKey(roleName);
	}
	@Override
	public boolean isHinted(String modelName) {
		return "gptsovits".equals(modelName);
	}

}
