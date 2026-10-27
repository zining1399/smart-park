package com.smartpark.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全相关配置：JWT 密钥、令牌有效期、CORS 白名单。
 * 敏感值由环境变量注入（SMARTPARK_JWT_SECRET / SMARTPARK_CORS_ORIGINS），配置文件不落真实密钥。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "smartpark.security")
public class JwtProperties {

    /** HS256 密钥，>= 32 字节随机串：openssl rand -base64 48 */
    private String jwtSecret;

    /** access token 有效时长（分钟），默认 120 */
    private long accessTokenMinutes = 120;

    /** refresh token 有效时长（天），默认 7 */
    private long refreshTokenDays = 7;

    /** CORS 白名单（逗号分隔）；留空表示同域部署，不注册 CORS mapping */
    private String corsOrigins = "";
}
