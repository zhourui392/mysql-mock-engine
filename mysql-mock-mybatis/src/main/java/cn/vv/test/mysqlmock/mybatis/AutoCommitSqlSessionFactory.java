package cn.vv.test.mysqlmock.mybatis;

import java.sql.Connection;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;

/**
 * 所有 openSession 变体都按自动提交打开（D-14：引擎只支持自动提交）。
 * <p>SqlSessionTemplate 在无 Spring 事务时调用 {@code openSession(ExecutorType)}，默认 autoCommit=false，
 * 会被事务工厂拒绝；这里统一改成 autoCommit=true，并保留调用方指定的执行器类型。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class AutoCommitSqlSessionFactory implements SqlSessionFactory {

    private final SqlSessionFactory delegate;

    AutoCommitSqlSessionFactory(SqlSessionFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public SqlSession openSession() {
        return openSession(defaultExecutorType());
    }

    @Override
    public SqlSession openSession(boolean autoCommit) {
        return openSession(defaultExecutorType());
    }

    @Override
    public SqlSession openSession(Connection connection) {
        return delegate.openSession(connection);
    }

    @Override
    public SqlSession openSession(TransactionIsolationLevel level) {
        return openSession(defaultExecutorType());
    }

    @Override
    public SqlSession openSession(ExecutorType execType) {
        return delegate.openSession(execType, true);
    }

    @Override
    public SqlSession openSession(ExecutorType execType, boolean autoCommit) {
        return openSession(execType);
    }

    @Override
    public SqlSession openSession(ExecutorType execType, TransactionIsolationLevel level) {
        return openSession(execType);
    }

    @Override
    public SqlSession openSession(ExecutorType execType, Connection connection) {
        return delegate.openSession(execType, connection);
    }

    @Override
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    private ExecutorType defaultExecutorType() {
        return delegate.getConfiguration().getDefaultExecutorType();
    }
}
