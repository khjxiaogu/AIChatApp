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
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
/**
 * 本地部署模型的交互器，提供对本地模型服务的静态访问入口。
 * 该类封装了 {@link LocalModelHandshaker} 的单例实例，并通过静态方法
 * 暴露其核心功能，便于应用程序其他部分调用。
 * 支持多种模型类型的调用，包括音频合成等。
 */
public class LocalModel {

    /** 单例的本地模型握手器实例，负责管理与本地模型服务的 WebSocket 连接 */
    public static final LocalModelHandshaker lhs = new LocalModelHandshaker();

    /**
     * 检查是否有支持指定模型类型的在线服务。
     *
     * @param modelType 模型类型
     * @return 如果至少有一个支持该类型的活跃连接则返回 true
     */
    public static boolean hasOnlineService(ModelType modelType) {
        return lhs.hasOnlineService(modelType);
    }

    /**
     * 检查指定用户是否能用上支持该模型类型的本地服务。
     * <p>
     * 用户自己接入的本地模型优先，用户没有可用连接时回退到服务器共享连接池。
     * 调用 {@link #require(String, ModelType, String, JsonObject)} 时按同样的顺序取用。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（只检查共享连接池）
     * @param modelType 模型类型
     * @return 用户专属连接或共享连接任一在线时返回 true
     */
    public static boolean hasOnlineService(String uid, ModelType modelType) {
        return lhs.hasOnlineService(uid, modelType);
    }

    /**
     * 判断指定用户是否有自己接入的、支持该模型类型的在线连接。
     *
     * @param uid       用户标识
     * @param modelType 模型类型
     * @return 用户专属连接在线时返回 true
     */
    public static boolean hasUserService(String uid, ModelType modelType) {
        return lhs.hasUserService(uid, modelType);
    }

    /**
     * 检查是否有任何在线可用的连接。
     *
     * @return 如果至少有一个活跃的 WebSocket 连接则返回 true
     */
    public static boolean hasOnlineService() {
        return lhs.hasOnlineService();
    }

    /**
     * 通用模型请求方法。指定模型类型，由系统选择合适的连接发送请求。
     *
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @return 包含响应数据的 CompletableFuture
     */
    public static CompletableFuture<ModelGenerationResult> require(ModelType modelType, String reqid, JsonObject request) {
        return lhs.require(modelType, reqid, request);
    }

    /**
     * 通用模型请求方法，支持按用户取用本地模型。
     * <p>
     * 用户自己接入的本地模型优先；用户没有在线连接时回退到共享连接池。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（只使用共享连接池）
     * @param modelType 请求的模型类型
     * @param reqid     请求唯一 ID
     * @param request   完整的请求 JSON 对象
     * @return 包含响应数据的 CompletableFuture
     */
    public static CompletableFuture<ModelGenerationResult> require(String uid, ModelType modelType, String reqid,
            JsonObject request) {
        return lhs.require(uid, modelType, reqid, request);
    }

    /**
     * 异步请求音频数据。
     *
     * @param chara  角色标识
     * @param reqid  请求唯一标识符
     * @param content 要合成语音的文本内容数组
     * @param usageListener 用量回调
     * @return 包含音频字节数组的 CompletableFuture
     */
    public static CompletableFuture<ModelGenerationResult> requireAudio(String chara, String reqid, JsonArray content, Consumer<UsageIntf<?>> usageListener) {
        return lhs.requireAudio(chara, reqid, content, usageListener);
    }

    /**
     * 检查是否有支持 SVS（歌声合成）模型类型的在线服务。
     *
     * @return 如果至少有一个支持 SVS 的活跃连接则返回 true
     */
    public static boolean hasOnlineSvsService() {
        return lhs.hasOnlineService(ModelType.SVS);
    }

    /**
     * 异步请求 SVS（歌声合成）音频数据。
     *
     * @param reqid   请求唯一标识符
     * @param url     音源地址
     * @param options SVS 参数，见 {@link LocalModelHandshaker.SvsOptions}
     * @return 包含音频字节数组的 CompletableFuture
     */
    public static CompletableFuture<ModelGenerationResult> requireSvs(String reqid, String url, LocalModelHandshaker.SvsOptions options) {
        return lhs.requireSvs(reqid, url, options);
    }

    /**
     * 异步请求 SVS（歌声合成）音频数据。
     *
     * @param reqid   请求唯一标识符
     * @param url     音源地址
     * @param name    音源名称，可为 null
     * @param options SVS 参数，见 {@link LocalModelHandshaker.SvsOptions}
     * @return 包含音频字节数组的 CompletableFuture
     */
    public static CompletableFuture<ModelGenerationResult> requireSvs(String reqid, String url, String name, LocalModelHandshaker.SvsOptions options) {
        return lhs.requireSvs(reqid, url, name, options);
    }

    public static CompletableFuture<ModelGenerationResult> registerTask(String reqid) {
        return lhs.registerTask(reqid);
    }
}
