package com.smartpark.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 关键操作审计日志（终稿 S05，等保/个保法合规要求）：
 * 每条审计必带 traceId（关联同一次请求的应用日志）与操作人（关联到人）。
 * 落日志文件，不建独立审计表。
 *
 * <p><b>logger 名固定为 {@value #LOGGER_NAME}</b>（刻意不用类名的 {@code @Slf4j}）：
 * 运维侧按 <b>logger 名</b>做日志分流与 180 天留存策略，
 * 若按报文里的 "AUDIT" 文本前缀匹配，会误伤应用日志中恰好含该词的行。
 */
@Component
public class AuditLogger {

    /** 审计专用 logger 名：devops 按此名分流 + 保留 180 天 */
    public static final String LOGGER_NAME = "AUDIT";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private static final String ANONYMOUS = "anonymous";
    private static final String PREFIX = "AUDIT";

    /**
     * @param action 操作（如 LOGIN / ACCESS_SIMULATE / STOCK_OUT / APPROVE / DELETE_EMPLOYEE）
     * @param target 操作对象（如 工号 / 物资编码 / 记录 id）
     * @param result 结果（SUCCESS / FAIL / DENY / 已拦截 等）
     */
    public void audit(String action, String target, String result) {
        audit(action, target, result, null);
    }

    public void audit(String action, String target, String result, String detail) {
        log.info("{} | traceId={} | operator={} | action={} | target={} | result={} | detail={}",
                PREFIX,
                MDC.get(TraceIds.MDC_KEY),
                currentOperator(),
                action,
                target == null ? "-" : target,
                result,
                detail == null ? "-" : detail);
    }

    public static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return ANONYMOUS;
        }
        return auth.getName();
    }
}
