package cn.vv.test.mysqlmock.schema;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1 DDL 装载（catalog 侧）：现网语料注册后列/类型/精度/默认/主键/唯一/自增与 DDL 一致；
 * 生成列登记表达式并参与唯一约束；重复注册与悬空索引列显式失败。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class SchemaCatalogTest {

    private SchemaCatalog loadCorpus() {
        SchemaCatalog catalog = new SchemaCatalog();
        for (String statement : SqlScriptSplitter.split(
                cn.vv.test.mysqlmock.api.ResourceLoader.readAsString("classpath:mysql/dine-customer.sql"))) {
            catalog.register(new MySqlDdlParser().parse(statement));
        }
        return catalog;
    }

    @Test
    void registerProductionCorpusAndQueryDefinitions() {
        SchemaCatalog catalog = loadCorpus();
        assertTrue(catalog.exists("dine_customer"));
        assertTrue(catalog.exists("DINE_CUSTOMER_PHONE"), "表名大小写不敏感");
        assertFalse(catalog.exists("no_such"));

        TableDefinition customer = catalog.get("dine_customer");
        assertNotNull(customer);
        assertEquals(2, catalog.all().size());
        assertEquals(java.util.Arrays.asList("dine_customer", "dine_customer_phone"),
                catalog.tableNames(), "注册序保持 DDL 顺序");

        // 列与类型精度
        ColumnDefinition id = customer.column("id");
        assertTrue(id.autoIncrement());
        assertTrue(id.notNull());
        assertEquals(MysqlType.BIGINT, id.type());
        ColumnDefinition phone = customer.column("phone");
        assertEquals(MysqlType.VARCHAR, phone.type());
        assertEquals(32, phone.precision());
        assertFalse(phone.notNull(), "phone 可空（DEFAULT NULL）");

        // 默认值
        assertEquals("852", customer.column("phone_dial").defaultValue());
        assertEquals("active", customer.column("status").defaultValue());

        // 主键 / 唯一 / 生成列
        assertEquals(1, customer.primaryIndex().columns().size());
        IndexDefinition unique = customer.uniqueIndexes().get(0);
        assertEquals(5, unique.columns().size());
        assertTrue(unique.columns().contains("occupies_unique"), "生成列在唯一键内（D-13）");
        assertEquals(ColumnDefinition.GeneratedKind.STORED,
                customer.column("occupies_unique").generatedKind());
    }

    @Test
    void duplicateRegistrationRejected() {
        SchemaCatalog catalog = loadCorpus();
        TableDefinition again = new MySqlDdlParser().parse(
                "CREATE TABLE `dine_customer` (`id` BIGINT PRIMARY KEY)");
        assertThrows(IllegalStateException.class, () -> catalog.register(again));
    }

    @Test
    void indexReferencingUnknownColumnRejected() {
        MySqlDdlParser parser = new MySqlDdlParser();
        TableDefinition bad = parser.parse(
                "CREATE TABLE `t_bad` (`id` BIGINT, KEY `k` (`no_such_col`))");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaCatalog().register(bad));
        assertTrue(e.getMessage().contains("no_such_col"));
    }
}
