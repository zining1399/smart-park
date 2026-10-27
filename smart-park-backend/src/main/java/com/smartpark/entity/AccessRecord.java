package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("access_record")
public class AccessRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** PERSON / VEHICLE */
    private String passType;

    /** 工号或车牌 */
    private String targetNo;

    /** 姓名或车牌 */
    private String targetName;

    /** 东门/西门/南门/货运门 */
    private String gate;

    /** IN / OUT */
    private String direction;

    private String passTime;

    /** 放行 / 拦截 */
    private String result;

    /** 拦截原因（可空） */
    private String reason;
}
