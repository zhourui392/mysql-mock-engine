package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.QueryResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4 白名单 SELECT：LEFT JOIN + SUM + GROUP BY + HAVING + ORDER BY + LIMIT（含 MySQL
 * 方言 LIMIT offset,count 与参数绑定）；限定名 database.table 查询。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class SelectExecutionTest {

    private static MysqlMockEngine engine;

    @BeforeAll
    static void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/orders.sql",
                        "classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void leftJoinSumGroupHavingOrderLimitWithParams() {
        QueryResult result = engine.query(
                "SELECT o.customer_id, c.nickname, SUM(i.qty * i.price) AS total, COUNT(i.id) AS items "
                        + "FROM t_order o "
                        + "LEFT JOIN t_order_item i ON i.order_id = o.id "
                        + "LEFT JOIN dine_customer c ON c.id = o.customer_id "
                        + "WHERE o.status = ? AND o.amount >= ? "
                        + "GROUP BY o.customer_id, c.nickname "
                        + "HAVING SUM(i.qty * i.price) > ? "
                        + "ORDER BY total DESC, o.customer_id LIMIT 1, 2",
                "PAID", new BigDecimal("100.00"), new BigDecimal("100.00"));
        // 命中分组：customer 1（120.50，2 件）、customer 2（300.00，1 件）；customer 3 无明细被 HAVING 过滤；
        // ORDER BY total DESC → Bob(300) 在前；LIMIT 1, 2 跳过 Bob 仅剩 Alex
        assertEquals(1, result.rowCount());
        assertEquals("Alex", result.rows().get(0)[1]);
        assertEquals(0, new BigDecimal("120.50").compareTo((BigDecimal) result.rows().get(0)[2]));
        assertEquals(1L, result.rows().get(0)[3]);
    }

    @Test
    void mysqlDialectLimitOffsetCountAndNoRows() {
        QueryResult result = engine.query(
                "SELECT id FROM t_order ORDER BY id LIMIT 1, 3");
        assertEquals(3, result.rowCount());
        assertEquals(2L, result.rows().get(0)[0]);

        QueryResult empty = engine.query(
                "SELECT id FROM t_order WHERE status = ?", "NO_SUCH");
        assertEquals(0, empty.rowCount());
    }

    @Test
    void qualifiedDatabaseTableReference() {
        QueryResult result = engine.query(
                "SELECT COUNT(*) FROM vv_dine.dine_customer WHERE status = 'active'");
        assertEquals(1L, result.rows().get(0)[0]);
    }

    @Test
    void builtinAndUdfFunctions() {
        QueryResult result = engine.query(
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS d, "
                        + "IFNULL(NULL, 'fallback') AS fb, IF(1 = 1, 'yes', 'no') AS pick "
                        + "FROM vv_pc_sku_unit WHERE id = 11");
        assertEquals(1, result.rowCount());
        assertEquals("2026-01-02", result.rows().get(0)[0]);
        assertEquals("fallback", result.rows().get(0)[1]);
        assertEquals("yes", result.rows().get(0)[2]);
    }

    @Test
    void timestampSemanticsInSelect() {
        // 无精度 DATETIME：装载即按 MySQL 秒级四舍五入（差分对齐）
        QueryResult result = engine.query(
                "SELECT created_at FROM vv_pc_sku_unit WHERE id = 11");
        assertEquals(java.sql.Timestamp.valueOf("2026-01-02 03:04:06"),
                result.rows().get(0)[0]);
        // 声明 DATETIME(3)：毫秒精度保留（D-08）
        engine.executeDdl("CREATE TABLE `t_prec` (`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`ts` DATETIME(3) NULL, PRIMARY KEY (`id`))");
        engine.execute("INSERT INTO t_prec (ts) VALUES ('2026-01-02 03:04:05.678')");
        assertEquals(java.sql.Timestamp.valueOf("2026-01-02 03:04:05.678"),
                engine.query("SELECT ts FROM t_prec WHERE id = 1").rows().get(0)[0]);
    }
}
