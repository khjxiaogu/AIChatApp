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

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
/**
 * 本地部署模型服务对象，用于管理与本地模型服务的 WebSocket 连接。
 * 该类实现了 {@link WebsocketEvents} 接口，处理 WebSocket 连接的生命周期事件，
 * 并提供向服务端发送请求并等待响应的能力。主要用于音频生成等需要实时响应的场景。
 * 内部维护一个连接池，支持多路复用；每个请求通过唯一的请求 ID 关联，并支持超时等待。
 */
public class LocalModelHandshaker implements WebsocketEvents {

    /**
     * 内部类，用于封装异步请求的结果和完成状态。
     */
    private static class Result {
        /** 标记请求是否已完成（无论成功或失败） */
        boolean finished;
        /** 响应数据的字节数组（Base64 解码后的原始数据） */
        byte[] data;
    }

    /** 可用的 WebSocket 连接池，使用阻塞队列实现线程安全 */
    private final BlockingQueue<Channel> pool = new LinkedBlockingQueue<>();
    /** 请求 ID 到 Result 对象的映射，用于在收到响应时唤醒等待线程 */
    private final Map<String, Result> ars = new ConcurrentHashMap<>();

    /**
     * 构造一个空的本地模型握手器，初始化连接池和请求映射。
     */
    public LocalModelHandshaker() {
        super();
    }

    /** 当前活跃的连接数量（仅用于统计） */
    int num = 0;

    /**
     * 当新的 WebSocket 连接建立时调用。
     * 将连接加入连接池，并增加活跃连接计数。
     *
     * @param conn       建立的 WebSocket 通道
     * @param handshake  完整的 HTTP 握手请求
     */
    @Override
    public void onOpen(Channel conn, FullHttpRequest handshake) {
        this.pool.offer(conn);
        num++;
    }

    /**
     * 当 WebSocket 连接关闭时调用。
     * 从连接池中移除该连接，并减少活跃连接计数。
     *
     * @param conn 关闭的 WebSocket 通道
     */
    @Override
    public void onClose(Channel conn) {
        pool.remove(conn);
        num--;
    }

    /**
     * 当接收到 WebSocket 文本消息时调用。
     * 解析 JSON 消息，提取请求 ID 和数据，唤醒对应等待线程。
     *
     * @param conn    接收消息的 WebSocket 通道
     * @param message 接收到的文本消息（JSON 格式）
     */
    @Override
    public void onMessage(Channel conn, String message) {
        JsonObject jo = JsonParser.parseString(message).getAsJsonObject();
        Result ar = ars.remove(jo.get("reqid").getAsString());
        System.out.println("received " + message + ar);
        if (ar != null) {
            synchronized (ar) {
                if (!jo.has("error")) {
                    // 数据为 Base64 编码，需要解码为原始字节数组
                    ar.data = Base64.getDecoder().decode(jo.get("data").getAsString());
                }
                ar.finished = true;
                ar.notifyAll();
            }
        }
    }

    /**
     * 处理直接传入的字节数据（可能来自本地或其他来源）。
     * 此方法通常不用于 WebSocket 接收，而是用于本地回调。
     *
     * @param reqid 请求 ID
     * @param data  响应的字节数据
     */
    public void onMessage(String reqid, byte[] data) {
        Result ar = ars.remove(reqid);
        if (ar != null) {
            ar.data = data;
            ar.finished = true;
            // 注意：此处未调用 notifyAll，可能意味着此方法用于同步或已不再需要等待
        }
    }

    /**
     * 检查是否有在线服务（即是否有可用的 WebSocket 连接）。
     *
     * @return 如果至少有一个活跃连接则返回 true，否则 false
     */
    public boolean hasOnlineService() {
        return num > 0;
    }

    /**
     * 异步请求音频数据。
     * 从连接池中获取一个可用连接，向服务端发送音频生成请求（包含角色、表情、文本等），
     * 然后等待响应，直到超时（3 分钟）或连接断开。
     *
     * @param chara  角色标识
     * @param emote  表情标识
     * @param reqid  请求唯一 ID（用于关联响应）
     * @param text   要合成的文本内容
     * @return 包含音频字节数组的 {@link CompletableFuture}，如果失败或超时则返回 null
     */
    public CompletableFuture<byte[]> requireAudio(String chara, String emote, String reqid, String text) {
        if (pool.isEmpty())
            return CompletableFuture.completedFuture(null);

        JsonObject request = JsonBuilder.object()
                .add("chara", chara)
                .add("emote", emote)
                .add("reqid", reqid)
                .add("text", text)
                .add("type", "audiorequest")
                .end();

        return CompletableFuture.supplyAsync(() -> {
            while (true) {
                if (pool.isEmpty())
                    return null;
                Channel ch = null;
                try {
                    // 从池中获取一个连接，最多等待 5 秒
                    ch = pool.poll(5, TimeUnit.SECONDS);
                    if (ch == null) continue; // 超时未获取到连接，重试

                    Result result = new Result();
                    ars.put(reqid, result);
                    ch.writeAndFlush(new TextWebSocketFrame(request.toString()));

                    synchronized (result) {
                        long beginTime = System.currentTimeMillis();
                        long endTime = beginTime + 1000 * 60 * 3; // 3 分钟超时
                        while (true) {
                            long currTime = System.currentTimeMillis();
                            if (!ch.isActive() || currTime >= endTime)
                                return null; // 连接断开或超时
                            if (result.finished) {
                                return result.data; // 成功接收到数据
                            }
                            // 等待剩余时间，或被唤醒
                            result.wait(endTime - currTime);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (ch != null)
                        pool.offer(ch); // 将连接归还池中
                }
            }
        });
    }
}