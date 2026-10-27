package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("stock_record")
public class StockRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String materialCode;

    private String materialName;

    /** IN / OUT（裁决 15：RETURN 为死枚举，归还只更新原 OUT 单的 returnTime，不新增记录） */
    private String type;

    /** 领用人（IN 时可空） */
    private String borrower;

    /** 领用部门 */
    private String dept;

    /** 免审 / 待审批 / 已批准 / 已驳回 */
    private String approveStatus;

    private String expectReturnTime;

    private String outTime;

    private String returnTime;

    private String createTime;

    private String remark;
}
