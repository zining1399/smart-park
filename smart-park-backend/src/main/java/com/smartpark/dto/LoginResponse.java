package com.smartpark.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 登录 / 刷新令牌响应：data = { token, refreshToken, expiresIn, user }。
 */
@Data
@Builder
public class LoginResponse {

    private String token;

    private String refreshToken;

    /** access token 有效秒数 */
    private Long expiresIn;

    private UserInfo user;

    @Data
    @Builder
    public static class UserInfo {
        private String username;
        private String realName;
        private String role;
    }
}
