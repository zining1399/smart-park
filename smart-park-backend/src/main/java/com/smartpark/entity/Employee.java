package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("employee")
public class Employee {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工号，唯一 */
    private String empNo;

    private String name;

    private String dept;

    /** 手机号密文（AES-256-GCM），接口出参一律替换为 phoneMask */
    private String phone;

    /** 手机号 HMAC-SHA256（等值检索走此列） */
    private String phoneHash;

    /** 手机号掩码（前3后4） */
    private String phoneMask;

    /** 在职 / 离职 */
    private String status;
}
