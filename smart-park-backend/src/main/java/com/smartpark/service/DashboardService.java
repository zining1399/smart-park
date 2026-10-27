package com.smartpark.service;

import java.util.List;
import java.util.Map;

public interface DashboardService {

    /** 汇总卡片：todayAccessCount / visitorOnSite / materialOut / materialOverdue / unhandledAlarm */
    Map<String, Object> summary();

    /** 近 7 天通行趋势：{ dates, inCounts, outCounts } */
    Map<String, Object> accessTrend();

    /** 物资状态分布：[{ status, count }] */
    List<Map<String, Object>> materialStatus();
}
