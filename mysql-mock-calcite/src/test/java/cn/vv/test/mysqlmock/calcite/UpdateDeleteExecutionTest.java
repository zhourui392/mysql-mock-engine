package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.AffectedRowsMode;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6 UPDATE/DELETE：matched/changed 影响行数口径（D-10）、索引一致性（唯一列改值后旧值可复用）、
 * ON UPDATE CURRENT_TIMESTAMP、生成列重算、DELETE 部分行。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class UpdateDeleteExecutionTest {

    private static final Instant T0 = Instant.parse("2026-10-11T01:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-11T09:00:00Z");

    private MutableClock clock;
    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        engine = MysqlMockEngine.builder()
                .clock(clock)
                .schema("classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql", "classpath:mysql/orders.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void affectedRowsMatchedVsChangedSemantics() {
        engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                + "VALUES ('T1', 100, 'U1', '92220001'), ('T1', 100, 'U2', '92220002')");

        // 一行改值、一行赋原值：matched 口径 = 2（默认，D-10）
        UpdateResult matched = engine.execute(
                "UPDATE dine_customer SET nickname = IF(phone = '92220001', 'U1x', nickname) "
                        + "WHERE phone IN ('92220001', '92220002')");
        assertEquals(2, matched.affectedRows());

        try (MysqlMockEngine changedEngine = MysqlMockEngine.builder()
                .affectedRowsMode(AffectedRowsMode.CHANGED)
                .schema("classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql", "classpath:mysql/orders.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build()) {
            changedEngine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                    + "VALUES ('T1', 100, 'U1', '92220001'), ('T1', 100, 'U2', '92220002')");
            UpdateResult changed = changedEngine.execute(
                    "UPDATE dine_customer SET nickname = IF(phone = '92220001', 'U1x', nickname) "
                        + "WHERE phone IN ('92220001', '92220002')");
            assertEquals(1, changed.affectedRows(), "changed 口径只计值变化的行");
        }
    }

    @Test
    void uniqueIndexMigratesAndOldValueReusable() {
        engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                + "VALUES ('T1', 100, 'U1', '92220001')");
        assertEquals(1, engine.execute(
                "UPDATE dine_customer SET phone = '93330001' WHERE phone = '92220001'").affectedRows());

        // 旧值可复用：索引已随更新迁移
        assertEquals(1, engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                + "VALUES ('T1', 100, 'U1b', '92220001')").affectedRows());

        // 新值占用中，同号 active 冲突 → 1062
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                        + "VALUES ('T1', 100, 'U1c', '93330001')"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, e.vendorCode());
    }

    @Test
    void onUpdateCurrentTimestampFiresOnlyWhenValueChanged() {
        engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                + "VALUES ('T1', 100, 'U1', '92220001')");
        Timestamp created = (Timestamp) engine.query(
                "SELECT created_at, updated_at FROM dine_customer WHERE phone = '92220001'")
                .rows().get(0)[0];

        // 赋原值：无变化 → ON UPDATE 不触发
        engine.execute("UPDATE dine_customer SET nickname = 'U1' WHERE phone = '92220001'");
        Timestamp unchanged = (Timestamp) engine.query(
                "SELECT updated_at FROM dine_customer WHERE phone = '92220001'").rows().get(0)[0];
        assertEquals(created, unchanged);

        // 改值 + 时钟推进 → updated_at 跳到新时钟
        clock.set(T1);
        engine.execute("UPDATE dine_customer SET nickname = 'U1x' WHERE phone = '92220001'");
        Timestamp updated = (Timestamp) engine.query(
                "SELECT updated_at FROM dine_customer WHERE phone = '92220001'").rows().get(0)[0];
        assertNotEquals(created, updated);
        assertEquals(Timestamp.from(T1), updated);
    }

    @Test
    void generatedColumnRecomputedOnUpdateAndReleasesUniqueSlot() {
        // active 同号被唯一键拦截（生成列 occupies_unique='1'）
        engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                + "VALUES ('T1', 100, 'U1', '92220001')");
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                        + "VALUES ('T1', 100, 'U1b', '92220001')"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, e.vendorCode());

        // status → deleted：生成列变 NULL，唯一槽位释放
        assertEquals(1, engine.execute(
                "UPDATE dine_customer SET status = 'deleted' WHERE phone = '92220001'").affectedRows());
        QueryResult slot = engine.query(
                "SELECT occupies_unique FROM dine_customer WHERE phone = '92220001'");
        assertNull(slot.rows().get(0)[0]);

        assertEquals(1, engine.execute("INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                + "VALUES ('T1', 100, 'U1b', '92220001')").affectedRows(), "释放后同号可占用");
    }

    @Test
    void deletePartialRowsAndStateConsistent() {
        assertEquals(1, engine.execute(
                "DELETE FROM dine_customer_phone WHERE customer_id = ? AND is_default = 0", 1L)
                .affectedRows());
        QueryResult remaining = engine.query(
                "SELECT id FROM dine_customer_phone WHERE customer_id = 1 ORDER BY id");
        assertEquals(1, remaining.rowCount());
        assertEquals(101L, remaining.rows().get(0)[0]);

        // 删除后主键值可复用
        assertEquals(1, engine.execute("INSERT INTO dine_customer_phone "
                + "(id, shop_id, tenant_code, customer_id, phone, is_default) "
                + "VALUES (102, 100, 'T1', 1, '92220002', 0)").affectedRows());
        assertTrue(engine.query("SELECT COUNT(*) FROM dine_customer_phone").rowCount() == 1);
    }

    @Test
    void updateWithExpressionAndParams() {
        // SET 表达式经 Calcite 求值：qty/price 算术 + 参数
        engine.executeDdl("CREATE TABLE `t_calc` (`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`qty` INT NOT NULL DEFAULT 1, `price` DECIMAL(18,2) NOT NULL, "
                + "`remark` VARCHAR(64) NULL, PRIMARY KEY (`id`))");
        engine.execute("INSERT INTO t_calc (qty, price, remark) VALUES (2, 10.50, 'x')");
        assertEquals(1, engine.execute(
                "UPDATE t_calc SET qty = qty * ?, price = price + ?, remark = ? WHERE qty = 2",
                3L, new java.math.BigDecimal("0.50"), "y").affectedRows());
        Object[] row = engine.query("SELECT qty, price, remark FROM t_calc WHERE id = 1")
                .rows().get(0);
        assertEquals(6L, row[0]);
        assertEquals(0, new java.math.BigDecimal("11.00").compareTo(
                (java.math.BigDecimal) row[1]));
        assertEquals("y", row[2]);
    }

    /** 可变时钟：验证 ON UPDATE CURRENT_TIMESTAMP 的时间来源。 */
    static final class MutableClock extends java.time.Clock {

        private volatile Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
