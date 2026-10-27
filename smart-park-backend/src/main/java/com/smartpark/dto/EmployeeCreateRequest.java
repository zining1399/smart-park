package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增员工（不含状态机字段之外的内部字段；status 允许管理端设置，故收并校验）。
 */
@Data
public class EmployeeCreateRequest {

    @NotBlank(message = "工号不能为空")
    @Size(max = 32, message = "工号长度不能超过 32")
    private String empNo;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String name;

    @Size(max = 64, message = "部门长度不能超过 64")
    private String dept;

    @Size(max = 32, message = "手机号长度不能超过 32")
    private String phone;

    @Pattern(regexp = ValidationPatterns.EMPLOYEE_STATUS, message = "status 仅支持 在职 / 离职")
    private String status;
}
