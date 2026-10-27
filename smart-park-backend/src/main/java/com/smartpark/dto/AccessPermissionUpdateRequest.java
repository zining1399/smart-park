package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改通行权限：status 由服务端按有效期计算 */
@Data
public class AccessPermissionUpdateRequest {

    @NotBlank(message = "targetType 不能为空")
    @Pattern(regexp = ValidationPatterns.TARGET_TYPE, message = "targetType 仅支持 PERSON / VEHICLE")
    private String targetType;

    @NotBlank(message = "targetNo 不能为空")
    @Size(max = 32, message = "targetNo 长度不能超过 32")
    private String targetNo;

    @Size(max = 64, message = "targetName 长度不能超过 64")
    private String targetName;

    @NotBlank(message = "area 不能为空")
    @Pattern(regexp = ValidationPatterns.AREA, message = "area 仅支持 办公区 / 生产区 / 仓储区 / 生活区")
    private String area;

    @NotBlank(message = "startTime 不能为空")
    @Pattern(regexp = ValidationPatterns.DATETIME, message = ValidationPatterns.DATETIME_MSG)
    private String startTime;

    @NotBlank(message = "endTime 不能为空")
    @Pattern(regexp = ValidationPatterns.DATETIME, message = ValidationPatterns.DATETIME_MSG)
    private String endTime;
}
