package com.smartpark.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpark.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TC-AUTH-12 禁用账号必须即时失效（无状态 JWT 的老问题）
 * TC-AUTH-13 登录名大小写不敏感：唯一键冲突 1062，应用层须转成 40901 友好提示，不得冒泡 50001
 */
class AccountStatusTest extends MySqlIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledAccountTokenMustFailImmediately() throws Exception {
        String token = tokenOf("admin");

        // 禁用前：token 可用
        assertThat(callMe(token)).as("禁用前应可访问 /auth/me").isEqualTo(0);

        jdbcTemplate.update("UPDATE sys_user SET status = '禁用' WHERE username = 'admin'");

        // 禁用后：未过期的 token 立即失效（40103，不是放行）
        assertThat(callMe(token))
                .as("禁用后，未过期 token 访问业务接口应被拒（40103）")
                .isEqualTo(40103);
    }

    @Test
    void disabledAccountCannotLoginAgain() throws Exception {
        jdbcTemplate.update("UPDATE sys_user SET status = '禁用' WHERE username = 'admin'");
        try {
            String body = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(body).path("code").asInt())
                    .as("禁用账号重新登录应返回 40103")
                    .isEqualTo(40103);
        } finally {
            jdbcTemplate.update("UPDATE sys_user SET status = '启用' WHERE username = 'admin'");
        }
    }

    @Test
    void reEnabledAccountTokenWorksAgain() throws Exception {
        String token = tokenOf("admin");
        jdbcTemplate.update("UPDATE sys_user SET status = '禁用' WHERE username = 'admin'");
        assertThat(callMe(token)).isEqualTo(40103);

        jdbcTemplate.update("UPDATE sys_user SET status = '启用' WHERE username = 'admin'");
        assertThat(callMe(token)).as("重新启用后原 token 应恢复可用").isEqualTo(0);
    }

    /** 登录名大小写不敏感：'ADMIN' 与 'admin' 撞唯一键（1062），且错误须指向具体约束名 */
    @Test
    void usernameCaseInsensitiveUniqueKeyViolation() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO sys_user (username, password_hash, real_name, role) VALUES (?,?,?,?)",
                "ADMIN", passwordEncoder.encode(TEST_PASSWORD), "重名账号", "ADMIN"))
                .isInstanceOfSatisfying(DataAccessException.class, ex -> {
                    SQLException sql = unwrap(ex);
                    // SQLException 实现 Iterable<Throwable>，直接传入会产生重载二义性，需显式转型
                    assertThat((Object) sql).isNotNull();
                    assertThat(sql.getErrorCode()).as("应为 ERROR 1062（唯一键冲突），不是 3819").isEqualTo(1062);
                    assertThat(sql.getMessage()).as("1062 错误须能定位到具体约束").contains("uk_sys_user_username");
                });
    }

    /** 正向钉住：用不同大小写登录能登进同一账号（ci 是刻意选择，不是缺陷） */
    @Test
    void loginWithDifferentCaseIsAllowed() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ADMIN\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("code").asInt())
                .as("登录名大小写不敏感，ADMIN 应能登录 admin 的账号")
                .isEqualTo(0);
    }

    /**
     * TC-AUTH-13 的 API 层部分：创建同名不同大小写账号应返回 40901「登录名已存在」，不得冒泡 50001。
     * ⏳ 当前无用户管理接口（42 个接口中不含 /users），待 backend 补接口后启用本用例。
     */
    @Test
    @Disabled("等待用户管理接口（POST /users）落地后启用")
    void duplicateUsernameReturnsFriendlyConflict() throws Exception {
        String token = tokenOf("admin");
        String body = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ADMIN\",\"realName\":\"重名\",\"role\":\"ADMIN\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        assertThat(node.path("code").asInt()).as("应为业务冲突 40901，不得冒泡为 50001").isEqualTo(40901);
        assertThat(node.path("message").asText()).contains("登录名已存在");
    }

    private int callMe(String token) throws Exception {
        String body = mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("code").asInt(-1);
    }

    private SQLException unwrap(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SQLException sql) {
                return sql;
            }
            cur = cur.getCause();
        }
        return null;
    }
}
