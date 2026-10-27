package com.smartpark.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpark.common.ErrorCodes;
import com.smartpark.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 鉴权失败响应：覆盖 Spring Security 默认空 body，统一输出 {@code {code, message}} + 真实 HTTP 状态码。
 * 40101 未登录/过期 → 401；40301 无权限 → 403。前端按 code 分流（40102/40103 由登录接口抛出，不跳转）。
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "无操作权限");
    }

    /** 供过滤器直接写出错误响应（如账号被禁用 40103） */
    public void writeError(HttpServletResponse response, HttpStatus status, int code, String message)
            throws IOException {
        write(response, status, code, message);
    }

    private void write(HttpServletResponse response, HttpStatus status, int code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.error(code, message));
    }
}
