package com.smartpark.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类（QA 专用）：
 *   MySQL 8 容器 → Flyway V1 自动建表 → 播种三个角色账号 → MockMvc 调用。
 *
 * 设计要点：
 *  - 容器为 static，多个测试类共用同一个容器（Flyway 只跑一次，速度快）；
 *  - 无 Docker 环境时 @Testcontainers(disabledWithoutDocker = true) 自动跳过，不误报失败；
 *  - 账号播种只执行一次（容器数据跨测试类复用）；
 *  - 数据源 URL 通过 @DynamicPropertySource 注入，测试期间同时验证 Flyway 脚本可执行。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.profiles.active=test",
        "smartpark.security.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
        "smartpark.security.access-token-minutes=120",
        "smartpark.security.refresh-token-days=7",
        "smartpark.crypto.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "smartpark.crypto.hmac-key=test-hmac-key-0123456789abcdefgh",
        "smartpark.pii-backfill.enabled=false"
})
@AutoConfigureMockMvc
public abstract class MySqlIntegrationTest {

    protected static final String TEST_PASSWORD = "Test@123456";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("smart_park")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--default-time-zone=+08:00", "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);
    private static final Map<String, String> TOKEN_CACHE = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void seedUsersOnce() {
        if (SEEDED.compareAndSet(false, true)) {
            insertUser("admin", "系统管理员", "ADMIN");
            insertUser("gate", "门岗值班", "GATE");
            insertUser("store", "仓库管理员", "STORE");
        }
    }

    private void insertUser(String username, String realName, String role) {
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, password_hash, real_name, role, status) VALUES (?, ?, ?, ?, '启用')",
                username, passwordEncoder.encode(TEST_PASSWORD), realName, role);
    }

    /** 按角色登录并缓存 token（admin / gate / store） */
    protected String tokenOf(String role) {
        return TOKEN_CACHE.computeIfAbsent(role, this::login);
    }

    private String login(String role) {
        try {
            MvcResult result = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + role + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                    .andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode token = body.at("/data/token");
            if (token.isMissingNode()) {
                throw new IllegalStateException("登录失败，未拿到 token：" + body);
            }
            return token.asText();
        } catch (Exception e) {
            throw new IllegalStateException("登录 " + role + " 失败", e);
        }
    }

    /** 构造带token的GET请求（路径不含 context-path，MockMvc 直接匹配 /xxx） */
    protected MockHttpServletRequestBuilder authGet(String path, String token) {
        return get(path).header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder authPost(String path, String token) {
        return post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    /** 造数用：随机业务键，避免唯一键冲突（uk_employee_emp_no / uk_vehicle_plate_no / uk_material_code） */
    protected static String randomCode(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
