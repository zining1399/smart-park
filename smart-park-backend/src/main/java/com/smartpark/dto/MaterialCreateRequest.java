package com.smartpark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增物资：status 由服务端固定为「在库」，不接收外部传入 */
@Data
public class MaterialCreateRequest {

    @NotBlank(message = "物资编码不能为空")
    @Size(max = 64, message = "物资编码长度不能超过 64")
    private String code;

    @NotBlank(message = "物资名称不能为空")
    @Size(max = 64, message = "物资名称长度不能超过 64")
    private String name;

    @Pattern(regexp = ValidationPatterns.MATERIAL_CATEGORY, message = "category 仅支持 工具 / 备件 / 车辆")
    private String category;

    @Size(max = 128, message = "规格型号长度不能超过 128")
    private String spec;

    @Size(max = 64, message = "责任部门长度不能超过 64")
    private String dept;

    @Size(max = 64, message = "存放位置长度不能超过 64")
    private String location;

    private Boolean valuable;
}
