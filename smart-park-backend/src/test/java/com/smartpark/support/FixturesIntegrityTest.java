package com.smartpark.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数化数据（docs/qa/fixtures/*.csv）的完整性自检。
 *
 * 刻意**不继承 MySqlIntegrationTest**：本文件不需要 Docker，因此可以在任何环境（含本机无 docker）
 * 与 CI 的"无容器"阶段执行，专门守住三件事：
 *   ① CSV 没被 BOM 污染、没被改列、没被误删行（否则鉴权矩阵会静默漏测接口）；
 *   ② 期望值只可能是契约里的错误码，不出现手滑值；
 *   ③ 几条**已裁决的权限口径**不被改回去（改 CSV 就等于改口径，必须让人看见）。
 *
 * 这也是唯一能在无 docker 环境下验证"CSV 能被正确解析"的手段——
 * 真正的矩阵测试因 disabledWithoutDocker 会整体跳过，跳过时 CSV 根本不会被读取。
 */
class FixturesIntegrityTest {

    private static final Set<String> ALLOWED_EXPECT = Set.of("0", "40101", "40301");
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    @Test
    void authMatrixKeeps42RowsAndValidValues() {
        List<QaFixtures.AuthCase> cases = QaFixtures.authCases();

        assertThat(cases).as("鉴权矩阵必须是 42 接口").hasSize(42);

        for (QaFixtures.AuthCase c : cases) {
            assertThat(ALLOWED_METHODS).contains(c.method());
            assertThat(c.path()).as("路径应以 /api/ 开头：" + c.path()).startsWith("/api/");
            for (String identity : new String[]{"admin", "gate", "store", "anon"}) {
                assertThat(ALLOWED_EXPECT)
                        .as("%s %s 的 %s 期望值应在契约错误码内", c.method(), c.path(), identity)
                        .contains(String.valueOf(c.expectFor(identity)));
            }
        }

        // 路径唯一（去重 = 42，防止复制粘贴产生重复行导致部分接口从未被执行）
        Map<String, Long> dup = cases.stream()
                .map(c -> c.method() + " " + c.path())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(dup.values()).as("不应存在重复接口行：%s", dup).allMatch(v -> v == 1);
    }

    @Test
    void publicEndpointsMustBeAnonymousAllowed() {
        Map<String, QaFixtures.AuthCase> byPath = QaFixtures.authCases().stream()
                .collect(Collectors.toMap(c -> c.method() + " " + c.path(), Function.identity()));

        // 免鉴权只有这两个：login 与 refresh
        // （refresh 若被 /auth/** authenticated 覆盖，"access 过期→refresh 自救"这条链路会自断）
        for (String key : new String[]{"POST /api/auth/login", "POST /api/auth/refresh"}) {
            assertThat(byPath.get(key).expectFor("anon"))
                    .as("%s 必须免鉴权", key)
                    .isZero();
        }

        // 其余接口未登录一律 40101
        for (QaFixtures.AuthCase c : QaFixtures.authCases()) {
            if (!c.path().equals("/api/auth/login") && !c.path().equals("/api/auth/refresh")) {
                assertThat(c.expectFor("anon")).as("未登录访问 %s 应为 40101", c.path()).isEqualTo(40101);
            }
        }
    }

    @Test
    void rulingsAreLockedAgainstRegression() {
        Map<String, QaFixtures.AuthCase> byPath = QaFixtures.authCases().stream()
                .collect(Collectors.toMap(c -> c.method() + " " + c.path(), Function.identity()));

        // 裁决：审批严格 ADMIN-only
        assertThat(byPath.get("POST /api/stock-records/{id}/approve").expectFor("gate")).isEqualTo(40301);
        assertThat(byPath.get("POST /api/stock-records/{id}/approve").expectFor("store")).isEqualTo(40301);

        // 裁决：门岗不进物资模块（台账与明细同口径）
        assertThat(byPath.get("GET /api/materials").expectFor("gate")).isEqualTo(40301);
        assertThat(byPath.get("GET /api/materials/{id}").expectFor("gate")).isEqualTo(40301);

        // 裁决：门岗必须能发权限（登记→授权→通行，缺授权闭环不了）
        assertThat(byPath.get("POST /api/permissions").expectFor("gate")).isZero();
        assertThat(byPath.get("PUT /api/permissions/{id}").expectFor("gate")).isZero();

        // 仓管不得模拟通行；门岗可模拟通行
        assertThat(byPath.get("POST /api/access-records/simulate").expectFor("store")).isEqualTo(40301);
        assertThat(byPath.get("POST /api/access-records/simulate").expectFor("gate")).isZero();
    }

    @Test
    void enumConstraintsKeeps20Rows() {
        List<QaFixtures.EnumCase> cases = QaFixtures.enumCases();

        assertThat(cases).as("CHECK 约束矩阵必须是 20 条").hasSize(20);
        for (QaFixtures.EnumCase c : cases) {
            assertThat(c.constraint()).as("约束名不得为空").isNotBlank();
            assertThat(c.table()).isNotBlank();
            assertThat(c.column()).isNotBlank();
            assertThat(c.legal()).as("%s 应有合法值样本", c.constraint()).isNotBlank();
            assertThat(c.illegal()).as("%s 应有非法值样本", c.constraint()).isNotBlank();
            // 挂起态（errno=0 且无消息）之外的行，必须给出 3819
            if (!c.pending()) {
                assertThat(c.errno()).as("%s 负向应报 3819", c.constraint()).isEqualTo(3819);
                assertThat(c.msgContains()).as("%s 应校验消息含约束名", c.constraint()).contains(c.constraint());
            }
        }
        // 约束名唯一
        assertThat(cases.stream().map(QaFixtures.EnumCase::constraint).distinct().count())
                .as("约束名不得重复").isEqualTo(20);
    }

    @Test
    void enumApiValidationCsvIsParseable() {
        List<String[]> rows = QaFixtures.readCsv(QaFixtures.fixturesDir() + "/enum-api-validation.csv");

        assertThat(rows).as("API 枚举校验清单应有 16 行（12 枚举字段 + 4 口径登记项）").hasSize(16);
        for (String[] row : rows) {
            assertThat(row.length).as("每行应为 11 列，实际 %d：%s", row.length, String.join(",", row)).isEqualTo(11);
            assertThat(row[0]).as("字段名不得为空").isNotBlank();
            assertThat(Set.of("P0", "P1", "P2")).contains(row[1]);
        }
    }

    @Test
    void csvFilesMustNotHaveBom() throws Exception {
        // BOM 会让首列名变成 "\ufeff模块"，列名匹配静默失败，
        // 报错信息看起来像"文件不存在"，极难定位——所以必须在字节层面守住。
        for (String file : new String[]{"auth-matrix.csv", "enum-constraints.csv", "enum-api-validation.csv"}) {
            byte[] head = new byte[3];
            try (java.io.InputStream in = new java.io.FileInputStream(QaFixtures.fixturesDir() + "/" + file)) {
                int read = in.read(head);
                assertThat(read).as("%s 过短", file).isEqualTo(3);
            }
            boolean bom = (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF;
            assertThat(bom).as("%s 必须是无 BOM 的 UTF-8（首字节为 %02x %02x %02x）", file, head[0], head[1], head[2]).isFalse();
        }
    }
}
