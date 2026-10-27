package com.smartpark.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与校验（HS256）。access token 与 refresh token 以 claim {@code typ} 区分，
 * refresh 每次使用即轮换（签发新对，旧 refresh 在自然过期前仍有效：无状态方案已知取舍）。
 */
@Slf4j
@Component
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_UID = "uid";
    public static final String CLAIM_TYP = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少配置项 smartpark.security.jwt-secret（环境变量 SMARTPARK_JWT_SECRET），拒绝启动");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT 密钥长度不足 32 字节，HS256 不安全，拒绝启动");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String issueAccessToken(String username, String role, Long uid) {
        return issue(username, role, uid, TYPE_ACCESS, accessExpiration());
    }

    public String issueRefreshToken(String username, String role, Long uid) {
        return issue(username, role, uid, TYPE_REFRESH, refreshExpiration());
    }

    public long accessExpiresInSeconds() {
        return properties.getAccessTokenMinutes() * 60;
    }

    /** 解析成功返回 claims，任何失败（过期/签名错误/类型不符）返回 null，由调用方转 401 */
    public Claims parse(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String typ = claims.get(CLAIM_TYP, String.class);
            if (expectedType != null && !expectedType.equals(typ)) {
                return null;
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private String issue(String username, String role, Long uid, String typ, Date expiration) {
        return Jwts.builder()
                .subject(username)
                .claims(Map.of(CLAIM_ROLE, role, CLAIM_TYP, typ))
                .claim(CLAIM_UID, uid)
                .issuedAt(new Date())
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    private Date accessExpiration() {
        return new Date(System.currentTimeMillis() + properties.getAccessTokenMinutes() * 60_000);
    }

    private Date refreshExpiration() {
        return new Date(System.currentTimeMillis() + properties.getRefreshTokenDays() * 86_400_000);
    }
}
