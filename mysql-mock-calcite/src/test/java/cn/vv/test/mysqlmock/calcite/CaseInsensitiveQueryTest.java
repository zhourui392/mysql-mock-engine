package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询侧 _ci 语义对齐 MySQL utf8mb4_general_ci（D-16）：字符串等值、IN、比较、JOIN、GROUP BY、DISTINCT、
 * ORDER BY 忽略大小写与尾随空格；LIKE 忽略大小写但不忽略尾随空格。结果保留存储原值，不改大小写。
 *
 * @author zhourui
 * @since 2026-10-23
 */
class CaseInsensitiveQueryTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder().build();
        engine.executeDdl("CREATE TABLE t_tag (id BIGINT PRIMARY KEY AUTO_INCREMENT, tag VARCHAR(32) NULL, "
                + "code CHAR(8) NULL, qty INT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        engine.executeDdl("CREATE TABLE t_ref (id BIGINT PRIMARY KEY AUTO_INCREMENT, tag VARCHAR(32) NULL) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        engine.execute("INSERT INTO t_tag (tag, code, qty) VALUES ('Paid', 'AB', 1), ('paid', 'ab', 2), "
                + "('PAID ', 'Ab', 3), ('b', NULL, 4), (NULL, 'x', 5)");
        engine.execute("INSERT INTO t_ref (tag) VALUES ('PAID')");
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void equalityIgnoresCaseAndTrailingSpaces() {
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE tag = 'paid'"));
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE tag = ?", "PAID"));
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE code = 'aB  '"));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_tag WHERE tag <> 'PAID'"));
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE tag IN ('PAID', 'zz')"));
        assertEquals(4L, count("SELECT COUNT(*) FROM t_tag WHERE tag IN (?, ?)", "paid", "B"));
    }

    @Test
    void rangeComparisonUsesCaseInsensitiveOrder() {
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE tag BETWEEN 'P' AND 'Q'"));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_tag WHERE tag < 'C'"));
    }

    @Test
    void likeIgnoresCaseButKeepsTrailingSpaces() {
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE tag LIKE 'pa%'"));
        assertEquals(2L, count("SELECT COUNT(*) FROM t_tag WHERE tag LIKE ?", "PAID"));
    }

    @Test
    void joinAndInSubqueryMatchCaseVariants() {
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag a JOIN t_ref r ON a.tag = r.tag"));
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE tag IN (SELECT tag FROM t_ref)"));
    }

    @Test
    void groupByAndDistinctMergeCaseVariantsAndKeepStoredValue() {
        List<Object[]> groups = engine.query(
                "SELECT tag, COUNT(*) AS n, SUM(qty) AS total FROM t_tag GROUP BY tag ORDER BY tag").rows();

        assertEquals(3, groups.size());
        assertEquals(null, groups.get(0)[0]);
        assertEquals("b", groups.get(1)[0]);
        assertTrue(Arrays.asList("Paid", "paid", "PAID ").contains(groups.get(2)[0]));
        assertEquals(3L, ((Number) groups.get(2)[1]).longValue());
        assertEquals(6L, ((Number) groups.get(2)[2]).longValue());
        assertEquals(3L, count("SELECT COUNT(*) FROM (SELECT DISTINCT tag FROM t_tag) d"));
        assertEquals(2L, count("SELECT COUNT(DISTINCT tag) FROM t_tag"));
    }

    @Test
    void orderByIsCaseInsensitiveWithStableTieBreak() {
        List<Object> tags = column("SELECT tag FROM t_tag WHERE tag IS NOT NULL ORDER BY tag DESC, id");

        assertEquals(Arrays.asList("Paid", "paid", "PAID ", "b"), tags);
    }

    @Test
    void updateAndDeleteLocateRowsCaseInsensitively() {
        engine.execute("UPDATE t_tag SET qty = 0 WHERE tag = 'PAID'");
        assertEquals(3L, count("SELECT COUNT(*) FROM t_tag WHERE qty = 0"));

        engine.execute("DELETE FROM t_tag WHERE code = 'ab'");
        assertEquals(2L, count("SELECT COUNT(*) FROM t_tag"));
    }

    @Test
    void caseExpressionAndResultValuesKeepStoredCase() {
        assertEquals(Arrays.asList(1L, 1L, 1L, 0L), column(
                "SELECT CASE WHEN tag = 'PAID' THEN 1 ELSE 0 END FROM t_tag WHERE tag IS NOT NULL ORDER BY id"));
        assertEquals(Arrays.asList("Paid", "paid", "PAID "),
                column("SELECT tag FROM t_tag WHERE tag = 'paid' ORDER BY id"));
    }

    @Test
    void minAndMaxCompareCaseInsensitivelyAndReturnStoredValue() {
        Object[] row = engine.query("SELECT MIN(tag), MAX(tag) FROM t_tag").rows().get(0);

        assertEquals("b", row[0]);
        assertTrue(Arrays.asList("Paid", "paid", "PAID ").contains(row[1]));
        assertEquals("AB", column("SELECT MIN(code) FROM t_tag WHERE id = 1").get(0));
        assertEquals("AB", column("SELECT code FROM t_tag WHERE id = 1").get(0));
    }

    @Test
    void windowPartitionAndOrderMergeCaseVariants() {
        assertEquals(Arrays.asList(3L, 3L, 3L, 1L), column(
                "SELECT COUNT(*) OVER (PARTITION BY tag) FROM t_tag WHERE tag IS NOT NULL ORDER BY id"));
        assertEquals(Arrays.asList(2L, 2L, 2L, 1L), column(
                "SELECT RANK() OVER (ORDER BY tag) FROM t_tag WHERE tag IS NOT NULL ORDER BY id"));
    }

    @Test
    void unionDistinctMergesCaseVariants() {
        assertEquals(2L, count("SELECT COUNT(*) FROM (SELECT tag FROM t_tag WHERE id <= 3 UNION "
                + "SELECT tag FROM t_ref UNION SELECT 'B' FROM t_ref) u"));
        assertEquals(1, engine.query("SELECT tag FROM t_tag WHERE id <= 3 UNION SELECT tag FROM t_ref").rows().size());
        assertEquals(4L, count("SELECT COUNT(*) FROM (SELECT tag FROM t_tag WHERE id <= 3 UNION ALL "
                + "SELECT tag FROM t_ref) u"));
    }

    @Test
    void intersectAndExceptMatchCaseVariants() {
        assertEquals(1, engine.query("SELECT tag FROM t_tag WHERE id = 1 INTERSECT SELECT tag FROM t_ref").rowCount());
        assertEquals(Arrays.asList("b"),
                column("SELECT tag FROM t_tag WHERE tag IS NOT NULL EXCEPT SELECT tag FROM t_ref"));
    }

    @Test
    void windowMinAndMaxCompareCaseInsensitively() {
        assertEquals(Arrays.asList("b", "b", "b", "b"),
                column("SELECT MIN(tag) OVER () FROM t_tag WHERE tag IS NOT NULL ORDER BY id"));
        assertEquals("b", column("SELECT MIN(tag) OVER (ORDER BY id) FROM t_tag WHERE tag IS NOT NULL ORDER BY id")
                .get(3));
        assertEquals(Arrays.asList("PAID ", "PAID "),
                column("SELECT MAX(tag) OVER () FROM t_tag WHERE id IN (3, 4) ORDER BY id"));
    }

    @Test
    void charColumnReportsCharTypeWithoutPadding() {
        QueryResult result = engine.query("SELECT code FROM t_tag WHERE code = 'ab' ORDER BY id");

        assertEquals(Types.CHAR, result.columnTypes().get(0).intValue());
        assertEquals("AB", result.rows().get(0)[0]);
    }

    @Test
    void intersectAllAndExceptAllCountCaseVariants() {
        assertEquals(1, engine.query("SELECT tag FROM t_tag WHERE tag IS NOT NULL INTERSECT ALL "
                + "SELECT tag FROM t_ref").rowCount());
        assertEquals(3, engine.query("SELECT tag FROM t_tag WHERE tag IS NOT NULL EXCEPT ALL "
                + "SELECT tag FROM t_ref").rowCount());
        assertEquals(2, engine.query("SELECT tag FROM t_tag WHERE id <= 2 INTERSECT ALL "
                + "SELECT tag FROM t_tag WHERE id BETWEEN 2 AND 3").rowCount());
    }

    private long count(String sql, Object... params) {
        return ((Number) engine.query(sql, params).rows().get(0)[0]).longValue();
    }

    private List<Object> column(String sql) {
        List<Object> values = new ArrayList<>();
        for (Object[] row : engine.query(sql).rows()) {
            values.add(row[0] instanceof Number ? ((Number) row[0]).longValue() : row[0]);
        }
        return values;
    }
}
