package com.smartpark.common;

import org.slf4j.MDC;

/**
 * traceId 读写工具：MDC key 固定为 {@code traceId}（与 logback pattern %X{traceId} 严格对齐）。
 */
public final class TraceIds {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIds() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
