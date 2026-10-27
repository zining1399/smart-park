package com.smartpark.config;

import com.smartpark.common.DateTimes;
import com.smartpark.entity.SysUser;
import com.smartpark.security.Roles;
import com.smartpark.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次部署初始化三个角色账号（sys_user 为空时执行）。
 * 初始密码优先取环境变量 SMARTPARK_INIT_ADMIN_PASSWORD / SMARTPARK_INIT_GATE_PASSWORD / SMARTPARK_INIT_STORE_PASSWORD，
 * 未配置时生成随机密码并打印到日志（仅一次）。交付文档要求：首次登录后必须修改密码。
 */
@Slf4j
@Component
public class AdminInitializer implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;

    public AdminInitializer(SysUserService sysUserService, PasswordEncoder passwordEncoder) {
        this.sysUserService = sysUserService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (sysUserService.countUsers() > 0) {
            return;
        }
        create("admin", "系统管理员", Roles.ADMIN, env("SMARTPARK_INIT_ADMIN_PASSWORD"));
        create("gate", "门岗值班", Roles.GATE, env("SMARTPARK_INIT_GATE_PASSWORD"));
        create("store", "仓库管理员", Roles.STORE, env("SMARTPARK_INIT_STORE_PASSWORD"));
        log.warn("已初始化默认账号 admin/gate/store，请首次登录后立即修改密码（生产环境务必通过环境变量指定初始密码）");
    }

    private void create(String username, String realName, String role, String rawPassword) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setRole(role);
        user.setStatus("启用");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setCreateTime(DateTimes.now());
        sysUserService.save(user);
        log.info("初始化账号：{}（role={}）初始密码={}", username, role, rawPassword);
    }

    private String env(String key) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? randomPassword() : value;
    }

    private String randomPassword() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
