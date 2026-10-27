package com.smartpark.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具：全工程统一 "yyyy-MM-dd HH:mm:ss" 字符串。
 * 定长格式可直接按字符串比较先后。
 */
public final class DateTimes {

    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateTimes() {
    }

    public static String now() {
        return LocalDateTime.now().format(FMT);
    }

    public static String plusDays(int days) {
        return LocalDateTime.now().plusDays(days).format(FMT);
    }

    public static String today() {
        return LocalDate.now().format(DAY);
    }
}
