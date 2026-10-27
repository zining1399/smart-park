package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("vehicle")
public class Vehicle {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车牌号，唯一 */
    private String plateNo;

    /** 员工 / 访客 / 仓储 */
    private String ownerType;

    private String ownerName;

    private String dept;

    /** 正常 / 黑名单 */
    private String status;
}
