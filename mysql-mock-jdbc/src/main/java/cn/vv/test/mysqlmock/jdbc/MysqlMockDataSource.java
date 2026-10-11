package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 引擎 DataSource：连接为内存引擎会话（无真实网络资源）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlMockDataSource implements DataSource {

    private final MysqlMockEngine engine;

    MysqlMockDataSource(MysqlMockEngine engine) {
        this.engine = engine;
    }

    @Override
    public Connection getConnection() {
        return new MysqlMockConnection(engine);
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLFeatureNotSupportedException("DataSource 不实现 " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // 内存引擎无日志输出
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 无连接建立成本
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(MysqlMockDataSource.class.getName());
    }
}
