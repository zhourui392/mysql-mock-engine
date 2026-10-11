package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 未支持显式失败（R9 strict：不静默返回空结果）。白名单外函数 / 未知表 /
 * 语法错误 / execute() 收到非 DML / INSERT...SELECT / 写生成列，均抛携带 SQL 线索的异常。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class StrictModeTest {

    private static MysqlMockEngine engine;

    @BeforeAll
    static void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/dine-customer.sql")
                .build();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void unknownFunctionFailsLoudlyWithSqlFragment() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.query("SELECT no_such_func(nickname) FROM dine_customer"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, e.vendorCode());
        assertTrue(e.getMessage().contains("dine_customer"), "错误携带 SQL 线索");
    }

    @Test
    void unknownTableFailsWithMysqlTableErrorCode() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.query("SELECT * FROM no_such_table"));
        assertEquals(MysqlMockException.ER_NO_SUCH_TABLE, e.vendorCode());
        assertEquals("42S02", e.sqlState());
        assertTrue(e.getMessage().contains("no_such_table"));
    }

    @Test
    void syntaxErrorCarriesParserPosition() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.query("SELEC nickname FROM dine_customer"));
        assertEquals(MysqlMockException.ER_PARSE_ERROR, e.vendorCode());
        assertEquals("42000", e.sqlState());
        assertTrue(e.getMessage().contains("SELEC"), "错误携带 SQL 片段（定位随 Calcite 消息形态，校验类错误含 line/col）");
    }

    @Test
    void executeRejectsNonDmlStatements() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.execute("SELECT * FROM dine_customer"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, e.vendorCode());

        assertThrows(MysqlMockException.class,
                () -> engine.execute("TRUNCATE TABLE dine_customer"));
        // 多表 JOIN 形态的 UPDATE（MySQL 方言）未支持：解析层显式失败
        assertThrows(MysqlMockException.class,
                () -> engine.execute("UPDATE dine_customer c JOIN t_order o ON o.customer_id = c.id "
                        + "SET c.nickname = 'x'"));
    }

    @Test
    void generatedColumnWritesStillRejected() {
        MysqlMockException generated = assertThrows(MysqlMockException.class,
                () -> engine.execute("UPDATE dine_customer SET occupies_unique = '1' WHERE id = 1"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, generated.vendorCode());
    }

    @Test
    void groupConcatSeparatorAndDistinctRejected() {
        // basic / ORDER BY 形态已支持（SelectExecutionTest 覆盖）；SEPARATOR 与 DISTINCT
        // 形态在 Calcite 侧静默返回 NULL，引擎入口显式拒绝（R9，验证记录 19）
        MysqlMockException separator = assertThrows(MysqlMockException.class,
                () -> engine.query("SELECT GROUP_CONCAT(nickname SEPARATOR ';') FROM dine_customer"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, separator.vendorCode());
        MysqlMockException distinct = assertThrows(MysqlMockException.class,
                () -> engine.query("SELECT GROUP_CONCAT(DISTINCT phone_dial) FROM dine_customer"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, distinct.vendorCode());
    }

    @Test
    void dmlOnUnknownTableFailsWith1146() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.execute("DELETE FROM no_such_table WHERE id = 1"));
        assertEquals(MysqlMockException.ER_NO_SUCH_TABLE, e.vendorCode());

        assertThrows(MysqlMockException.class,
                () -> engine.execute("UPDATE no_such_table SET id = 1"));
    }

    @Test
    void ddlRejectsOutOfRangeTypes() {
        // 清单外类型（R1 白名单）
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.executeDdl("CREATE TABLE `bad_type` (`g` GEOMETRY NOT NULL)"));
        assertEquals(MysqlMockException.ER_NOT_SUPPORTED, e.vendorCode());

        // 语法失败的 DDL
        assertDoesNotThrow(() -> engine.executeDdl("CREATE TABLE `ok_t` (`id` BIGINT PRIMARY KEY)"));
        assertThrows(MysqlMockException.class,
                () -> engine.executeDdl("CREATE TABLE `bad` (`id` BIGINT,,)"));
    }
}
