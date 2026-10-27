package com.smartpark.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 参数化数据源：直接读取 docs/qa/fixtures/*.csv，保证文档与测试共用一个数据源。
 *
 * 路径解析：系统属性 qa.fixtures.dir（默认 ../../docs/qa/fixtures，相对后端模块目录）。
 * CSV 必须以纯 UTF-8（无 BOM）保存，否则首列名会带上 \ufeff 导致列名匹配失败。
 */
public final class QaFixtures {

    private static final String DIR_PROPERTY = "qa.fixtures.dir";
    /**
     * 候选目录（按序取第一个存在的）：
     * ① -Dqa.fixtures.dir=<目录>；
     * ② ../docs/qa/fixtures —— surefire 的工作目录是**模块目录** smart-park-backend，
     *    「../../」会退到仓库的上级（WorkBuddy/docs），是错的，这里必须只有一层「..」；
     * ③ docs/qa/fixtures —— 兼容从仓库根目录运行 mvn -pl 的场景。
     */
    private static final String[] CANDIDATE_DIRS = {"../docs/qa/fixtures", "docs/qa/fixtures"};

    private QaFixtures() {
    }

    /** 鉴权矩阵：42 接口 ×（管理员/门岗/仓管/未登录） */
    public record AuthCase(String module, String caseId, String method, String path,
                           int adminExpect, int gateExpect, int storeExpect, int anonExpect,
                           boolean inMergeGate, String remark) {

        public int expectFor(String identity) {
            return switch (identity) {
                case "admin" -> adminExpect;
                case "gate" -> gateExpect;
                case "store" -> storeExpect;
                case "anon" -> anonExpect;
                default -> throw new IllegalArgumentException("未知身份：" + identity);
            };
        }
    }

    /** CHECK 约束矩阵：20 条 */
    public record EnumCase(String constraint, String table, String column, String legal, String illegal,
                           boolean nullable, int errno, String msgContains, int positiveExpect) {
        public boolean pending() {
            return errno == 0 && msgContains != null && msgContains.isBlank();
        }
    }

    public static List<AuthCase> authCases() {
        String dir = resolveDir();
        List<AuthCase> cases = new ArrayList<>();
        for (String[] row : readCsv(dir + "/auth-matrix.csv")) {
            cases.add(new AuthCase(
                    row[0], row[1], row[2], row[3],
                    Integer.parseInt(row[4]), Integer.parseInt(row[5]),
                    Integer.parseInt(row[6]), Integer.parseInt(row[7]),
                    "Y".equalsIgnoreCase(row[8]), row.length > 9 ? row[9] : ""));
        }
        return cases;
    }

    public static List<EnumCase> enumCases() {
        String dir = resolveDir();
        List<EnumCase> cases = new ArrayList<>();
        for (String[] row : readCsv(dir + "/enum-constraints.csv")) {
            int errorNo = "暂缓".equals(row[7]) ? 0 : Integer.parseInt(row[7]);
            cases.add(new EnumCase(
                    row[0], row[1], row[2], row[3], row[4],
                    "是".equals(row[5]), errorNo, row[8], row.length > 9 ? safeInt(row[9]) : 0));
        }
        return cases;
    }

    public static class AuthMatrixProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return authCases().stream().map(Arguments::of);
        }
    }

    public static class EnumConstraintProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return enumCases().stream().map(Arguments::of);
        }
    }

    private static String resolveDir() {
        String explicit = System.getProperty(DIR_PROPERTY);
        if (explicit != null && Files.isDirectory(Paths.get(explicit))) {
            return explicit;
        }
        for (String candidate : CANDIDATE_DIRS) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到参数化数据目录，已尝试：" + String.join(" / ", CANDIDATE_DIRS)
                + "（工作目录 " + Paths.get("").toAbsolutePath() + "；可用 -D" + DIR_PROPERTY + "=<目录> 指定）");
    }

    /** 供自检用例使用：返回实际解析到的 fixtures 目录 */
    static String fixturesDir() {
        return resolveDir();
    }

    /** 极简 CSV 解析：不支持引号内逗号（本项目 CSV 无此需求） */
    static List<String[]> readCsv(String file) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8);
            List<String[]> rows = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                rows.add(line.split(",", -1));
            }
            rows.remove(0); // 表头
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("读取参数化数据失败：" + file, e);
        }
    }

    private static int safeInt(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
    }
}
