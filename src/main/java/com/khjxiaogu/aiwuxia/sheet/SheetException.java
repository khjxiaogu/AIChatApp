package com.khjxiaogu.aiwuxia.sheet;

import java.io.IOException;

/**
 * 表格 API 调用失败。
 * <p>
 * 同时携带厂商业务返回码与 HTTP 状态码，便于调用方区分「参数错误 / 权限不足 / 限频 / 服务端错误」。
 * 网络层异常（连接失败、超时、响应无法解析）也会包装成该异常抛出，并保留原始异常为 {@code cause}。
 * </p>
 */
public class SheetException extends IOException {
	private static final long serialVersionUID = 1L;

	/** 厂商业务返回码；0 表示没有业务错误码。 */
	private final int code;
	/** HTTP 状态码；-1 表示没有拿到 HTTP 响应（连接失败、请求未发出等）。 */
	private final int httpStatus;

	public SheetException(String message) {
		this(message, 0, -1, null);
	}

	public SheetException(String message, Throwable cause) {
		this(message, 0, -1, cause);
	}

	public SheetException(String message, int code, int httpStatus, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.httpStatus = httpStatus;
	}

	/**
	 * @return 厂商业务返回码，0 表示没有
	 */
	public int getCode() {
		return code;
	}

	/**
	 * @return HTTP 状态码，-1 表示没有拿到响应
	 */
	public int getHttpStatus() {
		return httpStatus;
	}
}
