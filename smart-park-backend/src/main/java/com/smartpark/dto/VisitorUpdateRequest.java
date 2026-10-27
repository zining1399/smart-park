package com.smartpark.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改访客：status / checkInTime / checkOutTime 不接收（状态流转只能走 check-in / check-out / 定时任务）。
 * idCard / phone 若传入的是掩码值（含 *），后端将忽略该字段（B05 三层防护之一）。
 */
@Data
public class VisitorUpdateRequest {

    @Size(max = 64, message = "访客姓名长度不能超过 64")
    private String visitorName;

    @Size(max = 64, message = "身份证长度不能超过 64")
    private String idCard;

    @Size(max = 64, message = "手机号长度不能超过 64")
    private String phone;

    @Size(max = 32, message = "车牌号长度不能超过 32")
    private String plateNo;

    @Size(max = 64, message = "被访人长度不能超过 64")
    private String hostName;

    @Size(max = 64, message = "被访部门长度不能超过 64")
    private String hostDept;

    @Size(max = 255, message = "来访事由长度不能超过 255")
    private String reason;

    @Pattern(regexp = ValidationPatterns.AREA, message = "area 仅支持 办公区 / 生产区 / 仓储区 / 生活区")
    private String area;

    @Pattern(regexp = ValidationPatterns.DATETIME, message = ValidationPatterns.DATETIME_MSG)
    private String appointTime;

    @Pattern(regexp = ValidationPatterns.DATETIME, message = ValidationPatterns.DATETIME_MSG)
    private String expireTime;
}
