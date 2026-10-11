package cn.vv.test.mysqlmock.schema;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.ResourceLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1 / T2：现网语料装载与 strict 拒绝。 */
class MySqlDdlParserTest {

    private final MySqlDdlParser parser = new MySqlDdlParser();

    private TableDefinition parseCorpusTable(String resource, String tableName) {
        for (String statement : SqlScriptSplitter.split(ResourceLoader.readAsString(resource))) {
            TableDefinition table = parser.parse(statement);
            if (table.name().equals(tableName)) {
                return table;
            }
        }
        throw new AssertionError("语料中未找到表 " + tableName);
    }

    @Test
    void parseDineCustomerPhoneFromProductionDdl() {
        List<String> statements = SqlScriptSplitter.split(ResourceLoader.readAsString("classpath:mysql/dine-customer.sql"));
        TableDefinition phone = null;
        TableDefinition customer = null;
        for (String statement : statements) {
            TableDefinition table = parser.parse(statement);
            if (table.name().equals("dine_customer_phone")) {
                phone = table;
            } else {
                customer = table;
            }
        }
        assertNotNull(phone);
        assertNotNull(customer);

        // 表级元数据（不影响结果，接受保留）与缺省 collation（D-16：未显式声明按 _ci）
        assertEquals("InnoDB", phone.engine());
        assertEquals(TableDefinition.DEFAULT_COLLATION, phone.collation());
        assertEquals("vv_dine", phone.database());

        // 列：显式生成列（GENERATED ALWAYS AS ... STORED）
        ColumnDefinition occupies = phone.column("occupies_unique");
        assertNotNull(occupies);
        assertEquals(MysqlType.CHAR, occupies.type());
        assertEquals(ColumnDefinition.GeneratedKind.STORED, occupies.generatedKind());
        assertEquals("IF(`released_at` IS NULL, '1', NULL)", occupies.generatedExpr());

        ColumnDefinition guard = phone.column("default_guard");
        assertEquals("IF(`released_at` IS NULL AND `is_default` = 1, '1', NULL)", guard.generatedExpr());

        ColumnDefinition dial = phone.column("phone_dial");
        assertTrue(dial.notNull());
        assertTrue(dial.hasDefault());
        assertEquals("852", dial.defaultValue());

        ColumnDefinition createdAt = phone.column("created_at");
        assertTrue(createdAt.defaultCurrentTimestamp());
        assertTrue(phone.column("updated_at").onUpdateCurrentTimestamp());
        assertFalse(phone.column("released_at").notNull());

        // 索引
        assertEquals(3, phone.indexes().size());
        assertEquals(1, phone.primaryIndex().columns().size());
        assertEquals("id", phone.primaryIndex().columns().get(0));
        assertNull(phone.column("no_such"));
    }

    @Test
    void parseDineCustomerWithUniqueOverGeneratedColumn() {
        TableDefinition customer = parseCorpusTable("classpath:mysql/dine-customer.sql", "dine_customer");
        IndexDefinition unique = customer.uniqueIndexes().get(0);
        assertEquals("uk_customer_phone_active", unique.name());
        assertEquals(5, unique.columns().size());
        assertTrue(unique.columns().contains("occupies_unique"));
        assertEquals("IF(`status` = 'active', '1', NULL)",
                customer.column("occupies_unique").generatedExpr());
        assertEquals("active", customer.column("status").defaultValue(), "'active' 语义默认值保留原文");
    }

    @Test
    void parseSkuUnitWithShorthandGeneratedColumn() {
        TableDefinition skuUnit = parseCorpusTable("classpath:mysql/sku-unit.sql", "vv_pc_sku_unit");
        // 表级 COLLATE
        assertEquals("utf8mb4_general_ci", skuUnit.collation());
        // 简写生成列（无 GENERATED ALWAYS）
        ColumnDefinition marker = skuUnit.column("default_marker");
        assertEquals(ColumnDefinition.GeneratedKind.STORED, marker.generatedKind());
        assertEquals("IF(is_default = 1 AND delete_flag = 0, sku_id, NULL)", marker.generatedExpr());
        assertEquals(MysqlType.BIGINT, marker.type());
        // 内联 PRIMARY KEY + DECIMAL(10,3)
        assertEquals("id", skuUnit.primaryIndex().columns().get(0));
        ColumnDefinition ratio = skuUnit.column("ratio_to_base");
        assertEquals(MysqlType.DECIMAL, ratio.type());
        assertEquals(10, ratio.precision());
        assertEquals(3, ratio.scale());
        assertEquals(2, skuUnit.uniqueIndexes().size());
    }

    @Test
    void ifNotExistsFlagDetected() {
        assertTrue(parser.isIfNotExists("CREATE TABLE IF NOT EXISTS `t` (`id` INT)"));
        assertFalse(parser.isIfNotExists("CREATE TABLE `t` (`id` INT)"));
    }

    @Test
    void rejectForeignKey() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`id` BIGINT, `a` BIGINT, "
                        + "CONSTRAINT `fk_t` FOREIGN KEY (`a`) REFERENCES `o` (`id`))"));
        assertTrue(e.getMessage().contains("外键"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, e.vendorCode());
    }

    @Test
    void rejectInlineReferences() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`id` BIGINT, `a` BIGINT REFERENCES `o`(`id`))"));
        assertTrue(e.getMessage().contains("外键"));
    }

    @Test
    void rejectCheckConstraint() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`n` INT, CHECK (`n` > 0))"));
        assertTrue(e.getMessage().contains("CHECK"));
    }

    @Test
    void rejectVirtualGeneratedColumn() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`id` BIGINT, `g` INT AS (`id` + 1) VIRTUAL)"));
        assertTrue(e.getMessage().contains("VIRTUAL"));
        assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`id` BIGINT, `g` INT AS (`id` + 1))"));
    }

    @Test
    void rejectCollationOutsideWhitelist() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`s` VARCHAR(8) COLLATE utf8mb4_bin) "
                        + "COLLATE=utf8mb4_0900_ai_ci"));
        assertTrue(e.getMessage().contains("collation"));
    }

    @Test
    void rejectCharsetOutsideWhitelist() {
        assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`s` VARCHAR(8)) DEFAULT CHARSET=latin1"));
    }

    @Test
    void rejectTypeOutsideWhitelist() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`b` BLOB)"));
        assertTrue(e.getMessage().contains("白名单"));
    }

    @Test
    void rejectSyntaxErrorViaGatekeeper() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("CREATE TABLE `t` (`id` BIGINT"));
        assertEquals(MysqlMockException.ER_PARSE_ERROR, e.vendorCode());
    }

    @Test
    void rejectNonCreateStatement() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> parser.parse("ALTER TABLE `t` ADD COLUMN `x` INT"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, e.vendorCode());
    }

    @Test
    void scriptSplitterIgnoresSemicolonsInStringsAndComments() {
        List<String> statements = SqlScriptSplitter.split(
                "CREATE TABLE `a` (`x` VARCHAR(10) DEFAULT 'a;b') -- 注释;分号\n;"
                        + "CREATE TABLE `b` (`y` INT)\n");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'a;b'"));
        assertEquals("CREATE TABLE `b` (`y` INT)", statements.get(1));
    }
}
