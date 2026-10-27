package com.smartpark.security;

/**
 * 角色编码（与前端枚举一致，最终值冻结）：管理员 / 门岗 / 仓管。
 * Spring Security 内部 authorities 为 ROLE_ + 此值，接口出参只返回裸角色码。
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String GATE = "GATE";
    public static final String STORE = "STORE";

    private Roles() {
    }

    public static String authorityOf(String role) {
        return "ROLE_" + role;
    }
}
