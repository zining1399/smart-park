package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 访客预约登记：status / checkInTime / checkOutTime 为状态机字段，一律不接收外部传入。
 * area 必须校验取值（应用层唯一防线）。
 */
@Data
public class VisitorCreateRequest {

    @NotBlank(message = "访客姓名不能为空")
    @Size(max = 64, message = "访客姓名长度不能超过 64")
    private String visitorName;

    @Size(max = 64, message = "身份证长度不能超过 64")
    private String idCard;

    @NotBlank(message = "手机号不能为空")
    @Size(max = 64, message = "手机号长度不能超过 64")
    private String phone;

    @Size(max = 32, message = "车牌号长度不能超过 32")
    private String plateNo;

    @NotBlank(message = "被访人不能为空")
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
