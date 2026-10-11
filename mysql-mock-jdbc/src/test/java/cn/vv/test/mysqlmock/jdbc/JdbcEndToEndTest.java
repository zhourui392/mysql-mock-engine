package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §3.6 JDBC 层验收：close/isValid、? 绑定 setObject/setNull、Batch、
 * executeQuery/executeUpdate、getObject/getLong/getBigDecimal/getTimestamp/wasNull、
 * 列名/别名/类型、getTables/getColumns/getPrimaryKeys、Generated Keys 回填、
 * 1062/1146/42000 SQLException 语义。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class JdbcEndToEndTest {

    private static MysqlMockEngine engine;
    private static Connection connection;

    @BeforeAll
    static void setUp() throws Exception {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/orders.sql",
                        "classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build();
        connection = engine.dataSource().getConnection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        connection.close();
        engine.close();
    }

    @Test
    void paramBindingAndNull() throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, nickname, phone FROM dine_customer WHERE status = ? AND phone_dial = ?")) {
            ps.setString(1, "active");
            ps.setObject(2, "852");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1L, rs.getLong("id"));
                assertEquals("Alex", rs.getString("nickname"));
                assertFalse(rs.next());
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT phone, nickname FROM dine_customer WHERE id = ?")) {
            ps.setNull(1, Types.BIGINT);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next());
            }
            ps.setLong(1, 2L);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString("nickname"));
                // 可空列投影：显式 NULL 值
                assertTrue(true, "wasNull 场景见 wasNullAndTypedGetters");
            }
        }
    }

    @Test
    void wasNullAndTypedGetters() throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, nickname, NULL AS released_at FROM dine_customer WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals("Bob", rs.getString(2));
            assertFalse(rs.wasNull());
            assertNull(rs.getBigDecimal("released_at"));
            assertTrue(rs.wasNull(), "NULL 列置位 wasNull");
            assertEquals(0d, rs.getDouble("released_at"), "数值 getter 对 NULL 返回 0");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void typedGettersAndPrecision() throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, ratio_to_base, created_at, unit FROM vv_pc_sku_unit WHERE id = 11")) {
            assertTrue(rs.next());
            assertEquals(11L, rs.getLong(1));
            assertEquals(new BigDecimal("100.000"), rs.getBigDecimal("ratio_to_base"));
            assertEquals(Timestamp.valueOf("2026-01-02 03:04:05.678"), rs.getTimestamp("created_at"));
            assertEquals("瓶", rs.getString("unit"));
            assertFalse(rs.wasNull());
        }
    }

    @Test
    void executeUpdateAndGeneratedKeys() throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO t_order_item (order_id, sku, qty, price) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, 1L);
            ps.setString(2, "SKU-X");
            ps.setInt(3, 1);
            ps.setBigDecimal(4, new BigDecimal("42.00"));
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(4L, keys.getLong(1), "自增键回填（dataset 已用 1-3）");
                assertEquals("GENERATED_KEY", keys.getMetaData().getColumnLabel(1));
            }
        }
    }

    @Test
    void statementExecuteAndBatch() throws Exception {
        try (Statement st = connection.createStatement()) {
            assertFalse(st.execute("DELETE FROM t_order WHERE status = 'PENDING'"));
            assertEquals(1, st.getUpdateCount());
            assertTrue(st.execute("SELECT COUNT(*) FROM t_order WHERE id <= 4"));
            try (ResultSet rs = st.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(3L, rs.getLong(1), "删除 PENDING 后初始 4 单剩 3");
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO t_order (customer_id, amount, status) VALUES (?, ?, ?)")) {
            ps.setLong(1, 10L);
            ps.setBigDecimal(2, new BigDecimal("1.00"));
            ps.setString(3, "PAID");
            ps.addBatch();
            ps.setLong(1, 11L);
            ps.setBigDecimal(2, new BigDecimal("2.00"));
            ps.setString(3, "PAID");
            ps.addBatch();
            int[] counts = ps.executeBatch();
            assertEquals(2, counts.length);
            assertEquals(1, counts[0]);
            assertEquals(1, counts[1]);
        }
    }

    @Test
    void resultSetMetaDataCoversNamesAliasAndTypes() throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id AS order_no, amount, created_at, status FROM t_order WHERE id = 1")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(4, meta.getColumnCount());
            assertEquals("order_no", meta.getColumnLabel(1));
            assertEquals("order_no", meta.getColumnName(1), "别名即列名（MySQL 语义）");
            assertEquals(Types.DECIMAL, meta.getColumnType(2));
            assertEquals(Types.TIMESTAMP, meta.getColumnType(3));
        }
    }

    @Test
    void databaseMetaDataTablesColumnsAndPrimaryKeys() throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        assertEquals("jdbc:mysql:mock:", meta.getURL());
        assertEquals("MySQL", meta.getDatabaseProductName());

        try (ResultSet tables = meta.getTables(null, null, "dine_%", null)) {
            assertTrue(tables.next());
            assertEquals("dine_customer", tables.getString("TABLE_NAME"));
            assertTrue(tables.next());
            assertEquals("dine_customer_phone", tables.getString("TABLE_NAME"));
        }
        try (ResultSet columns = meta.getColumns(null, null, "dine_customer", null)) {
            int seen = 0;
            String firstName = null;
            while (columns.next()) {
                if (firstName == null) {
                    firstName = columns.getString("COLUMN_NAME");
                }
                seen++;
            }
            assertEquals(10, seen, "dine_customer 10 列");
            assertEquals("id", firstName);
        }
        try (ResultSet pk = meta.getPrimaryKeys(null, null, "vv_pc_sku_unit")) {
            assertTrue(pk.next());
            assertEquals("id", pk.getString("COLUMN_NAME"));
            assertEquals("PRIMARY", pk.getString("PK_NAME"));
        }
    }

    @Test
    void mysqlErrorSemanticsSurfaceAsSqlException() throws Exception {
        try (Statement st = connection.createStatement()) {
            try {
                st.executeUpdate("INSERT INTO t_order (id, customer_id, amount) VALUES (1, 1, 9.99)");
                throw new AssertionError("应抛主键冲突");
            } catch (SQLException e) {
                assertEquals(1062, e.getErrorCode());
                assertEquals("23000", e.getSQLState());
            }
            try {
                st.executeQuery("SELECT * FROM no_such");
                throw new AssertionError("应抛表不存在");
            } catch (SQLException e) {
                assertEquals(1146, e.getErrorCode());
                assertEquals("42S02", e.getSQLState());
            }
            try {
                st.executeQuery("SELECT FROM WHERE");
                throw new AssertionError("应抛语法错误");
            } catch (SQLException e) {
                assertEquals(1064, e.getErrorCode());
                assertEquals("42000", e.getSQLState());
            }
        }
    }

    @Test
    void closeAndIsValid() throws Exception {
        Connection c = engine.dataSource().getConnection();
        assertTrue(c.isValid(1));
        c.close();
        assertTrue(c.isClosed());
        assertEquals(false, c.isValid(0));
    }
}
