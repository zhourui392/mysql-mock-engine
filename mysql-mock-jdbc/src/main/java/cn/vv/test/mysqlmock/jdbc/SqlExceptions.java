package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.MysqlMockException;

import java.sql.SQLException;

/**
 * MysqlMockException（vendorCode/SQLState）→ SQLException 语义桥。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class SqlExceptions {

    private SqlExceptions() {
    }

    static SQLException wrap(RuntimeException e) {
        if (e instanceof MysqlMockException) {
            MysqlMockException mock = (MysqlMockException) e;
            return new SQLException(mock.getMessage(), mock.sqlState(), mock.vendorCode(), mock);
        }
        return new SQLException(e.getMessage() == null ? "引擎执行失败" : e.getMessage(), "HY000", 0, e);
    }

    static SQLException of(String message) {
        return new SQLException(message, "HY000", 1105);
    }
}
