package com.smartpark.common;

import lombok.Data;

/**
 * 统一返回体：code=0 成功，非 0 为业务/系统错误（取值见 {@link ErrorCodes}）。
 * traceId 用于前后端日志关联排查（由 TraceIdFilter 写入 MDC 后回填）。
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(ErrorCodes.OK);
        r.setMessage("ok");
        r.setData(data);
        r.setTraceId(TraceIds.current());
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(String message) {
        return error(ErrorCodes.PARAM_ERROR, message);
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        r.setTraceId(TraceIds.current());
        return r;
    }
}
