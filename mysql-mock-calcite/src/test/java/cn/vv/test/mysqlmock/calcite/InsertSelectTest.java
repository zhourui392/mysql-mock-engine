package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * INSERT ... SELECT 语义：源查询经 Calcite 求值后逐行走 INSERT 装配管道
 * （默认值/自增/生成列/约束），冲突仍按 strict 报 1062。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class InsertSelectTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/orders.sql",
                        "classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void insertSelectWithParamsAndCoercion() {
        UpdateResult result = engine.execute(
                "INSERT INTO t_order (customer_id, amount, status) "
                        + "SELECT customer_id, amount, 'PAID' FROM t_order WHERE status = ?", "PENDING");
        assertEquals(1, result.affectedRows(), "PENDING 单被复制");
        assertEquals(5L, result.generatedKeys().get(0), "自增回填");

        Object[] row = engine.query(
                "SELECT customer_id, amount, status FROM t_order WHERE id = 5").rows().get(0);
        assertEquals(1L, row[0]);
        assertEquals(0, new BigDecimal("80.00").compareTo((BigDecimal) row[1]), "DECIMAL 按 scale 归一");
        assertEquals("PAID", row[2]);
    }

    @Test
    void insertSelectColumnListMapsByPosition() {
        assertEquals(1, engine.execute(
                "INSERT INTO t_order_item (price, sku, order_id, qty) "
                        + "SELECT SUM(price), 'AGG', order_id, SUM(qty) FROM t_order_item "
                        + "WHERE order_id = 1 GROUP BY order_id").affectedRows());
        Object[] row = engine.query(
                "SELECT order_id, sku, qty, price FROM t_order_item WHERE sku = 'AGG'")
                .rows().get(0);
        assertEquals(1L, row[0]);
        assertEquals(2L, row[2], "SUM(qty) = 2");
        assertEquals(0, new BigDecimal("60.25").compareTo((BigDecimal) row[3]), "SUM(price) = 60.25");
    }

    @Test
    void insertSelectComputesGeneratedColumns() {
        assertEquals(1, engine.execute(
                "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                        + "SELECT tenant_code, shop_id, 'Copied', phone FROM dine_customer WHERE id = 2")
                .affectedRows(), "源 status=deleted 的生成列为 NULL，不与新行 occupies='1' 冲突");
        Object[] row = engine.query(
                "SELECT occupies_unique, status FROM dine_customer WHERE nickname = 'Copied'")
                .rows().get(0);
        assertEquals("1", row[0], "新行走 status 默认 active → occupies='1'，生成列在 SELECT 源之后计算");
        assertEquals("active", row[1]);
    }

    @Test
    void insertSelectDuplicateStillStrict() {
        MysqlMockException e = assertThrows(MysqlMockException.class, () -> engine.execute(
                "INSERT INTO t_order (id, customer_id, amount, status) "
                        + "SELECT id, customer_id, amount, status FROM t_order"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, e.vendorCode());
        assertEquals(4L, engine.query("SELECT COUNT(*) FROM t_order").rows().get(0)[0],
                "失败后不落部分行（首行即冲突）");
    }

    @Test
    void insertSelectCrossTableJoin() {
        assertEquals(1, engine.execute(
                "INSERT INTO t_order_item (order_id, sku, qty, price) "
                        + "SELECT o.id, CONCAT('SKU-', o.id), 1, o.amount "
                        + "FROM t_order o JOIN dine_customer c ON c.id = o.customer_id "
                        + "WHERE o.status = 'PAID' AND c.status = 'active'").affectedRows(),
                "Bob(deleted) 被过滤，仅 Alex 的 PAID 单命中");
        assertEquals(1L, engine.query(
                "SELECT COUNT(*) FROM t_order_item WHERE sku = 'SKU-1'").rows().get(0)[0],
                "CONCAT('SKU-', o.id) 的目标行");
    }
}
