package com.smartpark.data;

import com.smartpark.support.MySqlIntegrationTest;
import com.smartpark.support.QaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-ENUM-01 CHECK 约束矩阵（20 条）+
 * TC-ENUM-04 可空列 NULL 放行 +
 * TC-ENUM-05 刻意无 CHECK 的两列正向放行。
 *
 * 断言口径：负向必须 **ERROR 3819** 且消息含**该约束名**（双条件，排除 1364/1062/1146 假通过）。
 * 数据源：docs/qa/fixtures/enum-constraints.csv。
 */
class EnumConstraintsTest extends MySqlIntegrationTest {

    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);
    private static final Map<String, Long> SEED_IDS = new HashMap<>();

    @BeforeEach
    void seedRowsOnce() {
        if (SEEDED.compareAndSet(false, true)) {
            String s = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            SEED_IDS.put("employee", insert("INSERT INTO employee (emp_no, name) VALUES (?, ?)", "E" + s, "枚举测试"));
            SEED_IDS.put("vehicle", insert("INSERT INTO vehicle (plate_no) VALUES (?)", "新A" + s));
            SEED_IDS.put("access_permission", insert(
                    "INSERT INTO access_permission (target_type, target_no, area, start_time, end_time) VALUES (?,?,?,?,?)",
                    "PERSON", "E" + s, "办公区", "2026-01-01 00:00:00", "2026-12-31 23:59:59"));
            SEED_IDS.put("access_record", insert(
                    "INSERT INTO access_record (pass_type, target_no, pass_time, result) VALUES (?,?,?,?)",
                    "PERSON", "E" + s, "2026-01-01 08:00:00", "放行"));
            SEED_IDS.put("visitor", insert("INSERT INTO visitor (visitor_name) VALUES (?)", "枚举访客" + s));
            SEED_IDS.put("material", insert("INSERT INTO material (code, name) VALUES (?, ?)", "M-ENUM-" + s, "枚举物资" + s));
            SEED_IDS.put("stock_record", insert(
                    "INSERT INTO stock_record (material_code, type, approve_status) VALUES (?,?,?)",
                    "M-ENUM-" + s, "IN", "免审"));
            SEED_IDS.put("alarm", insert("INSERT INTO alarm (type, title) VALUES (?, ?)", "门禁异常", "枚举告警" + s));
            SEED_IDS.put("sys_user", insert(
                    "INSERT INTO sys_user (username, password_hash, real_name, role) VALUES (?,?,?,?)",
                    "enum" + s, passwordEncoder.encode(TEST_PASSWORD), "枚举账号", "ADMIN"));
        }
    }

    static java.util.stream.Stream<QaFixtures.EnumCase> cases() {
        return QaFixtures.enumCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void negativeValueRejectedByNamedCheck(QaFixtures.EnumCase c) {
        long id = SEED_IDS.get(c.table());
        String illegal = c.illegal().split("\\|")[0];

        if (isTimeRange(c)) {
            // chk_perm_time_range：>= 语义。真倒挂（end < start）报 3819；零时长（start = end）放行。
            String[] parts = c.illegal().split("~");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE access_permission SET start_time=?, end_time=? WHERE id=?", parts[0], parts[1], id))
                    .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                            ex -> assertCheckViolation(ex, c));
            jdbcTemplate.update("UPDATE access_permission SET start_time=?, end_time=? WHERE id=?",
                    "2026-06-01 00:00:00", "2026-06-01 00:00:00", id); // 零时长放行
            return;
        }

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE %s SET %s = ? WHERE id = ?".formatted(c.table(), c.column()), illegal, id))
                .as("%s 写入非法值 %s 应被 %s 拒绝", c.table() + "." + c.column(), illegal, c.constraint())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        ex -> assertCheckViolation(ex, c));

        // 正向：合法值必须写回成功
        String legal = c.legal().split("\\|")[0];
        jdbcTemplate.update("UPDATE %s SET %s = ? WHERE id = ?".formatted(c.table(), c.column()), legal, id);
    }

    @ParameterizedTest(name = "{0} NULL 放行")
    @MethodSource("nullableCases")
    void nullAcceptedWhenColumnNullable(QaFixtures.EnumCase c) {
        assertThat(c.nullable()).as("%s 应为可空列", c.column()).isTrue();
        jdbcTemplate.update("UPDATE %s SET %s = NULL WHERE id = ?".formatted(c.table(), c.column()),
                SEED_IDS.get(c.table()));
    }

    static java.util.stream.Stream<QaFixtures.EnumCase> nullableCases() {
        return QaFixtures.enumCases().stream().filter(QaFixtures.EnumCase::nullable);
    }

    /** TC-ENUM-05：刻意不建 CHECK 的两列，任意值必须能写入（防止有人把 CHECK 加回来） */
    @org.junit.jupiter.api.Test
    void intentionallyUnconstrainedColumnsAcceptAnyValue() {
        long recordId = SEED_IDS.get("access_record");
        jdbcTemplate.update("UPDATE access_record SET gate = ? WHERE id = ?", "北门", recordId);

        long alarmId = SEED_IDS.get("alarm");
        jdbcTemplate.update("UPDATE alarm SET type = ? WHERE id = ?", "自定义告警类型", alarmId);

        assertThat(jdbcTemplate.queryForObject("SELECT gate FROM access_record WHERE id = ?", String.class, recordId))
                .isEqualTo("北门");
    }

    private static boolean isTimeRange(QaFixtures.EnumCase c) {
        return c.column().contains(">=");
    }

    private void assertCheckViolation(Throwable ex, QaFixtures.EnumCase c) {
        SQLException sql = findSqlException(ex);
        // SQLException 实现了 Iterable<Throwable>，直接 assertThat(sql) 会在 assertThat(Iterable) 与
        // assertThat(T) 之间产生二义性（javac 报"无法应用到给定类型"），故显式向上转型
        assertThat((Object) sql).as("应抛出 SQLException").isNotNull();
        assertThat(sql.getErrorCode())
                .as("%s 违反时应为 ERROR 3819（非 1364/1062/1146 等假通过）", c.constraint())
                .isEqualTo(3819);
        assertThat(sql.getMessage())
                .as("%s 的错误消息应含约束名", c.constraint())
                .contains(c.constraint());
    }

    private SQLException findSqlException(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SQLException sql) {
                return sql;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private long insert(String sql, Object... args) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            var ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
