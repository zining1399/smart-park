package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("visitor")
public class Visitor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String visitorName;

    /** 身份证密文（AES-256-GCM），接口出参一律替换为 idCardMask */
    private String idCard;

    /** 身份证 HMAC-SHA256（等值检索走此列） */
    private String idCardHash;

    /** 身份证掩码（前6后4），列表与详情展示 */
    private String idCardMask;

    /** 手机号密文 */
    private String phone;

    /** 手机号 HMAC-SHA256 */
    private String phoneHash;

    /** 手机号掩码（前3后4） */
    private String phoneMask;

    private String plateNo;

    private String hostName;

    private String hostDept;

    private String reason;

    private String area;

    private String appointTime;

    private String expireTime;

    /** 已预约 / 在场 / 已签销 / 滞留预警 */
    private String status;

    private String checkInTime;

    private String checkOutTime;
}
