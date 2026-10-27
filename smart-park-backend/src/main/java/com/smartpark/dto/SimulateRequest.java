package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 模拟通行终端上报。
 */
@Data
public class SimulateRequest {

    @NotBlank(message = "passType 不能为空")
    @Pattern(regexp = "PERSON|VEHICLE", message = "passType 仅支持 PERSON / VEHICLE")
    private String passType;

    @NotBlank(message = "targetNo 不能为空")
    private String targetNo;

    @NotBlank(message = "gate 不能为空")
    private String gate;

    @NotBlank(message = "direction 不能为空")
    @Pattern(regexp = "IN|OUT", message = "direction 仅支持 IN / OUT")
    private String direction;
}
