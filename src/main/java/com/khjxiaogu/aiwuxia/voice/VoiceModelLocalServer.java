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

import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
import com.khjxiaogu.webserver.annotations.Query;
import com.khjxiaogu.webserver.web.AbstractServiceClass;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.DataIn;
/**
 * 本地部署模型服务器
 * 私有部署通过 websocket client 提供模型调用服务，服务器通过 websocket 发布任务，
 * 并通过 HTTP POST 接收模型产出，该服务对于调用者完全无感。
 * 该网络模型可以方便于穿透 NAT、动态 IP 等网络而不需要公网映射，能极大方便部署，
 * 也方便与 Python 的调用。
 *
 * 该类继承自 {@link AbstractServiceClass}，提供了两个 HTTP 端点：
 * <ul>
 *   <li>{@code /kh$localModelDeploy} - WebSocket 端点，用于建立长连接。</li>
 *   <li>{@code /kh$localModelDeployData} - HTTP POST 端点，用于接收模型产出的数据。</li>
 * </ul>
 */
public class VoiceModelLocalServer extends AbstractServiceClass {

    /**
     * WebSocket 端点，用于与本地模型服务建立长连接。
     * 此方法将传入的请求和响应对象与 {@link LocalVoiceModel#lhs} 握手器绑定，
     * 从而将 WebSocket 连接交由 {@link LocalModelHandshaker} 处理。
     *
     * @param req HTTP 请求对象（包含握手信息）
     * @param res HTTP 响应对象，用于升级为 WebSocket 连接
     */
    @HttpPath("/kh$localModelDeploy")
    public void voiceWebSocket(Request req, Response res) {
        res.suscribeWebsocketEvents(LocalVoiceModel.lhs);
    }

    /**
     * HTTP POST 端点，用于接收本地模型产出的数据（如音频字节数组）。
     * 该端点通常由模型服务在生成结果后调用，将数据通过 HTTP 推送给服务器。
     * 接收到的数据会通过 {@link LocalVoiceModel#lhs} 的 {@code onMessage} 方法传递给等待的请求。
     *
     * @param reqid 请求唯一标识符，用于关联到原始请求
     * @param type  数据类型（目前可能固定为某种类型，如音频）
     * @param data  接收到的字节数组，通过 {@link DataIn} 解码器获取
     * @return 包含 HTTP 状态码 200 的 {@link ResultDTO} 对象，表示成功接收
     */
    @HttpPath("/kh$localModelDeployData")
    @Adapter
    @HttpMethod("POST")
    public ResultDTO dataPost(@Query("reqid") String reqid,
                              @Query("type") String type,
                              @GetBy(DataIn.class) byte[] data) {
        LocalVoiceModel.lhs.onMessage(reqid, data);
        return new ResultDTO(200);
    }

    /**
     * 返回该服务的名称，用于标识或日志记录。
     *
     * @return 服务名称字符串 "LocalAudio"
     */
    @Override
    public String getName() {
        return "LocalAudio";
    }
}
