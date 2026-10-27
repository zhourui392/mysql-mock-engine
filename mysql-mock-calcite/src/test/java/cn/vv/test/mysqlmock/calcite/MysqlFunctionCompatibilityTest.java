package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 函数与写法补齐：dine 各服务 mapper 实际用到、此前引擎跑不了的函数，
 * 加上同类常用写法。期望值取 MySQL 8.0 utf8mb4_general_ci 下的结果。
 *
 * @author zhourui
 * @since 2026-10-23
 */
class MysqlFunctionCompatibilityTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder().build();
        engine.executeDdl("CREATE TABLE t_fn (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64) NULL, "
                + "tags VARCHAR(64) NULL, attrs JSON NULL, amount DECIMAL(10,2) NULL, created_at DATETIME NULL, "
                + "biz_date DATE NULL, grp INT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        engine.execute("INSERT INTO t_fn (name, tags, attrs, amount, created_at, biz_date, grp) VALUES "
                + "(' Ab c ', 'a,b', '{\"k\":[1,2],\"s\":\"x\",\"o\":{\"n\":1}}', 1.50, '2026-10-20 08:30:00', "
                + "'2026-10-20', 1), ('zz', NULL, NULL, 2.00, '2026-10-21 23:00:00', '2026-10-21', 1), "
                + "('Ab c', 'c', '[1,2,3]', 3.00, '2026-10-22 00:00:00', '2026-10-22', 2)");
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void trimAndCastForms() {
        assertEquals("ab c", one("SELECT LOWER(REPLACE(TRIM(name), '  ', ' ')) FROM t_fn WHERE id = 1"));
        assertEquals("2026-10", one("SELECT SUBSTRING(CAST(created_at AS CHAR), 1, 7) FROM t_fn WHERE id = 1"));
        assertEquals(2L, num("SELECT CAST(amount AS SIGNED) FROM t_fn WHERE id = 2"));
        assertEquals(3L, num("SELECT CAST(amount AS UNSIGNED INTEGER) FROM t_fn WHERE id = 3"));
        assertEquals("b c", one("SELECT MID(name, 3, 3) FROM t_fn WHERE id = 1"));
        assertEquals("Ab c", one("SELECT CONVERT(name USING utf8mb4) FROM t_fn WHERE id = 3"));
    }

    @Test
    void groupConcatSeparatorDistinctAndOrder() {
        assertEquals("zz;  Ab c ", one("SELECT GROUP_CONCAT(name ORDER BY id DESC SEPARATOR '; ') FROM t_fn WHERE grp = 1"));
        assertEquals("1,2", one("SELECT GROUP_CONCAT(DISTINCT grp ORDER BY grp) FROM t_fn"));
        assertEquals("1|2", one("SELECT GROUP_CONCAT(DISTINCT grp ORDER BY grp SEPARATOR '|') FROM t_fn"));
    }

    @Test
    void jsonFunctionsAndArrowOperators() {
        assertEquals(1L, num("SELECT JSON_CONTAINS_PATH(attrs, 'one', '$.k', '$.missing') FROM t_fn WHERE id = 1"));
        assertEquals(0L, num("SELECT JSON_CONTAINS_PATH(attrs, 'all', '$.k', '$.missing') FROM t_fn WHERE id = 1"));
        assertEquals(1L, num("SELECT COUNT(*) FROM t_fn WHERE JSON_CONTAINS_PATH(attrs, 'one', '$.o.n')"));
        assertEquals("\"x\"", one("SELECT JSON_EXTRACT(attrs, '$.s') FROM t_fn WHERE id = 1"));
        assertEquals("[1, 2]", one("SELECT JSON_EXTRACT(attrs, '$.k') FROM t_fn WHERE id = 1"));
        assertEquals("2", one("SELECT JSON_EXTRACT(attrs, '$[1]') FROM t_fn WHERE id = 3"));
        assertEquals("\"x\"", one("SELECT attrs->'$.s' FROM t_fn WHERE id = 1"));
        assertEquals("x", one("SELECT t.attrs->>'$.s' FROM t_fn t WHERE t.id = 1"));
        assertEquals("x", one("SELECT JSON_UNQUOTE(JSON_EXTRACT(attrs, '$.s')) FROM t_fn WHERE id = 1"));
        assertEquals(1L, num("SELECT JSON_CONTAINS(attrs, '2', '$.k') FROM t_fn WHERE id = 1"));
        assertEquals(1L, num("SELECT JSON_CONTAINS(attrs, '[1,3]') FROM t_fn WHERE id = 3"));
        assertEquals(0L, num("SELECT JSON_CONTAINS(attrs, '{\"s\":\"y\"}') FROM t_fn WHERE id = 1"));
        assertEquals("\"$.s\"", one("SELECT JSON_SEARCH(attrs, 'one', 'x') FROM t_fn WHERE id = 1"));
    }

    @Test
    void mapperStyleParametersAndBarePredicates() {
        assertEquals(1L, num("SELECT COUNT(*) FROM t_fn p WHERE (JSON_CONTAINS_PATH(p.attrs, 'one', "
                + "CONCAT('$.\"', ?, '\"')) = 1 OR p.id = 0)", "s"));
        assertEquals(1L, num("SELECT COUNT(*) FROM t_fn WHERE grp = 1 AND JSON_CONTAINS(attrs, ?, '$.k')", "1"));
        assertEquals(1L, num("SELECT COUNT(*) FROM t_fn WHERE FIND_IN_SET(?, tags)", "b"));
        assertEquals(2L, num("SELECT COUNT(*) FROM t_fn WHERE LOCATE(?, name) > 0", "AB"));
        assertEquals("2026-10-25", one("SELECT CAST(TIMESTAMPADD(DAY, ?, biz_date) AS VARCHAR) FROM t_fn "
                + "WHERE id = 1", 5L));
    }

    @Test
    void stringAndPredicateFunctions() {
        assertEquals(3L, num("SELECT LOCATE('b', name) FROM t_fn WHERE id = 1"));
        assertEquals(0L, num("SELECT LOCATE('b', name, 4) FROM t_fn WHERE id = 1"));
        assertEquals(2L, num("SELECT FIELD(tags, 'x', 'A,B', 'c') FROM t_fn WHERE id = 1"));
        assertEquals(2L, num("SELECT COUNT(*) FROM t_fn WHERE name REGEXP '^ ?ab'"));
        assertEquals(1L, num("SELECT COUNT(*) FROM t_fn WHERE name NOT REGEXP ?", "b"));
        assertEquals(1L, num("SELECT COUNT(*) FROM t_fn WHERE ISNULL(tags)"));
        assertEquals(36, one("SELECT UUID() FROM t_fn WHERE id = 1").length());
    }

    @Test
    void dateFunctions() {
        assertEquals("2026-10-23", one("SELECT CAST(DATE_ADD(biz_date, INTERVAL ? DAY) AS VARCHAR) FROM t_fn "
                + "WHERE id = 1", 3L));
        assertEquals("2026-10-19 08:30:00", one("SELECT CAST(DATE_SUB(created_at, INTERVAL ? HOUR) AS VARCHAR) "
                + "FROM t_fn WHERE id = 1", 24L));
        assertEquals(1L, num("SELECT DATEDIFF(created_at, '2026-10-20 23:59:59') FROM t_fn WHERE id = 2"));
        assertEquals(21L, num("SELECT DAY(created_at) FROM t_fn WHERE id = 2"));
        assertEquals(740274L, num("SELECT TO_DAYS(biz_date) FROM t_fn WHERE id = 1"));
        assertEquals("2026-01-10", one("SELECT CAST(MAKEDATE(2026, 10) AS VARCHAR) FROM t_fn WHERE id = 1"));
        assertEquals("2026-10-20 08:30:00", one("SELECT CAST(STR_TO_DATE('20/10/2026 08:30', '%d/%m/%Y %H:%i') "
                + "AS VARCHAR) FROM t_fn WHERE id = 1"));
        assertEquals(Timestamp.valueOf("2026-10-20 08:30:00").getTime() / 1000,
                num("SELECT UNIX_TIMESTAMP(created_at) FROM t_fn WHERE id = 1"));
        assertEquals("2026-10-20 08:30:00", one("SELECT CAST(FROM_UNIXTIME(?) AS VARCHAR) FROM t_fn WHERE id = 1",
                Timestamp.valueOf("2026-10-20 08:30:00").getTime() / 1000));
        assertEquals(1L, num("SELECT WEEKDAY(biz_date) FROM t_fn WHERE id = 1"));
        long nowSeconds = System.currentTimeMillis() / 1000;
        assertTrue(Math.abs(num("SELECT UNIX_TIMESTAMP() FROM t_fn WHERE id = 1") - nowSeconds) < 5);
    }

    @Test
    void jsonWildcardPaths() {
        assertEquals("[1, 2]", one("SELECT JSON_EXTRACT(attrs, '$.k[*]') FROM t_fn WHERE id = 1"));
        assertEquals("[1]", one("SELECT JSON_EXTRACT(attrs, '$.o.*') FROM t_fn WHERE id = 1"));
        assertEquals("[1]", one("SELECT JSON_EXTRACT(attrs, '$**.n') FROM t_fn WHERE id = 1"));
        assertEquals("[1, 2, 3]", one("SELECT attrs->'$[*]' FROM t_fn WHERE id = 3"));
        assertEquals(null, one("SELECT JSON_EXTRACT(attrs, '$.none[*]') FROM t_fn WHERE id = 1"));
        assertEquals(1L, num("SELECT JSON_CONTAINS_PATH(attrs, 'one', '$.o.*') FROM t_fn WHERE id = 1"));
        assertEquals(0L, num("SELECT JSON_CONTAINS_PATH(attrs, 'one', '$.s[*]') FROM t_fn WHERE id = 1"));
    }

    @Test
    void jsonBuildAndMutateKeepValueTypesAndMysqlFormat() {
        assertEquals("{\"a\": \"x\", \"b\": 2}", one("SELECT JSON_SET('{\"a\":1}', '$.b', 2, '$.a', 'x') FROM t_fn WHERE id = 1"));
        assertEquals("{\"c\": [1, 2]}", one("SELECT JSON_SET('{}', '$.c', JSON_ARRAY(1, 2)) FROM t_fn WHERE id = 1"));
        assertEquals("{\"c\": \"v\"}", one("SELECT JSON_SET('{}', '$.c', ?) FROM t_fn WHERE id = 1", "v"));
        assertEquals("{\"a\": 1, \"b\": 3}", one("SELECT JSON_INSERT('{\"a\":1}', '$.a', 2, '$.b', 3) FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": 2}", one("SELECT JSON_REPLACE('{\"a\":1}', '$.a', 2, '$.b', 3) FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": 1, \"b\": [2]}", one("SELECT JSON_REMOVE('{\"a\":1,\"b\":[1,2]}', '$.b[0]') FROM t_fn WHERE id = 1"));
        assertEquals("{\"b\": 1, \"aa\": {\"z\": 2}}", one("SELECT JSON_OBJECT('aa', JSON_OBJECT('z', 2), 'b', 1) FROM t_fn WHERE id = 1"));
        assertEquals("[1, \"a\"]", one("SELECT JSON_ARRAY(1, 'a') FROM t_fn WHERE id = 1"));
        assertEquals("[\"a\", \"b\"]", one("SELECT JSON_KEYS('{\"b\":1,\"a\":2}') FROM t_fn WHERE id = 1"));
        assertEquals("[1, 1, 2]", one("SELECT JSON_ARRAYAGG(grp) FROM t_fn"));
        assertEquals("\"a\\\"b\"", one("SELECT JSON_QUOTE('a\"b') FROM t_fn WHERE id = 1"));
        assertEquals(1L, num("SELECT JSON_OVERLAPS('[1,2]', '[2,3]') FROM t_fn WHERE id = 1"));
        assertEquals(0L, num("SELECT COUNT(*) FROM t_fn WHERE JSON_OVERLAPS(attrs, ?)", "[9]"));
    }

    @Test
    void sessionAndScalarFunctions() {
        engine.execute("INSERT INTO t_fn (name) VALUES ('n1'), ('n2')");
        engine.execute("INSERT INTO t_fn (id, name) VALUES (100, 'explicit')");
        assertEquals(4L, num("SELECT LAST_INSERT_ID()"));
        assertEquals("08:30:00", one("SELECT TIME(created_at) FROM t_fn WHERE id = 1"));
        assertEquals("08:30:15", one("SELECT TIME('2026-10-20 08:30:15') FROM t_fn WHERE id = 1"));
        assertEquals("08:30:00", one("SELECT CAST(created_at AS TIME) FROM t_fn WHERE id = 1"));
        assertTrue(one("SELECT CURTIME() FROM t_fn WHERE id = 1").matches("\\d{2}:\\d{2}:\\d{2}"));
        assertEquals(-1L, num("SELECT STRCMP('a', 'b') FROM t_fn WHERE id = 1"));
        assertEquals(1L, num("SELECT STRCMP(name, 'AB') FROM t_fn WHERE id = 3"));
        assertEquals(0L, num("SELECT STRCMP('A', 'a ') FROM t_fn WHERE id = 1"));
        assertEquals("FF", one("SELECT HEX(255) FROM t_fn WHERE id = 1"));
        assertEquals("6162", one("SELECT HEX('ab') FROM t_fn WHERE id = 1"));
        assertEquals("6162", one("SELECT HEX(?) FROM t_fn WHERE id = 1", "ab"));
    }

    @Test
    void jsonLastAndRangePaths() {
        assertEquals("3", one("SELECT JSON_EXTRACT(attrs, '$[last]') FROM t_fn WHERE id = 3"));
        assertEquals("2", one("SELECT JSON_EXTRACT(attrs, '$[last - 1]') FROM t_fn WHERE id = 3"));
        assertEquals("[2, 3]", one("SELECT JSON_EXTRACT(attrs, '$[1 to last]') FROM t_fn WHERE id = 3"));
        assertEquals("[1, 2]", one("SELECT JSON_EXTRACT(attrs, '$[0 to 1]') FROM t_fn WHERE id = 3"));
        assertEquals("[1, 2, 9]", one("SELECT JSON_SET(attrs, '$[last]', 9) FROM t_fn WHERE id = 3"));
        assertEquals("[1, 2]", one("SELECT JSON_REMOVE(attrs, '$[last]') FROM t_fn WHERE id = 3"));
    }

    @Test
    void jsonColumnIsStoredInMysqlFormat() {
        assertEquals("{\"k\": [1, 2], \"o\": {\"n\": 1}, \"s\": \"x\"}", one("SELECT attrs FROM t_fn WHERE id = 1"));
        engine.execute("UPDATE t_fn SET attrs = ? WHERE id = 2", "{\"bb\":true,\"a\":null}");
        assertEquals("{\"a\": null, \"bb\": true}", one("SELECT attrs FROM t_fn WHERE id = 2"));

        MysqlMockException invalid = assertThrows(MysqlMockException.class,
                () -> engine.execute("UPDATE t_fn SET attrs = 'not json' WHERE id = 2"));
        assertEquals(3140, invalid.vendorCode());
    }

    @Test
    void lastInsertIdWithExpressionSetsTheValue() {
        engine.execute("UPDATE t_fn SET grp = LAST_INSERT_ID(grp + 10) WHERE id = 3");
        assertEquals(12L, num("SELECT LAST_INSERT_ID()"));
        assertEquals(12L, num("SELECT grp FROM t_fn WHERE id = 3"));
        assertEquals(42L, num("SELECT LAST_INSERT_ID(42)"));
        assertEquals(42L, num("SELECT LAST_INSERT_ID()"));
    }

    @Test
    void jsonModifyWrapsScalarsAndKeepsDecimalScale() {
        assertEquals("{\"a\": [1, 2]}", one("SELECT JSON_SET('{\"a\":1}', '$.a[1]', 2) FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": 2}", one("SELECT JSON_SET('{\"a\":1}', '$.a[0]', 2) FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": [1, 2]}", one("SELECT JSON_INSERT('{\"a\":1}', '$.a[1]', 2) FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": 1}", one("SELECT JSON_REPLACE('{\"a\":1}', '$.a[1]', 2) FROM t_fn WHERE id = 1"));
        assertEquals("[[1, 2]]", one("SELECT JSON_SET('[1]', '$[0][1]', 2) FROM t_fn WHERE id = 1"));
        assertEquals("{\"d\": 1.50}", one("SELECT JSON_SET('{}', '$.d', amount) FROM t_fn WHERE id = 1"));
        assertEquals("{\"d\": 1.50}", one("SELECT JSON_OBJECT('d', amount) FROM t_fn WHERE id = 1"));
        assertEquals("{\"d\": 1.5}", one("SELECT JSON_EXTRACT('{\"d\":1.50}', '$') FROM t_fn WHERE id = 1"));
    }

    @Test
    void jsonModifyBindParametersKeepJavaTypes() {
        assertEquals("{\"n\": 5}", one("SELECT JSON_SET('{}', '$.n', ?) FROM t_fn WHERE id = 1", 5L));
        assertEquals("{\"n\": 1.50}", one("SELECT JSON_SET('{}', '$.n', ?) FROM t_fn WHERE id = 1",
                new java.math.BigDecimal("1.50")));
        assertEquals("{\"a\": 1.50, \"b\": true}", one("SELECT JSON_SET('{}', '$.a', amount, '$.b', ?) FROM t_fn "
                + "WHERE id = 1", Boolean.TRUE));
        engine.execute("UPDATE t_fn SET attrs = json_set(COALESCE(attrs, '{}'), concat('$.', ?), ?) WHERE id = 2", "cfg", 7L);
        assertEquals("{\"cfg\": 7}", one("SELECT attrs FROM t_fn WHERE id = 2"));
    }

    @Test
    void nestedJsonFunctionsKeepDecimalAndJsonTextReadsAsDouble() {
        assertEquals("1.50", one("SELECT JSON_EXTRACT(JSON_SET('{}', '$.a', amount), '$.a') FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": 1.50}", one("SELECT JSON_REMOVE(JSON_SET('{}', '$.a', amount, '$.b', 1), '$.b') "
                + "FROM t_fn WHERE id = 1"));
        assertEquals("{\"a\": 1.5, \"b\": 1}", one("SELECT JSON_SET(?, '$.b', 1) FROM t_fn WHERE id = 1",
                "{\"a\":1.50}"));
        assertEquals("1.5", one("SELECT JSON_EXTRACT('{\"a\":1.50}', '$.a') FROM t_fn WHERE id = 1"));
        assertEquals("1.50", one("SELECT JSON_EXTRACT(JSON_REMOVE(JSON_SET('{}', '$.a', amount, '$.b', 1), '$.b'), '$.a') "
                + "FROM t_fn WHERE id = 1"));
    }

    private String one(String sql, Object... params) {
        Object value = engine.query(sql, params).rows().get(0)[0];
        return value == null ? null : String.valueOf(value);
    }

    private long num(String sql, Object... params) {
        Object value = engine.query(sql, params).rows().get(0)[0];
        if (value instanceof Boolean) {
            return (Boolean) value ? 1L : 0L;
        }
        return ((Number) value).longValue();
    }
}
