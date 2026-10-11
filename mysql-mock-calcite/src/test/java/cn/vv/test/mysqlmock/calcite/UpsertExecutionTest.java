package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * REPLACE INTO 与 INSERT ... ON DUPLICATE KEY UPDATE 语义。
 * <p>影响行数口径（对齐 Connector/J 默认 found-rows）：
 * ODKU 插入 = 1，更新且有变化 = 2，更新但值不变 = 1；
 * REPLACE 无冲突 = 1，替换一行 = 2（1 删 + 1 插）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class UpsertExecutionTest {

    private static final Instant T0 = Instant.parse("2026-10-11T01:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-11T09:30:00Z");

    private UpdateDeleteExecutionTest.MutableClock clock;
    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        clock = new UpdateDeleteExecutionTest.MutableClock(T0);
        engine = MysqlMockEngine.builder()
                .clock(clock)
                .schema("classpath:mysql/orders.sql")
                .build();
        engine.executeDdl("CREATE TABLE `t_upsert` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`uk` VARCHAR(32) NOT NULL,"
                + "`qty` BIGINT NOT NULL DEFAULT 0,"
                + "`memo` VARCHAR(64) NULL,"
                + "`updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (`id`), UNIQUE KEY `uk_upsert` (`uk`))");
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void onDuplicateKeyInsertPath() {
        UpdateResult result = engine.execute(
                "INSERT INTO t_upsert (uk, qty) VALUES ('a', 10) "
                        + "ON DUPLICATE KEY UPDATE qty = qty + 1");
        assertEquals(1, result.affectedRows(), "无冲突走插入 = 1");
        assertEquals(1L, result.generatedKeys().get(0));
        assertEquals(10L, engine.query("SELECT qty FROM t_upsert WHERE uk = 'a'").rows().get(0)[0]);
    }

    @Test
    void onDuplicateKeyUpdateWithValuesFunction() {
        engine.execute("INSERT INTO t_upsert (uk, qty) VALUES ('a', 10)");
        UpdateResult result = engine.execute(
                "INSERT INTO t_upsert (uk, qty) VALUES ('a', 5) "
                        + "ON DUPLICATE KEY UPDATE qty = qty + VALUES(qty)");
        assertEquals(2, result.affectedRows(), "有变化更新 = 2");
        assertEquals(15L, engine.query("SELECT qty FROM t_upsert WHERE uk = 'a'").rows().get(0)[0],
                "VALUES(col) 引用待插值");
        assertEquals(1L, engine.query("SELECT COUNT(*) FROM t_upsert").rows().get(0)[0],
                "不产生新行");
    }

    @Test
    void onDuplicateKeyUpdateExpressionAndParams() {
        engine.execute("INSERT INTO t_upsert (uk, qty, memo) VALUES ('a', 10, 'old')");
        engine.execute(
                "INSERT INTO t_upsert (uk, qty, memo) VALUES ('a', 4, ?) "
                        + "ON DUPLICATE KEY UPDATE qty = qty * ?, memo = CONCAT(memo, '+', ?)",
                "new", 10L, "x");
        Object[] row = engine.query("SELECT qty, memo FROM t_upsert WHERE uk = 'a'").rows().get(0);
        assertEquals(100L, row[0], "qty = 10 * 10");
        assertEquals("old+x", row[1], "SET 表达式引用既有列 + 参数");
    }

    @Test
    void onDuplicateKeyUnchangedCountsOneAndKeepsTimestamp() {
        engine.execute("INSERT INTO t_upsert (uk, qty) VALUES ('a', 10)");
        Timestamp before = (Timestamp) engine.query(
                "SELECT updated_at FROM t_upsert WHERE uk = 'a'").rows().get(0)[0];

        clock.set(T1);
        UpdateResult unchanged = engine.execute(
                "INSERT INTO t_upsert (uk, qty) VALUES ('a', 10) "
                        + "ON DUPLICATE KEY UPDATE qty = VALUES(qty)");
        assertEquals(1, unchanged.affectedRows(), "值不变（found-rows 口径）= 1");
        assertEquals(before, engine.query(
                "SELECT updated_at FROM t_upsert WHERE uk = 'a'").rows().get(0)[0],
                "ON UPDATE 未触发");

        UpdateResult changed = engine.execute(
                "INSERT INTO t_upsert (uk, qty) VALUES ('a', 11) "
                        + "ON DUPLICATE KEY UPDATE qty = VALUES(qty)");
        assertEquals(2, changed.affectedRows(), "有变化 = 2");
        assertNotEquals(before, engine.query(
                "SELECT updated_at FROM t_upsert WHERE uk = 'a'").rows().get(0)[0],
                "值变化触发 ON UPDATE CURRENT_TIMESTAMP");
    }

    @Test
    void onDuplicateKeyRecomputesGeneratedColumn() {
        engine.executeDdl("CREATE TABLE `t_gd` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT, `uk` VARCHAR(16) NOT NULL,"
                + "`status` VARCHAR(16) NOT NULL DEFAULT 'active',"
                + "`occ` CHAR(1) AS (IF(`status` = 'active', '1', NULL)) STORED,"
                + "PRIMARY KEY (`id`), UNIQUE KEY `uk_gd` (`uk`, `occ`))");
        engine.execute("INSERT INTO t_gd (uk, status) VALUES ('a', 'active')");
        // ODKU 更新 status → 生成列变 NULL，释放唯一槽位后同 uk + active 可再插
        engine.execute("INSERT INTO t_gd (uk, status) VALUES ('a', 'active') "
                + "ON DUPLICATE KEY UPDATE status = 'deleted'");
        assertEquals(null, engine.query("SELECT occ FROM t_gd WHERE id = 1").rows().get(0)[0]);
        assertEquals(1, engine.execute(
                "INSERT INTO t_gd (uk, status) VALUES ('a', 'active')").affectedRows(),
                "生成列重算释放唯一槽位");
    }

    @Test
    void replaceIntoInsertsWhenNoConflict() {
        UpdateResult result = engine.execute(
                "REPLACE INTO t_upsert (uk, qty) VALUES ('a', 1)");
        assertEquals(1, result.affectedRows(), "无冲突 = 1");
        assertEquals(1L, result.generatedKeys().get(0));
        assertEquals(1L, engine.query("SELECT qty FROM t_upsert WHERE uk = 'a'").rows().get(0)[0]);
    }

    @Test
    void replaceIntoDeletesConflictingRowsThenInserts() {
        engine.execute("INSERT INTO t_upsert (uk, qty) VALUES ('a', 10)");
        long oldId = (Long) engine.query("SELECT id FROM t_upsert WHERE uk = 'a'").rows().get(0)[0];

        UpdateResult result = engine.execute(
                "REPLACE INTO t_upsert (uk, qty) VALUES ('a', 99)");
        assertEquals(2, result.affectedRows(), "1 删 + 1 插");
        assertEquals(1L, engine.query("SELECT COUNT(*) FROM t_upsert").rows().get(0)[0]);
        long newId = (Long) engine.query("SELECT id FROM t_upsert WHERE uk = 'a'").rows().get(0)[0];
        assertNotEquals(oldId, newId, "REPLACE 分配新自增 id");
        assertEquals(99L, engine.query("SELECT qty FROM t_upsert WHERE uk = 'a'").rows().get(0)[0]);
    }

    @Test
    void replaceIntoRemovingRowsOnMultipleUniqueIndexes() {
        engine.executeDdl("CREATE TABLE `t_multi` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`code` VARCHAR(16) NOT NULL, `alt` VARCHAR(16) NOT NULL,"
                + "PRIMARY KEY (`id`), UNIQUE KEY `uk_code` (`code`), UNIQUE KEY `uk_alt` (`alt`))");
        engine.execute("INSERT INTO t_multi (code, alt) VALUES ('c1', 'a1')");
        engine.execute("INSERT INTO t_multi (code, alt) VALUES ('c2', 'a2')");
        // 同时命中两行的不同唯一键 → 2 删 + 1 插
        UpdateResult result = engine.execute(
                "REPLACE INTO t_multi (code, alt) VALUES ('c1', 'a2')");
        assertEquals(3, result.affectedRows());
        assertEquals(1L, engine.query("SELECT COUNT(*) FROM t_multi").rows().get(0)[0]);
        assertEquals("c1", engine.query("SELECT code FROM t_multi").rows().get(0)[0]);
    }
}
