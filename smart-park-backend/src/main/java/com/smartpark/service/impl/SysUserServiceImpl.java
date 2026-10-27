package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.entity.SysUser;
import com.smartpark.mapper.SysUserMapper;
import com.smartpark.service.SysUserService;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Override
    public SysUser findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username).last("LIMIT 1"));
    }

    @Override
    public SysUser findEnabledByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        // status='启用' 下沉到查询条件：禁用账号即便持有未过期 token 也会被拒（每次请求查库，即时生效）
        return getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getStatus, "启用")
                .last("LIMIT 1"));
    }

    @Override
    public long countUsers() {
        return count();
    }
}
