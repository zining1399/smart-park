package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("access_permission")
public class AccessPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** PERSON / VEHICLE */
    private String targetType;

    /** 工号或车牌号 */
    private String targetNo;

    /** 姓名或车牌（冗余展示用） */
    private String targetName;

    /** 办公区/生产区/仓储区/生活区 */
    private String area;

    private String startTime;

    private String endTime;

    /** 生效 / 临期 / 失效（服务端按时间计算） */
    private String status;
}
