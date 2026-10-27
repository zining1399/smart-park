package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("alarm")
public class Alarm {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 门禁异常 / 访客滞留 / 物资逾期 / 黑名单 */
    private String type;

    private String title;

    private String content;

    /** 提示 / 警告 / 严重 */
    private String level;

    /** 未处理 / 已处理 */
    private String status;

    private String createTime;

    private String handleTime;
}
