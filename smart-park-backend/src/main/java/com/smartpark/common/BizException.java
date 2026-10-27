package com.smartpark.common;

import lombok.Getter;

/**
 * 业务异常，message 直接透传给前端提示（面向用户的中文文案）。
 * code 取值见 {@link ErrorCodes}，未显式指定时按参数/业务错误处理（40001）。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(ErrorCodes.PARAM_ERROR, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCodes.NOT_FOUND, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCodes.CONFLICT, message);
    }
}
