package cn.vv.test.mysqlmock.mybatis;

import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Properties;

/**
 * 自动提交事务工厂（D-14）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MysqlMockTransactionFactory implements TransactionFactory {

    @Override
    public void setProperties(Properties props) {
        // 无配置项
    }

    @Override
    public Transaction newTransaction(DataSource dataSource,
                                      org.apache.ibatis.session.TransactionIsolationLevel level,
                                      boolean autoCommit) {
        if (!autoCommit) {
            throw new IllegalArgumentException("mysql-mock 仅支持自动提交（D-14），openSession 请使用 autoCommit=true");
        }
        return new MysqlMockTransaction(dataSource);
    }

    @Override
    public Transaction newTransaction(Connection conn) {
        throw new IllegalArgumentException("mysql-mock 事务必须由 DataSource 建立（自动提交，D-14）");
    }
}
