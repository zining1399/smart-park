package com.smartpark.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.SysUser;

public interface SysUserService extends IService<SysUser> {

    /** 按登录名查找（忽略状态，用于区分"不存在"与"已禁用"） */
    SysUser findByUsername(String username);

    /** 按登录名查找且状态必须为「启用」（JwtAuthFilter 每次请求调用，禁用即时生效） */
    SysUser findEnabledByUsername(String username);

    /** 统计已有账号数（初始化判断用） */
    long countUsers();
}
