package com.smartpark.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改物资：code 与 status 均不可由外部修改（code 被流水引用，status 由出入库流程维护） */
@Data
public class MaterialUpdateRequest {

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
