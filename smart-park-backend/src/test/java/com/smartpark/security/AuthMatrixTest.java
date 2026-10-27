package com.smartpark.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpark.support.MySqlIntegrationTest;
import com.smartpark.support.QaFixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * TC-AUTH-01/03/04/05 鉴权矩阵：42 接口 × 4 种身份（管理员 / 门岗 / 仓管 / 未登录）= 168 项。
 * 数据源：docs/qa/fixtures/auth-matrix.csv（与文档共用，改 CSV 即改测试）。
 *
 * 断言语义（重要）：本矩阵验证的是「鉴权层是否拦截」，不是「业务是否成功」。
 *  - 期望允许：code 非 40101 且非 40301 即视为通过（40401/40001 属业务层，不掩盖鉴权问题）；
 *  - 期望拒绝：code 与 HTTP 状态必须精确匹配（40301/403 或 40101/401）。
 */
class AuthMatrixTest extends MySqlIntegrationTest {

    private static final String ID = "1";
    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<QaFixtures.AuthCase> cases() {
        return QaFixtures.authCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void authMatrix(QaFixtures.AuthCase c) throws Exception {
        int allowed = c.expectFor("admin");
        assertIdentity(c, "admin", allowed);
        assertIdentity(c, "gate", c.expectFor("gate"));
        assertIdentity(c, "store", c.expectFor("store"));
        assertIdentity(c, "anon", c.expectFor("anon"));
    }

    private void assertIdentity(QaFixtures.AuthCase c, String identity, int expect) throws Exception {
        MvcResult result = perform(c, identity);
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        int code = body.path("code").asInt(-1);

        if (expect == 0) {
            assertThat(code)
                    .as("%s %s（%s）应通过鉴权层", c.method(), c.path(), identity)
                    .isNotIn(40101, 40301);
        } else {
            assertThat(code)
                    .as("%s %s（%s）应被鉴权层拒绝", c.method(), c.path(), identity)
                    .isEqualTo(expect);
            assertThat(result.getResponse().getStatus())
                    .as("%s %s（%s）HTTP 状态", c.method(), c.path(), identity)
                    .isEqualTo(expect == 40101 ? 401 : 403);
        }
    }

    private MvcResult perform(QaFixtures.AuthCase c, String identity) throws Exception {
        String token = "anon".equals(identity) ? null : tokenOf(identity);
        String path = c.path().replace("{id}", ID);

        if ("/api/auth/refresh".equals(c.path())) {
            // 刷新接口免鉴权，但需要合法的 refreshToken：先用 admin 登录取一对令牌
            MvcResult login = mockMvc.perform(post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}")).andReturn();
            String refreshToken = mapper.readTree(login.getResponse().getContentAsString()).at("/data/refreshToken").asText();
            return mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn();
        }

        String body = BODIES.get(c.method() + " " + c.path());
        return switch (c.method()) {
            case "GET" -> mockMvc.perform(token == null ? get(path) : get(path).header("Authorization", "Bearer " + token)).andReturn();
            case "POST" -> {
                var builder = post(path).contentType(MediaType.APPLICATION_JSON);
                if (token != null) {
                    builder.header("Authorization", "Bearer " + token);
                }
                yield mockMvc.perform(builder.content(body == null ? "{}" : body)).andReturn();
            }
            case "PUT" -> {
                var builder = put(path).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body);
                if (token != null) {
                    builder.header("Authorization", "Bearer " + token);
                }
                yield mockMvc.perform(builder).andReturn();
            }
            case "DELETE" -> mockMvc.perform(token == null
                    ? delete(path)
                    : delete(path).header("Authorization", "Bearer " + token)).andReturn();
            default -> throw new IllegalArgumentException("不支持的方法：" + c.method());
        };
    }

