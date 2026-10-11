package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.MysqlMockDataSourceFactory;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;

/**
 * DataSource 工厂（引擎经反射装配，FQCN 见 MysqlMockEngineImpl.DATASOURCE_FACTORY_IMPL）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlMockDataSourceFactoryImpl implements MysqlMockDataSourceFactory {

    /** 反射入口要求 public 无参构造。 */
    public MysqlMockDataSourceFactoryImpl() {
    }

    @Override
    public javax.sql.DataSource create(MysqlMockEngine engine) {
        return new MysqlMockDataSource(engine);
    }
}
