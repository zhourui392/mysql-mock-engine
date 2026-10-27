package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与 MySQL Connector/J 对齐的三处兼容：JDBC 4.2 {@code getObject(col, java.time 类型)}、
 * {@code setBoolean} 绑定 TINYINT 列、MySQL 非保留字（如 {@code value}）不加反引号作列名。
 * 消费方此前各自用 TypeHandler 与全局反引号绕开，改在引擎层兼容。
 *
 * @author zhourui
 * @since 2026-10-23
 */
class MysqlCompatibilityGapsTest {

    private MysqlMockEngine engine;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        engine = MysqlMockEngine.builder().schema("classpath:mysql/compat-gaps.sql").build();
        connection = engine.dataSource().getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        engine.close();
    }

    private void insert(String field, String value, boolean hidden) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO t_override (field, `value`, hidden, biz_date, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, field);
            ps.setString(2, value);
            ps.setBoolean(3, hidden);
            ps.setObject(4, LocalDate.of(2026, 10, 23));
            ps.setObject(5, LocalDateTime.of(2026, 10, 23, 8, 30, 15));
            ps.executeUpdate();
        }
    }

    @Test
    void getObjectConvertsTemporalColumnsToJavaTime() throws Exception {
        insert("name", "招牌", false);

        try (PreparedStatement ps = connection.prepareStatement("SELECT biz_date, created_at FROM t_override");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(LocalDate.of(2026, 10, 23), rs.getObject("biz_date", LocalDate.class));
            assertEquals(LocalDateTime.of(2026, 10, 23, 8, 30, 15), rs.getObject("created_at", LocalDateTime.class));
            assertEquals(LocalDateTime.of(2026, 10, 23, 8, 30, 15), rs.getObject(2, LocalDateTime.class));
        }
    }

    @Test
    void booleanParameterBindsTinyintColumnForWriteAndFilter() throws Exception {
        insert("name", "招牌", true);
        insert("sort", "5", false);

        try (PreparedStatement ps = connection.prepareStatement("SELECT field, hidden FROM t_override WHERE hidden = ?")) {
            ps.setBoolean(1, true);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("name", rs.getString("field"));
                assertTrue(rs.getBoolean("hidden"));
                assertEquals(Boolean.TRUE, rs.getObject("hidden", Boolean.class));
                assertFalse(rs.next());
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE t_override SET hidden = ? WHERE field = ?")) {
            ps.setObject(1, Boolean.FALSE);
            ps.setString(2, "name");
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(0L, engine.query("SELECT COUNT(*) FROM t_override WHERE hidden = 1").rows().get(0)[0]);
    }

    @Test
    void mysqlNonReservedWordWorksAsUnquotedColumn() throws Exception {
        insert("name", "招牌", false);

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE t_override SET value = ? WHERE field = ? AND value IS NOT NULL")) {
            ps.setString(1, "新名");
            ps.setString(2, "name");
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT o.value, value AS v FROM t_override o WHERE value = ? ORDER BY value")) {
            ps.setString(1, "新名");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("新名", rs.getString(1));
                assertEquals("新名", rs.getString("v"));
            }
        }
        assertEquals(1, engine.execute("INSERT INTO t_override (field, value) VALUE ('sort', '9')").affectedRows());
        assertEquals(1, engine.execute("INSERT INTO t_override (field, value) VALUES ('hidden', '1') "
                + "ON DUPLICATE KEY UPDATE value = VALUES(value)").affectedRows());
        assertEquals(3L, engine.query("SELECT COUNT(*) FROM t_override WHERE value IS NOT NULL").rows().get(0)[0]);
    }
}
