package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 物资入库请求。
 */
@Data
public class StockInRequest {

    @NotBlank(message = "materialCode 不能为空")
    private String materialCode;

    private String remark;
}
