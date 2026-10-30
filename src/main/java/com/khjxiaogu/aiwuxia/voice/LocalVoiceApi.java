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
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
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
		String botid = botids.get(roleName);
		if (botid == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("未知的本地语音说话人：" + roleName));
		}
		Consumer<UsageIntf<?>> listener = usageListener == null ? usage -> {
		} : usageListener;
		// 中间必须经过 VoiceTagger：分句 + 情感标注后再合成
		return vt.extractTalkContent(roleName, text, listener)
				.thenCompose(content -> LocalModel.requireAudio(botid, messageId, content, listener));
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
