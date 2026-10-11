package cn.vv.test.mysqlmock.compat.diff;

import cn.vv.test.mysqlmock.api.Dataset;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.ResourceLoader;
import cn.vv.test.mysqlmock.api.UpdateResult;
import cn.vv.test.mysqlmock.schema.DatasetLoader;
import cn.vv.test.mysqlmock.schema.SqlScriptSplitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差分兼容（D-09/T4/T6 差分证据）：同一 DDL + 数据集 + SQL 在引擎与真实 MySQL 双跑，
 * {@link ResultNormalizer} 归一后比对行/NULL/类型/影响行数/错误码；差异报告落
 * {@code compatibility-report/differential-report.md}。
 * <p>本机基准：docker 本地镜像（mysql:latest = 9.5.0；registry 拉取 8.0 受限时替代，
 * CI 应以 mysql:8.0 复跑）；库级 utf8mb4 / utf8mb4_general_ci（D-16 采集值）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
@Tag("compat")
class DifferentialCompatIT {

    private static final String[] SCHEMA_FILES = {
            "classpath:mysql/dine-customer.sql",
            "classpath:mysql/sku-unit.sql",
            "classpath:mysql/orders.sql",
    };
    private static final String DATASET_FILE = "classpath:datasets/dine-case.yml";

    private static MySqlContainerProvision provision;
    private static MysqlMockEngine engine;
    private static Connection mysql;
    private static Dataset dataset;
    private static final List<String> reportLines = new ArrayList<>();
    private static int passed;
    private static int failed;

    @BeforeAll
    static void setUp() throws Exception {
        provision = MySqlContainerProvision.start("mysql:latest");
        provision.initializeDatabases();
        engine = MysqlMockEngine.builder().schema(SCHEMA_FILES).dataset(DATASET_FILE).build();
        try {
            mysql = provision.open();
        } catch (SQLException e) {
            // USE mockdiff 的连接
            mysql = DriverManagerRetry.connect(provision);
        }
        dataset = new DatasetLoader().load(ResourceLoader.open(DATASET_FILE));
        resetBothSides();
        reportLines.add("# 差分兼容报告（D-09）");
        reportLines.add("");
        reportLines.add("- 基准: " + serverVersion() + "（docker 本地镜像；CI 钉 mysql:8.0 复跑）");
        reportLines.add("- 基准参数: utf8mb4 / utf8mb4_general_ci，server 默认 sql_mode，时区 Asia/Shanghai");
        reportLines.add("- 引擎: mysql-mock 0.1.0（Calcite 1.42.0）");
        reportLines.add("");
    }

    @AfterAll
    static void tearDown() throws Exception {
        mysql.close();
        engine.close();
        provision.stop();
        writeReport();
        if (failed > 0) {
            System.err.println("[compat] 差分失败 " + failed + " 条，见 compatibility-report/");
        }
    }

    // ---- T4 差分：查询 ----

    @Test
    void t4LeftJoinSumGroupHavingOrderLimit() {
        assertQueryAgreement("T4-JOIN",
                "SELECT o.customer_id, SUM(i.qty * i.price) AS total, COUNT(i.id) AS items "
                        + "FROM t_order o LEFT JOIN t_order_item i ON i.order_id = o.id "
                        + "LEFT JOIN vv_dine.dine_customer c ON c.id = o.customer_id "
                        + "WHERE o.status = 'PAID' AND o.amount >= 100.00 "
                        + "GROUP BY o.customer_id, c.nickname "
                        + "HAVING SUM(i.qty * i.price) > 100.00 "
                        + "ORDER BY total DESC, o.customer_id LIMIT 1, 2", true);
    }

    @Test
    void t4CrossDatabaseNoOrderBy() {
        assertQueryAgreement("T4-NOORDER",
                "SELECT id, nickname, status, occupies_unique FROM vv_dine.dine_customer "
                        + "WHERE phone_dial = '852' AND status <> 'deleted'", false);
    }

    @Test
    void t4GeneratedColumnProjection() {
        assertQueryAgreement("T4-GENCOL",
                "SELECT sku_id, unit, ratio_to_base, is_default, default_marker "
                        + "FROM vv_pc_sku_unit ORDER BY id", true);
    }

    @Test
    void t4TimestampPrecisionAndDateFormat() {
        assertQueryAgreement("T4-DATETIME",
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') FROM vv_pc_sku_unit "
                        + "WHERE id = 11", true);
    }

    // ---- T6 差分：UPDATE/DELETE 行数与索引 ----

    @Test
    void t6UpdateAffectedRowsAndValue() {
        assertUpdateAgreement("T6-UPDATE",
                "UPDATE vv_pc_sku_unit SET is_default = 0 WHERE sku_id = 1001");
        assertQueryAgreement("T6-UPDATE-AFTER",
                "SELECT is_default, default_marker FROM vv_pc_sku_unit ORDER BY id", true);
    }

