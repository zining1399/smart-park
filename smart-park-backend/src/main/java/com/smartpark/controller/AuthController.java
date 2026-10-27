package com.smartpark.controller;

import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.ErrorCodes;
import com.smartpark.common.Result;
import com.smartpark.dto.LoginRequest;
import com.smartpark.dto.LoginResponse;
import com.smartpark.dto.RefreshRequest;
import com.smartpark.entity.SysUser;
import com.smartpark.security.JwtService;
import com.smartpark.service.SysUserService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权入口：登录（匿名）、当前用户信息、令牌刷新。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogger auditLogger;

    public AuthController(SysUserService sysUserService,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          AuditLogger auditLogger) {
        this.sysUserService = sysUserService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditLogger = auditLogger;
    }

    /** 登录：失败统一 40102（密码错误）/ 40103（账号禁用），前端按 code 区分，不跳转 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        SysUser user = sysUserService.findByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditLogger.audit("LOGIN", request.getUsername(), "FAIL", "用户名或密码错误");
            throw new BizException(ErrorCodes.LOGIN_FAILED, "用户名或密码错误");
        }
        if (!"启用".equals(user.getStatus())) {
            auditLogger.audit("LOGIN", request.getUsername(), "DENY", "账号已被禁用");
            throw new BizException(ErrorCodes.ACCOUNT_DISABLED, "账号已被禁用");
        }
        auditLogger.audit("LOGIN", user.getUsername(), "SUCCESS", "role=" + user.getRole());
        return Result.ok(issueTokens(user));
    }

    /** 刷新：refresh 每次使用后轮换（签发新的一对令牌） */
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        Claims claims = jwtService.parse(request.getRefreshToken(), JwtService.TYPE_REFRESH);
        if (claims == null) {
            throw new BizException(ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        SysUser user = sysUserService.findByUsername(claims.getSubject());
        if (user == null || !"启用".equals(user.getStatus())) {
            throw new BizException(ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return Result.ok(issueTokens(user));
    }

    /** 当前登录用户（页面刷新后恢复会话与菜单） */
    @GetMapping("/me")
    public Result<LoginResponse.UserInfo> me(Authentication authentication) {
        if (authentication == null) {
            throw new BizException(ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        SysUser user = sysUserService.findByUsername(authentication.getName());
        if (user == null) {
            throw new BizException(ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return Result.ok(LoginResponse.UserInfo.builder()
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .build());
    }

    private LoginResponse issueTokens(SysUser user) {
        return LoginResponse.builder()
                .token(jwtService.issueAccessToken(user.getUsername(), user.getRole(), user.getId()))
                .refreshToken(jwtService.issueRefreshToken(user.getUsername(), user.getRole(), user.getId()))
                .expiresIn(jwtService.accessExpiresInSeconds())
                .user(LoginResponse.UserInfo.builder()
                        .username(user.getUsername())
                        .realName(user.getRealName())
                        .role(user.getRole())
                        .build())
                .build();
    }
}
