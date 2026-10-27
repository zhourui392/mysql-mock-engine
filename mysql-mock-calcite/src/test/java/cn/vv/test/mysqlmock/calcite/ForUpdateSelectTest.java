package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-05：SELECT ... FOR UPDATE 按普通查询执行（内存库无锁语义）；
 * JSON_CONTAINS_PATH 仍显式失败。
 *
 * @author zhourui
 * @since 2026-10-23
 */
class ForUpdateSelectTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/dine-customer.sql")
                .build();
        engine.execute(
                "INSERT INTO dine_customer (tenant_code, shop_id, nickname, phone) "
                        + "VALUES ('t1', 1, 'a', '10001'), ('t1', 1, 'b', '10002')");
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void selectForUpdateReturnsMatchingRows() {
        QueryResult result = engine.query(
                "SELECT nickname FROM dine_customer WHERE phone = '10001' FOR UPDATE");
        assertEquals(1, result.rowCount());
        assertEquals("a", result.rows().get(0)[0]);
    }

    @Test
    void selectForUpdateAllowsTrailingWhitespaceAndSemicolon() {
        QueryResult result = engine.query(
                "SELECT nickname FROM dine_customer WHERE phone = '10002' FOR UPDATE  ;  ");
        assertEquals(1, result.rowCount());
        assertEquals("b", result.rows().get(0)[0]);
    }

    @Test
    void jsonContainsPathStillUnsupported() {
        MysqlMockException e = assertThrows(MysqlMockException.class,
                () -> engine.query(
                        "SELECT id FROM dine_customer WHERE JSON_CONTAINS_PATH(nickname, 'one', '$.x')"));
        assertTrue(e.getMessage().toLowerCase().contains("json")
                        || e.vendorCode() == MysqlMockException.ER_NOT_SUPPORTED
                        || e.vendorCode() == MysqlMockException.ER_PARSE_ERROR,
                "应显式失败: " + e.getMessage());
    }
}
