package com.khjxiaogu.aiwuxia.state.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * WebSocket 心跳（ping/pong）支持。
 * <p>
 * 浏览器定期发送 {@code {"type":"ping","id":"<唯一id>"}}，服务端必须把同一个 id 原样回送，
 * 客户端据此判断连接是否仍然可用（半开连接不会触发 {@code onclose}，只能靠心跳发现）。
 * </p>
 * <p>
 * 各 WebSocket 会话在收到消息时统一调用 {@link #replyPongIfPing(Channel, JsonObject)}：
 * 如果是心跳则立即应答并返回 {@code true}，调用方直接结束本次处理即可。
 * </p>
 *
 * @see PainterSession
 * @see WebSocketAISession
 */
public final class Heartbeat {

	/** 心跳请求类型 */
	public static final String PING = "ping";

	/** 心跳应答类型 */
	public static final String PONG = "pong";

	private Heartbeat() {
	}

	/**
	 * 判断该消息是否为心跳请求，如果是则向发起请求的连接回送同 id 的 pong。
	 * <p>
	 * pong 只发给发起 ping 的那一条连接，不广播给同一会话的其他连接。
	 * </p>
	 *
	 * @param conn    发起请求的连接
	 * @param message 收到的消息
	 * @return {@code true} 表示这是一条心跳消息并且已经应答，调用方无需再处理
	 */
	public static boolean replyPongIfPing(Channel conn, JsonObject message) {
		if (conn == null || message == null || !message.has("type")) {
			return false;
		}
		JsonElement type = message.get("type");
		if (type == null || type.isJsonNull() || !PING.equals(type.getAsString())) {
			return false;
		}
		JsonObject pong = new JsonObject();
		pong.addProperty("type", PONG);
		JsonElement id = message.get("id");
		if (id != null && !id.isJsonNull()) {
			pong.add("id", id);
		}
		conn.writeAndFlush(new TextWebSocketFrame(pong.toString()));
		return true;
	}
}