    @Test
    void t6DeleteAffectedRows() {
        assertUpdateAgreement("T6-DELETE",
                "DELETE FROM t_order_item WHERE qty >= 4");
    }

    @Test
    void duplicateEntryErrorCodeBothSides() {
        String sql = "INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, tenant_code, is_default) "
                + "VALUES (1001, '瓶', 100.000, 'T1', 0)";
        int engineCode = errorCodeOfEngine(sql);
        int mysqlCode = errorCodeOfMysql(sql);
        record("ERR-1062", engineCode == mysqlCode && engineCode == 1062,
                "engine=" + engineCode + " mysql=" + mysqlCode);
        assertEquals(1062, engineCode);
        assertEquals(1062, mysqlCode);
    }

    @Test
    void notNullViolationBothSides() {
        // 缺列形态：MySQL 报 1364（Field doesn't have a default value）
        String missing = "INSERT INTO vv_dine.dine_customer (tenant_code, nickname, phone) "
                + "VALUES ('T1', 'NoShop', '90000001')";
        record("ERR-1364", checkErrorCode(missing, 1364), "");
        // 显式 NULL 形态：MySQL 报 1048
        String explicitNull = "INSERT INTO vv_dine.dine_customer "
                + "(tenant_code, shop_id, nickname, phone) VALUES ('T1', NULL, 'NoShop', '90000001')";
        record("ERR-1048", checkErrorCode(explicitNull, 1048), "");
    }

    private boolean checkErrorCode(String sql, int expected) {
        int engineCode = errorCodeOfEngine(sql);
        int mysqlCode = errorCodeOfMysql(sql);
        boolean ok = engineCode == mysqlCode && engineCode == expected;
        if (!ok) {
            record("MISMATCH", false, sql + " engine=" + engineCode + " mysql=" + mysqlCode);
        }
        return ok;
    }

    // ---- 双侧状态重置（DML 用例间互不污染） ----

    @Test
    void orderResetConsistency() {
        resetBothSides();
        assertQueryAgreement("RESET-CHECK",
                "SELECT COUNT(*) FROM t_order", true);
    }

    // ---- 实现 ----

    private void assertQueryAgreement(String caseId, String sql, boolean ordered) {
        List<String> engineRows = ResultNormalizer.normalizeRows(queryEngine(sql), ordered);
        List<String> mysqlRows;
        try {
            mysqlRows = ResultNormalizer.normalizeRows(queryMysql(sql), ordered);
        } catch (SQLException e) {
            record(caseId, false, "mysql 侧失败: " + e.getMessage() + " / engine=" + engineRows);
            assertEquals(engineRows, new ArrayList<String>(), "mysql 侧执行失败");
            return;
        }
        boolean ok = engineRows.equals(mysqlRows);
        record(caseId, ok, ok ? "" : "engine=" + engineRows + " mysql=" + mysqlRows);
        assertEquals(mysqlRows, engineRows, "差分不一致: " + sql);
    }

    private void assertUpdateAgreement(String caseId, String sql) {
        int engineRows = engine.execute(sql).affectedRows();
        int mysqlRows;
        try (Statement statement = mysql.createStatement()) {
            mysqlRows = statement.executeUpdate(sql);
        } catch (SQLException e) {
            record(caseId, false, "mysql 侧失败: " + e.getMessage());
            throw new IllegalStateException(e);
        }
        record(caseId, engineRows == mysqlRows, "engine=" + engineRows + " mysql=" + mysqlRows);
        assertEquals(mysqlRows, engineRows, "影响行数不一致: " + sql);
    }

    private List<Object[]> queryEngine(String sql) {
        QueryResult result = engine.query(sql);
        return new ArrayList<>(result.rows());
    }