    /** 各写入口的最小合法请求体（保证请求能走到鉴权之后，而不是被参数校验提前返回） */
    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("POST /api/auth/login", "{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}"),
            Map.entry("POST /api/employees", "{\"empNo\":\"E9001\",\"name\":\"矩阵测试\",\"dept\":\"生产部\",\"phone\":\"13900000000\",\"status\":\"在职\"}"),
            Map.entry("PUT /api/employees/{id}", "{\"empNo\":\"E9001\",\"name\":\"矩阵测试改\",\"dept\":\"生产部\",\"phone\":\"13900000000\",\"status\":\"在职\"}"),
            Map.entry("POST /api/vehicles", "{\"plateNo\":\"新A9001\",\"ownerType\":\"员工\",\"ownerName\":\"矩阵\",\"status\":\"正常\"}"),
            Map.entry("PUT /api/vehicles/{id}", "{\"plateNo\":\"新A9001\",\"ownerType\":\"员工\",\"ownerName\":\"矩阵\",\"status\":\"正常\"}"),
            Map.entry("POST /api/permissions", "{\"targetType\":\"PERSON\",\"targetNo\":\"E9001\",\"area\":\"办公区\",\"startTime\":\"2026-01-01 00:00:00\",\"endTime\":\"2099-12-31 23:59:59\",\"status\":\"生效\"}"),
            Map.entry("PUT /api/permissions/{id}", "{\"targetType\":\"PERSON\",\"targetNo\":\"E9001\",\"area\":\"办公区\",\"startTime\":\"2026-01-01 00:00:00\",\"endTime\":\"2099-12-31 23:59:59\",\"status\":\"生效\"}"),
            Map.entry("POST /api/access-records/simulate", "{\"passType\":\"PERSON\",\"targetNo\":\"E9001\",\"gate\":\"东门\",\"direction\":\"IN\"}"),
            Map.entry("POST /api/visitors", "{\"visitorName\":\"矩阵\",\"idCard\":\"110101199001011234\",\"phone\":\"13900000000\",\"hostName\":\"矩阵\",\"hostDept\":\"生产部\",\"reason\":\"矩阵\",\"area\":\"办公区\",\"appointTime\":\"2026-01-01 08:00:00\",\"expireTime\":\"2099-12-31 23:59:59\",\"status\":\"已预约\"}"),
            Map.entry("PUT /api/visitors/{id}", "{\"visitorName\":\"矩阵\",\"idCard\":\"110101199001011234\",\"phone\":\"13900000000\",\"hostName\":\"矩阵\",\"hostDept\":\"生产部\",\"reason\":\"矩阵\",\"area\":\"办公区\",\"appointTime\":\"2026-01-01 08:00:00\",\"expireTime\":\"2099-12-31 23:59:59\",\"status\":\"已预约\"}"),
            Map.entry("POST /api/materials", "{\"code\":\"M-MATRIX-001\",\"name\":\"矩阵物资\",\"category\":\"工具\",\"spec\":\"M\",\"dept\":\"生产部\",\"location\":\"A-01\",\"valuable\":false,\"status\":\"在库\"}"),
            Map.entry("PUT /api/materials/{id}", "{\"code\":\"M-MATRIX-001\",\"name\":\"矩阵物资\",\"category\":\"工具\",\"spec\":\"M\",\"dept\":\"生产部\",\"location\":\"A-01\",\"valuable\":false,\"status\":\"在库\"}"),
            Map.entry("POST /api/stock-records/in", "{\"materialCode\":\"M-MATRIX-001\"}"),
            Map.entry("POST /api/stock-records/out", "{\"materialCode\":\"M-MATRIX-001\",\"borrower\":\"矩阵\",\"dept\":\"生产部\",\"expectReturnTime\":\"2099-12-31 23:59:59\"}"),
            Map.entry("POST /api/stock-records/{id}/approve", "{\"approved\":true}"),
            Map.entry("POST /api/alarms/{id}/handle", "{}")
    );

    static {
        // 防止误用：CSV 变更（接口增删）时提示同步 BODIES
        if (BODIES.isEmpty()) {
            throw new IllegalStateException("请求体映射为空，接口矩阵无法执行");
        }
    }

    /** 供其他测试复用：CSV 中的全部接口路径 */
    static List<QaFixtures.AuthCase> allCases() {
        return QaFixtures.authCases();
    }
}
