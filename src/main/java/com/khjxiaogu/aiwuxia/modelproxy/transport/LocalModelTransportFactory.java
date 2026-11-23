package com.khjxiaogu.aiwuxia.modelproxy.transport;

import java.net.URI;
import java.util.Map;

/**
 * 传输实现工厂。
 * <p>
 * 业务侧只依赖本接口，通过替换实现即可切换协议。默认使用
 * {@link WebSocketTransportFactory}。
 * </p>
 */
public interface LocalModelTransportFactory {

	/**
	 * 创建一条传输。
	 *
	 * @param uri     连接地址（含查询参数）
	 * @param headers 连接头等传输相关元数据，可为 null；非 HTTP 类协议可以忽略
	 * @return 传输实例（尚未连接）
	 */
	LocalModelTransport create(URI uri, Map<String, String> headers);
}