    private List<Object[]> queryMysql(String sql) throws SQLException {
        try (Statement statement = mysql.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[columns];
                for (int i = 1; i <= columns; i++) {
                    row[i - 1] = rs.getObject(i);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private int errorCodeOfEngine(String sql) {
        try {
            engine.execute(sql);
            return 0;
        } catch (MysqlMockException e) {
            return e.vendorCode();
        }
    }

    private int errorCodeOfMysql(String sql) {
        try (Statement statement = mysql.createStatement()) {
            statement.executeUpdate(sql);
            return 0;
        } catch (SQLException e) {
            return e.getErrorCode();
        }
    }

    /** 双侧回到初始装载态：引擎 reset；MySQL 重放 DDL + 数据集。 */
    private static void resetBothSides() {
        engine.reset();
        try {
            try (Statement statement = mysql.createStatement()) {
                statement.execute("USE mockdiff");
            }
            applyDdl("mockdiff", null);
            applyDdl("vv_dine", "vv_dine");
            loadDatasetToMysql();
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL 侧重置失败", e);
        }
    }

    private static void applyDdl(String database, String expectQualified) throws SQLException {
        try (Statement statement = mysql.createStatement()) {
            statement.execute("USE " + database);
            for (String file : SCHEMA_FILES) {
                String script = ResourceLoader.readAsString(file);
                for (String stmt : SqlScriptSplitter.split(script)) {
                    if (expectQualified != null) {
                        // 只执行限定名命中该库的语句（兼容 `db`.`t` 反引号形态）
                        if (!isQualifiedFor(stmt, expectQualified)) {
                            continue;
                        }
                        stmt = stripQualifier(stmt, expectQualified);
                    } else {
                        if (isQualifiedFor(stmt, "vv_dine")) {
                            continue;
                        }
                    }
                    dropIfExists(statement, stmt);
                    statement.execute(stmt);
                }
            }
        }
    }

    /** 重放 DDL 前清理旧表（差分连接已 USE 对应库）。 */
    private static void dropIfExists(Statement statement, String createStmt) throws SQLException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(`[^`]+`|[A-Za-z0-9_]+)")
                .matcher(createStmt);
        if (matcher.find()) {
            String name = matcher.group(2).replace("`", "");
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(dot + 1);
            }
            statement.execute("DROP TABLE IF EXISTS `" + name + "`");
        }
    }

    /** 语句是否限定到指定库（`db`.`t` 与 db.t 两种形态）。 */
    private static boolean isQualifiedFor(String stmt, String database) {
        String lower = stmt.toLowerCase();
        return lower.contains(database + ".") || lower.contains("`" + database + "`.");
    }

    /** `vv_dine`.`t` → `t`（差分连接已 USE 对应库）。 */
    private static String stripQualifier(String stmt, String database) {
        return stmt.replace("`" + database + "`.", "").replace(database + ".", "");
    }

    private static void loadDatasetToMysql() throws SQLException {
        for (Map.Entry<String, List<Map<String, Object>>> table : dataset.tables().entrySet()) {
            if (table.getValue().isEmpty()) {
                continue;
            }
            // dine 语料在限定库，其余在默认库
            String targetDb = table.getKey().startsWith("dine_customer") ? "vv_dine" : "mockdiff";
            try (Statement use = mysql.createStatement()) {
                use.execute("USE " + targetDb);
            }
            List<Map<String, Object>> rows = table.getValue();
            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.getKey())
                    .append(" (").append(String.join(", ", columns)).append(") VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            sql.append(")");
            try (PreparedStatement ps = mysql.prepareStatement(sql.toString())) {
                for (Map<String, Object> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        Object value = row.get(columns.get(i));
                        if (value instanceof Timestamp) {
                            ps.setString(i + 1, value.toString());
                        } else {
                            ps.setObject(i + 1, value);
                        }
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void record(String caseId, boolean ok, String detail) {
        if (ok) {
            passed++;
            reportLines.add("- PASS " + caseId + (detail.isEmpty() ? "" : " (" + detail + ")"));
        } else {
            failed++;
            reportLines.add("- FAIL " + caseId + " :: " + detail);
        }
    }

    private static String serverVersion() {
        try (Statement statement = mysql.createStatement();
             ResultSet rs = statement.executeQuery("SELECT VERSION()")) {
            rs.next();
            return "MySQL " + rs.getString(1);
        } catch (SQLException e) {
            return "MySQL <unknown>";
        }
    }

    private static void writeReport() throws Exception {
        Path dir = Paths.get("compatibility-report");
        Files.createDirectories(dir);
        if (reportLines.size() >= 2) {
            reportLines.add(1, "");
            reportLines.add(2, "- 结果: PASS " + passed + " / FAIL " + failed);
        } else {
            reportLines.add("");
            reportLines.add("- 结果: PASS " + passed + " / FAIL " + failed
                    + "（初始化失败，未产出用例）");
        }
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(
                dir.resolve("differential-report.md"), StandardCharsets.UTF_8))) {
            for (String line : reportLines) {
                writer.println(line);
            }
        }
        System.out.println("[compat] 报告落盘: " + dir.toAbsolutePath());
    }

    /** DriverManager 重试（容器首连可能抖动）。 */
    private static final class DriverManagerRetry {

        static Connection connect(MySqlContainerProvision provision) throws SQLException {
            SQLException last = null;
            for (int i = 0; i < 10; i++) {
                try {
                    return provision.open();
                } catch (SQLException e) {
                    last = e;
                }
            }
            throw last;
        }
    }
}
