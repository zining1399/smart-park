package com.smartpark.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改员工：工号为业务唯一键，不可修改（从入参移除，服务端以库中值为准）。
 */
@Data
public class EmployeeUpdateRequest {

    @Size(max = 64, message = "姓名长度不能超过 64")
    private String name;

    @Size(max = 64, message = "部门长度不能超过 64")
    private String dept;

    @Size(max = 32, message = "手机号长度不能超过 32")
    private String phone;

    @Pattern(regexp = ValidationPatterns.EMPLOYEE_STATUS, message = "status 仅支持 在职 / 离职")
    private String status;
}
