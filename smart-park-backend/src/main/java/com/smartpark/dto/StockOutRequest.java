package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 物资申领出库请求。
 */
@Data
public class StockOutRequest {

    @NotBlank(message = "materialCode 不能为空")
    private String materialCode;

    @NotBlank(message = "borrower 不能为空")
    private String borrower;

    @NotBlank(message = "dept 不能为空")
    private String dept;

    @NotBlank(message = "expectReturnTime 不能为空")
    @Pattern(regexp = ValidationPatterns.DATETIME, message = ValidationPatterns.DATETIME_MSG)
    private String expectReturnTime;

    private String remark;
}
