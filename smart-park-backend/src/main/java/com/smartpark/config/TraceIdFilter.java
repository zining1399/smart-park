package com.smartpark.config;

import com.smartpark.common.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器（MDC key 固定为 traceId，与 logback %X{traceId} 对齐）。
 * 请求方向接受 X-Trace-Id（格式校验防日志注入），响应方向回写；最高优先级保证 MDC 早于日志与鉴权过滤器就位。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_PATTERN = "[0-9a-f]{8,64}";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolve(request);
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程池复用：不清会把上一个请求的 traceId 串进下一个请求的日志
            MDC.clear();
        }
    }

    private String resolve(HttpServletRequest request) {
        String incoming = request.getHeader(TraceIds.HEADER);
        if (StringUtils.hasText(incoming) && incoming.matches(TRACE_PATTERN)) {
            return incoming;
        }
        if (StringUtils.hasText(incoming)) {
            // 脏输入不拒绝业务请求，仅记录（截断防借日志行注入）
            log.warn("非法 X-Trace-Id 已忽略：{}", incoming.substring(0, Math.min(incoming.length(), 32)));
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
