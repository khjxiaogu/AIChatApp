package com.khjxiaogu.aiwuxia.voice;

import java.io.IOException;
/**
 * 调用音频大模型接口。
 */
public interface VoiceModel {
	/**
	 * 调用音频大模型生成音频数据。
	 * <p>
	 * 根据指定的音色标识、调用用户标识、输入文本和请求消息全局唯一ID，
	 * 调用音频大模型进行语音合成，将文本转换为音频，并返回生成的音频字节数组。
	 * </p>
	 * 
	 * @param botid 音色标识，用于指定语音合成使用的音色
	 * @param uid 调用用户的唯一标识，用于标识发起请求的用户
	 * @param text 待合成的文本内容，即需要转换为音频的文字
	 * @param messageId 请求消息的全局唯一ID，用于追踪和唯一标识本次音频合成请求
	 * @return 生成的音频数据，以字节数组(byte[])形式返回，格式一般为mp3
	 * @throws IOException 当调用音频大模型服务过程中发生输入输出异常时抛出，如网络连接失败、服务响应异常等
	 */
	byte[] getAudioData(String botid, String uid, String text, String messageId) throws IOException;

}