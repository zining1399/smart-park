package com.smartpark.common;

/**
 * 分页参数服务端收敛：防止 pageSize 过大打爆内存（S08）。
 */
public final class PageSupport {

    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 200;

    private PageSupport() {
    }

    public static int clampPageNum(Integer pageNum) {
        if (pageNum == null || pageNum < 1) {
            return DEFAULT_PAGE_NUM;
        }
        return pageNum;
    }

    public static int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
