package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 INSERT 语义：默认值填充、自增回填 generatedKeys、重复主键 1062/23000、
 * 清单内 collation 大小写变体唯一键 1062（_ci + PAD SPACE，D-16）、NOT NULL 1048、生成列。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class InsertExecutionTest {

    private static final Instant FIXED = Instant.parse("2026-10-11T02:03:04.567Z");

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder()
                .mysqlVersion("8.0")
                .clock(java.time.Clock.fixed(FIXED, ZoneId.systemDefault()))
                .schema("classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql", "classpath:mysql/orders.sql")
                .build();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void defaultsAutoIncrementAndGeneratedKeysBackfill() {
        UpdateResult result = engine.execute(
                "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                        + "VALUES ('T1', 100, 'Cara', '91230099')");
        assertEquals(1, result.affectedRows());
        assertEquals(1L, result.generatedKeys().get(0), "自增从 1 起（空表）");

        QueryResult row = engine.query(
                "SELECT phone_dial, status, occupies_unique, created_at, updated_at "
                        + "FROM dine_customer WHERE id = 1");
        Object[] values = row.rows().get(0);
        assertEquals("852", values[0], "phone_dial 缺省默认值");
        assertEquals("active", values[1], "status 语义默认值");
        assertEquals("1", values[2], "生成列 occupies_unique=IF(status='active','1',NULL)");
        assertEquals(Timestamp.from(FIXED), values[3], "CURRENT_TIMESTAMP 默认取引擎时钟（D-09）");
    }

    @Test
    void duplicatePrimaryKeyRejectedWithMysqlSemantics() {
        engine.execute("INSERT INTO dine_customer (id, tenant_code, shop_id, nickname, phone) "
                + "VALUES (5, 'T1', 100, 'A', '90000001')");
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO dine_customer (id, tenant_code, shop_id, nickname, phone) "
                        + "VALUES (5, 'T1', 100, 'B', '90000002')"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, e.vendorCode());
        assertEquals("23000", e.sqlState());
        assertEquals(1L, engine.query("SELECT COUNT(*) FROM dine_customer").rows().get(0)[0]);
    }

    @Test
    void caseInsensitiveUniqueWithinWhitelistedCollation() {
        engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, tenant_code) "
                + "VALUES (2001, 'Box', 1.0, 'T1')");
        // unit 'box' 与 'Box' 在 utf8mb4_general_ci 下冲突 → 1062（D-16）
        MysqlMockException ci = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, tenant_code) "
                        + "VALUES (2001, 'box', 1.0, 'T1')"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, ci.vendorCode());

        // PAD SPACE：尾随空格忽略，'Box ' 与 'Box' 冲突
        MysqlMockException pad = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, tenant_code) "
                        + "VALUES (2001, 'Box ', 1.0, 'T1')"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, pad.vendorCode());
    }

    @Test
    void notNullViolationRejected() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO dine_customer (tenant_code, nickname) "
                        + "VALUES ('T1', 'NoShop')"));
        assertEquals(MysqlMockException.ER_BAD_NULL_ERROR, e.vendorCode());
        assertEquals("23000", e.sqlState());
        assertTrue(e.getMessage().contains("shop_id"));
    }

    @Test
    void storedGeneratedColumnReceivesValuesAndGuardsUnique() {
        // sku_unit.default_marker = IF(is_default=1 AND delete_flag=0, sku_id, NULL)：每 SKU 至多一个默认
        engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, is_default, tenant_code) "
                + "VALUES (3001, '瓶', 1.0, 1, 'T1')");
        QueryResult marker = engine.query(
                "SELECT default_marker FROM vv_pc_sku_unit WHERE sku_id = 3001 AND is_default = 1");
        assertEquals(3001L, marker.rows().get(0)[0], "生成列求值 = sku_id");

        MysqlMockException second = assertThrows(MysqlMockException.class,
                () -> engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, is_default, tenant_code) "
                        + "VALUES (3001, '箱', 6.0, 1, 'T1')"));
        assertEquals(MysqlMockException.ER_DUP_ENTRY, second.vendorCode(),
                "同 SKU 第二个默认单位被生成列唯一键拦截（D-13 现网不变量）");

        // 非默认单位 marker 为 NULL，不参与唯一
        assertEquals(1, engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, is_default, tenant_code) "
                + "VALUES (3001, '件', 12.0, 0, 'T1')").affectedRows());
        assertNull(engine.query("SELECT default_marker FROM vv_pc_sku_unit WHERE unit = '件'")
                .rows().get(0)[0]);
    }

    @Test
    void decimalColumnScaleNormalizedOnInsert() {
        engine.execute("INSERT INTO vv_pc_sku_unit (sku_id, unit, ratio_to_base, tenant_code) "
                + "VALUES (4001, '瓶', 2.5, 'T1')");
        Object value = engine.query(
                "SELECT ratio_to_base FROM vv_pc_sku_unit WHERE sku_id = 4001").rows().get(0)[0];
        assertEquals(new BigDecimal("2.500"), value, "DECIMAL(10,3) 按列 scale 归一");
    }

    @Test
    void multiRowInsertWithParamsAndDefaults() {
        UpdateResult result = engine.execute(
                "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) VALUES "
                        + "(?, 100, ?, ?), (?, 100, ?, ?)",
                "T1", "M1", "91110001", "T1", "M2", "91110002");
        assertEquals(2, result.affectedRows());
        assertEquals(1L, result.generatedKeys().get(0));
        assertEquals(2L, result.generatedKeys().get(1), "自增连续分配");
        assertEquals(new BigDecimal("2"), BigDecimal.valueOf(
                (Long) engine.query("SELECT COUNT(*) FROM dine_customer").rows().get(0)[0]));
    }

    @Test
    void datasetLoadingAppliesTypeCoercion() {
        engine.loadDataset(cn.vv.test.mysqlmock.api.ResourceLoader.open("classpath:datasets/dine-case.yml"));
        Object ratio = engine.query("SELECT ratio_to_base FROM vv_pc_sku_unit WHERE id = 11")
                .rows().get(0)[0];
        assertEquals(new BigDecimal("100.000"), ratio);
        Object created = engine.query("SELECT created_at FROM vv_pc_sku_unit WHERE id = 11")
                .rows().get(0)[0];
        assertEquals(Timestamp.valueOf("2026-01-02 03:04:05.678"), created, "毫秒精度保留（D-08）");
    }
}
