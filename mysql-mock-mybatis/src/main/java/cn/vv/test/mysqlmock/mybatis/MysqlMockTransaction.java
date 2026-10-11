package cn.vv.test.mysqlmock.mybatis;

import org.apache.ibatis.transaction.Transaction;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 内置自动提交事务（D-14）：连接直连引擎；commit/rollback 为合法空操作，
 * MyBatis 会话层的提交/回滚不触发 JDBC 层拒绝（方案 §3.7）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MysqlMockTransaction implements Transaction {

    private final DataSource dataSource;
    private Connection connection;

    MysqlMockTransaction(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (connection == null) {
            connection = dataSource.getConnection();
        }
        return connection;
    }

    @Override
    public void commit() {
        // 自动提交语义：空操作
    }

    @Override
    public void rollback() {
        // 自动提交语义：空操作
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public Integer getTimeout() {
        return null;
    }
}
