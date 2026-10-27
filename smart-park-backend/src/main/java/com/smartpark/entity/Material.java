package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("material")
public class Material {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一物一码，唯一 */
    private String code;

    private String name;

    /** 工具 / 备件 / 车辆 */
    private String category;

    private String spec;

    private String dept;

    private String location;

    /** 是否贵重（出库需审批） */
    private Boolean valuable;

    /** 在库 / 在借 / 逾期 / 维修中 */
    private String status;
}
