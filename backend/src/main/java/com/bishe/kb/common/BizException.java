package com.bishe.kb.common;

/**
 * 业务异常：由 GlobalExceptionHandler 统一转成 {@link Result}，controller 不需要 try-catch。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(4000, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
