package com.smartpark.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改车辆：车牌号为业务唯一键，不可修改（被通行权限与流水引用） */
@Data
public class VehicleUpdateRequest {

    @Pattern(regexp = ValidationPatterns.VEHICLE_OWNER_TYPE, message = "ownerType 仅支持 员工 / 访客 / 仓储")
    private String ownerType;

    @Size(max = 64, message = "车主姓名长度不能超过 64")
    private String ownerName;

    @Size(max = 64, message = "归属部门长度不能超过 64")
    private String dept;

    @Pattern(regexp = ValidationPatterns.VEHICLE_STATUS, message = "status 仅支持 正常 / 黑名单")
    private String status;
}
