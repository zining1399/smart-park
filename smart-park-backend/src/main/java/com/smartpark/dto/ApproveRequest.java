package com.smartpark.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 出库审批请求。
 */
@Data
public class ApproveRequest {

    @NotNull(message = "approved 不能为空")
    private Boolean approved;
}
