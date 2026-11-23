package com.khjxiaogu.aiwuxia.modelproxy;

import java.io.IOException;

import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;

/**
 * 客户端附件拉取器：本地模型接收端按需把附件字节从服务器取回来。
 * <p>
 * 调用参数里只带附件 id，不内联 Base64；处理器在真正需要字节时调用
 * {@link #fetch(String, AttachmentRef)}，实现类负责缓存与重试。
 * </p>
 */
@FunctionalInterface
public interface AttachmentFetcher {

	/**
	 * 拉取附件内容。
	 *
	 * @param reqid 请求 ID（与附件作用域绑定）
	 * @param ref   附件引用
	 * @return 附件字节
	 * @throws IOException 拉取失败
	 */
	byte[] fetch(String reqid, AttachmentRef ref) throws IOException;
}
