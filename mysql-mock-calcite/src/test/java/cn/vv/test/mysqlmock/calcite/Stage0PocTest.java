package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.UpdateResult;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0 阶段 0 闭环（Go/No-Go）：Calcite 1.42.0 jar 字节码 = 52（JDK 8 可运行，D-01/D-11）；
 * SS 5.3.2 门禁 + Calcite 在现网 DDL（含 STORED 生成列）上 JOIN/GROUP BY/HAVING 与
 * INSERT→UPDATE→DELETE 全链。SS 传递依赖解析清单与 shade 零新增断言分别见 验证记录.md 与 T13。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class Stage0PocTest {

    @Test
    void calciteCoreJarBytecodeIsJdk8Level() throws IOException, java.net.URISyntaxException {
        File jarFile = new File(org.apache.calcite.jdbc.CalciteConnection.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        try (ZipFile jar = new ZipFile(jarFile)) {
            int checked = 0;
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements() && checked < 10) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().contains("$")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        assertTrue(in.read() == 0xCA && in.read() == 0xFE && in.read() == 0xBA
                                && in.read() == 0xBE, "非 class 文件: " + entry.getName());
                        in.read();
                        in.read();
                        int major = (in.read() << 8) | in.read();
                        assertEquals(52, major, "Calcite class 字节码版本应为 52: " + entry.getName());
                        checked++;
                    }
                }
            }
            assertTrue(checked >= 5, "抽检 class 数不足: " + checked);
        }
    }

    @Test
    void fullChainJoinGroupHavingAndDmlOnProductionDdl() {
        try (MysqlMockEngine engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql", "classpath:mysql/orders.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build()) {

            // JOIN + GROUP BY + HAVING（现网 DDL，STORED 生成列参与唯一约束）
            QueryResult joined = engine.query(
                    "SELECT c.id, c.nickname, COUNT(p.id) AS phones "
                            + "FROM dine_customer c LEFT JOIN dine_customer_phone p "
                            + "ON p.customer_id = c.id "
                            + "WHERE c.status = ? GROUP BY c.id, c.nickname "
                            + "HAVING COUNT(p.id) >= ? ORDER BY c.id",
                    "active", 1L);
            assertEquals(1, joined.rowCount());
            assertEquals(1L, joined.rows().get(0)[0]);
            assertEquals("Alex", joined.rows().get(0)[1]);
            assertEquals(2L, joined.rows().get(0)[2]);

            // 金额 DECIMAL scale=3 保留（sku_unit）
            QueryResult sku = engine.query(
                    "SELECT ratio_to_base FROM vv_pc_sku_unit WHERE id = 11");
            assertEquals(0, new BigDecimal("100.000").compareTo(
                    (BigDecimal) sku.rows().get(0)[0]));

            // INSERT：缺省列（phone_dial/status/created_at）+ 生成列 occupies_unique='1'
            UpdateResult inserted = engine.execute(
                    "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                            + "VALUES ('T1', 100, 'Cara', '91230099')");
            assertEquals(1, inserted.affectedRows());
            assertEquals(3L, inserted.generatedKeys().get(0));

            // 生成列承载唯一约束：active 同号再插 → 1062（D-13）
            MysqlMockException dup = assertThrows(MysqlMockException.class,
                    () -> engine.execute(
                            "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                                    + "VALUES ('T1', 100, 'Cara2', '91230099')"));
            assertEquals(MysqlMockException.ER_DUP_ENTRY, dup.vendorCode());

            // UPDATE：自有执行器写回（D-03）
            UpdateResult updated = engine.execute(
                    "UPDATE dine_customer SET nickname = 'Kara' WHERE id = 3");
            assertEquals(1, updated.affectedRows());

            // DELETE：索引同步拆除后，同号可重新占用
            assertEquals(1, engine.execute("DELETE FROM dine_customer WHERE id = 3").affectedRows());
            assertEquals(1, engine.execute(
                    "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                            + "VALUES ('T1', 100, 'New', '91230099')").affectedRows());

            QueryResult count = engine.query(
                    "SELECT COUNT(*) FROM dine_customer WHERE nickname = 'Kara'");
            assertEquals(0L, count.rows().get(0)[0]);
        }
    }
}
