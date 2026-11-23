package com.khjxiaogu.aiwuxia.modelproxy.transport;

import java.net.URI;
import java.util.Map;

/**
 * 默认的 WebSocket 传输工厂。
 */
public final class WebSocketTransportFactory implements LocalModelTransportFactory {

	/** 全局默认实例。 */
	public static final WebSocketTransportFactory INSTANCE = new WebSocketTransportFactory();

	@Override
	public LocalModelTransport create(URI uri, Map<String, String> headers) {
		return new WebSocketLocalModelTransport(uri, headers);
	}
}
