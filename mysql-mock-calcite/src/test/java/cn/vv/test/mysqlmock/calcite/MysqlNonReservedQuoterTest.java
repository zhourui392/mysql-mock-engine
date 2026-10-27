package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MySQL 非保留字作列名：标识符位置加反引号，关键字位置（函数、字面量前缀、INTERVAL / EXTRACT 单位、
 * CAST 目标类型）保持原样，注释与引号串不动。
 *
 * @author zhourui
 * @since 2026-10-23
 */
class MysqlNonReservedQuoterTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder().build();
        engine.executeDdl("CREATE TABLE t_cal (id BIGINT PRIMARY KEY AUTO_INCREMENT, `day` INT NOT NULL, "
                + "`date` DATE NOT NULL, `value` VARCHAR(32) NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        engine.execute("INSERT INTO t_cal (day, date, value) VALUES (?, ?, ?)", 3L, "2026-10-20", "a");
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void identifierUsesAreQuotedAndKeywordUsesAreKept() {
        assertEquals("SELECT t.`value`, `date` FROM t WHERE `day` = ? AND DATE '2026-01-01' < `date`",
                MysqlNonReservedQuoter.quote(
                        "SELECT t.value, date FROM t WHERE day = ? AND DATE '2026-01-01' < date"));
        assertEquals("SELECT DATE_ADD(`date`, INTERVAL 1 DAY), CAST(`value` AS DATE), EXTRACT(YEAR FROM `date`)",
                MysqlNonReservedQuoter.quote(
                        "SELECT DATE_ADD(date, INTERVAL 1 DAY), CAST(value AS DATE), EXTRACT(YEAR FROM date)"));
        assertEquals("INSERT INTO t (`day`, `value`) VALUE (?, ?)",
                MysqlNonReservedQuoter.quote("INSERT INTO t (day, value) VALUE (?, ?)"));
    }

    @Test
    void commentsQuotedTextAndBacktickedNamesAreUntouched() {
        String sql = "SELECT `value`, 'value', \"day\" /* date */ FROM t -- time\nWHERE x = 1";

        assertEquals(sql, MysqlNonReservedQuoter.quote(sql));
        assertEquals(MysqlNonReservedQuoter.quote(sql),
                MysqlNonReservedQuoter.quote(MysqlNonReservedQuoter.quote(sql)));
    }

    @Test
    void engineRunsUnquotedColumnsBesideIntervalAndExtract() {
        QueryResult result = engine.query("SELECT day, value, EXTRACT(DAY FROM date) AS d, "
                + "DATE_ADD(date, INTERVAL 2 DAY) AS next_date FROM t_cal WHERE date >= DATE '2026-10-01' "
                + "ORDER BY day");

        assertEquals(1, result.rowCount());
        Object[] row = result.rows().get(0);
        assertEquals(3L, row[0]);
        assertEquals("a", row[1]);
        assertEquals(20L, ((Number) row[2]).longValue());
        assertEquals("2026-10-22", String.valueOf(row[3]));
    }
}
