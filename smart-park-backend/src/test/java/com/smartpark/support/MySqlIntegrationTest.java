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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类（QA 专用）：
 *   MySQL → Flyway V1 自动建表 → 播种三个角色账号 → MockMvc 调用。
 *
 * 两种数据源：
 *  - CI（SMARTPARK_IT_DB_URL 已注入）：直接连 GitHub service container 的 MySQL；
 *  - 本地（未注入）：启动 Testcontainers 的 MySQL 8 容器，无 Docker 时整类干净 skip
 *    （@Testcontainers(disabledWithoutDocker)，不是 hard error），由 CI 守卫兜红。
 *
 * 注意：模式开关用专用变量 SMARTPARK_IT_DB_URL，不用官方 SPRING_DATASOURCE_URL——
 * 否则有人本地设 SPRING_DATASOURCE_URL 指向开发库，测试会对其 DELETE sys_user。
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

    /** CI 模式：外部 MySQL（GitHub service container）已注入专用 SMARTPARK_IT_DB_URL */
    private static final boolean EXTERNAL_DB = System.getenv("SMARTPARK_IT_DB_URL") != null;

    /** 本地模式：Testcontainers MySQL 8，懒启动（@DynamicPropertySource 时才 start）；CI 模式为 null */
    private static MySQLContainer<?> MYSQL;

    private static synchronized void ensureContainerStarted() {
        if (MYSQL == null) {
            MYSQL = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("smart_park")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--default-time-zone=+08:00", "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci");
            MYSQL.start();
        }
    }

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
        if (EXTERNAL_DB) {
            // CI 模式：从专用 SMARTPARK_IT_DB_* 环境变量注入数据源
            registry.add("spring.datasource.url", () -> System.getenv("SMARTPARK_IT_DB_URL"));
            registry.add("spring.datasource.username", () -> System.getenv("SMARTPARK_IT_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("SMARTPARK_IT_DB_PASSWORD"));
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            return;
        }
        ensureContainerStarted();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void seedUsersOnce() {
        if (SEEDED.compareAndSet(false, true)) {
            // 清掉 AdminInitializer 建的账号（随机密码），用测试统一密码重建
            jdbcTemplate.update("DELETE FROM sys_user");
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
