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

import java.util.concurrent.CompletableFuture;
/**
 * 本地部署语音模型的交互器，提供对本地语音合成服务的静态访问入口。
 * 该类封装了 {@link LocalModelHandshaker} 的单例实例，并通过静态方法
 * 暴露其核心功能，便于应用程序其他部分调用。
 * 主要用于检查本地语音服务是否在线，以及异步请求音频数据。
 */
public class LocalVoiceModel {

    /** 单例的本地模型握手器实例，负责管理与本地语音服务的 WebSocket 连接 */
    public static final LocalModelHandshaker lhs = new LocalModelHandshaker();

    /**
     * 检查本地语音服务是否有在线可用的连接。
     *
     * @return 如果至少有一个活跃的 WebSocket 连接则返回 true，否则返回 false
     * @see LocalModelHandshaker#hasOnlineService()
     */
    public static boolean hasOnlineService() {
        return lhs.hasOnlineService();
    }

    /**
     * 异步请求音频数据。
     * 向本地语音服务发送合成请求，并返回一个 {@link CompletableFuture}，
     * 当音频数据准备好或发生错误/超时时，该 Future 将完成。
     *
     * @param chara  角色标识（如角色名称或 ID）
     * @param emote  表情标识（如高兴、悲伤等）
     * @param reqid  请求唯一标识符，用于关联响应
     * @param text   要合成语音的文本内容
     * @return 包含音频字节数组的 CompletableFuture；如果服务不可用或请求失败，可能返回 null
     * @see LocalModelHandshaker#requireAudio(String, String, String, String)
     */
    public static CompletableFuture<byte[]> requireAudio(String chara, String emote, String reqid, String text) {
        return lhs.requireAudio(chara, emote, reqid, text);
    }
}
