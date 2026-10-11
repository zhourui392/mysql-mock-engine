package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 事务边界（D-14：仅自动提交）：rollback / setAutoCommit(false) 显式抛
 * SQLFeatureNotSupportedException 且错误信息说明支持级别；数据保持已写入状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class TransactionSemanticsTest {

    private MysqlMockEngine engine;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/orders.sql")
                .build();
        connection = engine.dataSource().getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        engine.close();
    }

    @Test
    void rollbackRejectedAndDataStaysCommitted() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_order (customer_id, amount, status) VALUES (?, ?, ?)")) {
            insert.setLong(1, 1L);
            insert.setBigDecimal(2, new java.math.BigDecimal("88.50"));
            insert.setString(3, "PAID");
            assertEquals(1, insert.executeUpdate());
        }

        SQLFeatureNotSupportedException e = assertThrows(SQLFeatureNotSupportedException.class,
                () -> connection.rollback());
        assertTrue(e.getMessage().contains("自动提交"), "错误信息说明支持级别");

        // 自动提交语义：数据保持已写入状态
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t_order")) {
            assertTrue(rs.next());
            assertEquals(1L, rs.getLong(1));
        }
    }

    @Test
    void setAutoCommitFalseRejected() throws Exception {
        SQLFeatureNotSupportedException e = assertThrows(SQLFeatureNotSupportedException.class,
                () -> connection.setAutoCommit(false));
        assertTrue(e.getMessage().contains("自动提交"));
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void commitAndSetAutoCommitTrueAreLegalNoops() throws Exception {
        connection.commit();
        connection.setAutoCommit(true);
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void savepointsRejected() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.setSavepoint());
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.setSavepoint("sp"));
    }

    @Test
    void supportsTransactionsLevelIsNone() throws Exception {
        assertEquals(java.sql.Connection.TRANSACTION_NONE,
                connection.getTransactionIsolation());
        assertEquals(false, connection.getMetaData().supportsTransactions());
    }

    @Test
    void urlAndProductMetadata() throws Exception {
        assertEquals("jdbc:mysql:mock:", connection.getMetaData().getURL());
        assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
    }
}
