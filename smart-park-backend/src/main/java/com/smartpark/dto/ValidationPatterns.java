package com.smartpark.dto;

/**
 * 校验正则常量（注解属性需编译期常量，故集中定义）：
 * <ul>
 *   <li>时间：全库时间按定长字符串比较，格式必须严格统一，否则字典序比较全错（B06）</li>
 *   <li>枚举：MySQL 5.7 下 CHECK 约束会静默失效，应用层 @Pattern 是唯一防线（S17）</li>
 * </ul>
 */
public final class ValidationPatterns {

    /** 业务时间格式（定长，可直接字符串比较） */
    public static final String DATETIME = "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$";

    public static final String DATETIME_MSG = "时间格式必须为 yyyy-MM-dd HH:mm:ss";

    // ---- 状态枚举（严格大小写，不加 (?i)：与业务键大写归一化构成一致的大小写防线）----
    public static final String EMPLOYEE_STATUS = "在职|离职";
    public static final String VEHICLE_STATUS = "正常|黑名单";
    public static final String VEHICLE_OWNER_TYPE = "员工|访客|仓储";
    public static final String MATERIAL_STATUS = "在库|在借|逾期|维修中";
    public static final String MATERIAL_CATEGORY = "工具|备件|车辆";
    public static final String AREA = "办公区|生产区|仓储区|生活区";
    public static final String TARGET_TYPE = "PERSON|VEHICLE";

    private ValidationPatterns() {
    }
}
