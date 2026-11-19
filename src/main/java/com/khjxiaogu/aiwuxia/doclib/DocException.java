package com.khjxiaogu.aiwuxia.doclib;

/**
 * 文档库业务异常：带一个稳定的错误码，工具层直接把它格式化成 {@code [CODE] message} 返回给模型。
 */
public class DocException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 稳定错误码，例如 DOC_STALE / DOC_LOCKED。 */
    public final String code;

    public DocException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DocException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
