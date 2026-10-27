package com.smartpark.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一返回 {code, message, data, traceId} 结构，并携带真实 HTTP 状态码。
 * B07 修复：异常详情只进服务端日志，响应仅给面向用户的中文文案，不再透传 e.getMessage()。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return build(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验失败：{}", msg);
        return build(ErrorCodes.PARAM_ERROR, msg);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return build(ErrorCodes.PARAM_ERROR, "请求参数不合法");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return build(ErrorCodes.NOT_FOUND, "资源不存在");
    }

    /**
     * S02：MySQL ERROR 1062 唯一键冲突 → 409 + 按约束名给出业务文案，不再落到 500 "系统异常"。
     * 注意 sys_user 的 uk_sys_user_username 在 ci 排序规则下大小写不敏感，Admin/admin 也走这条。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突：{}", e.getMessage());
        return build(ErrorCodes.CONFLICT, duplicateMessage(e.getMessage()));
    }

    static String duplicateMessage(String raw) {
        String constraint = null;
        if (raw != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("for key '[^']*\\.([^']+)'").matcher(raw);
            if (m.find()) {
                constraint = m.group(1);
            }
        }
        if (constraint == null) {
            return "数据已存在，请勿重复提交";
        }
        return switch (constraint) {
            case "uk_employee_emp_no" -> "工号已存在，请勿重复提交";
            case "uk_vehicle_plate_no" -> "车牌号已存在，请勿重复提交";
            case "uk_material_code" -> "物资编码已存在，请勿重复提交";
            case "uk_sys_user_username" -> "登录名已存在（不区分大小写），请更换";
            case "uk_alarm_dedup_key" -> "该告警已存在（去重键冲突），无需重复上报";
            default -> "数据已存在，请勿重复提交";
        };
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        return build(ErrorCodes.FORBIDDEN, "无操作权限");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return build(ErrorCodes.SYSTEM_ERROR, "系统繁忙，请稍后重试");
    }

    private ResponseEntity<Result<Void>> build(int code, String message) {
        return ResponseEntity.status(httpStatusOf(code)).body(Result.error(code, message));
    }

    static HttpStatus httpStatusOf(int code) {
        return switch (code) {
            case ErrorCodes.UNAUTHORIZED, ErrorCodes.LOGIN_FAILED, ErrorCodes.ACCOUNT_DISABLED -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.CONFLICT -> HttpStatus.CONFLICT;
            case ErrorCodes.SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
