package com.smartpark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 系统账号（最小鉴权：登录 + 三角色，无用户-角色-权限-数据范围四维模型）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，唯一 */
    private String username;

    /** BCrypt 密码散列（永不回传前端） */
    private String passwordHash;

    private String realName;

    /** ADMIN / GATE / STORE */
    private String role;

    /** 启用 / 禁用 */
    private String status;

    private String createTime;
}
