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
package com.khjxiaogu.aiwuxia.voice;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class VoiceModelHandler {
	private VoiceModelHandler() {
	}
	private static List<VoiceModel> models;
	/**
	 * 调用音频大模型生成音频数据。
	 * <p>
	 * 根据指定的音色标识、调用用户标识、输入文本和请求消息全局唯一ID，
	 * 调用音频大模型进行语音合成，将文本转换为音频，并返回生成的音频字节数组。
	 * </p>
	 * 
	 * @param roleName 音色标识，用于指定语音合成使用的音色
	 * @param uid 调用用户的唯一标识，用于标识发起请求的用户
	 * @param text 待合成的文本内容，即需要转换为音频的文字
	 * @param messageId 请求消息的全局唯一ID，用于追踪和唯一标识本次音频合成请求
	 * @return 生成的音频数据，以字节数组(byte[])形式返回，格式一般为mp3。
	 * @throws IOException 当调用音频大模型服务过程中发生输入输出异常时抛出，如网络连接失败、服务响应异常等
	 */
	public static CompletableFuture<ModelGenerationResult> getAudioData(String modelHint,String roleName, String uid, String text, String messageId, Consumer<UsageIntf<?>> usageListener){
		return models.stream()
    	        .filter(p -> p.isHinted(modelHint))
    	        .filter(p -> p.canProcessVoice(roleName))
    	        .findFirst()
    	        .or(()->models.stream()
    	                .filter(p -> p.canProcessVoice(roleName))
    	                .findFirst()).orElseThrow(() -> new ModelRouteException("No voice model found for request: " + roleName))
    	        .getAudioData(roleName, uid, text, messageId,usageListener);
	};
	public static boolean hasSupportedAudio(String roleName){
		return models.stream()
    	                .filter(p -> p.canProcessVoice(roleName))
    	                .findAny().isPresent();
	};
	public static void init(File dataFolder) throws JsonSyntaxException, IOException {
		models=new ArrayList<>();
		if(System.getProperty("localVoiceToken")!=null)
			models.add(new LocalVoiceApi(dataFolder));
		if(System.getProperty("mimotoken")!=null)
			models.add(new MimoVoiceApi(dataFolder));
		if(System.getProperty("volcsecret")!=null)
			models.add(new VolcanoVoiceApi(dataFolder));
	}
}
