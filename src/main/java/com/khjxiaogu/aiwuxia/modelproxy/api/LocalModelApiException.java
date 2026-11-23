package com.khjxiaogu.aiwuxia.modelproxy.api;

/**
 * 本地模型调用参数不合法时统一抛出的异常。
 */
public class LocalModelApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public LocalModelApiException(String message) {
		super(message);
	}

	public LocalModelApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
