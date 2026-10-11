package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockEngineBuilder;
import cn.vv.test.mysqlmock.api.MysqlMockEngineFactory;

/**
 * 引擎工厂实现（api 层经反射装配，保持 api 零第三方依赖）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlMockEngineFactoryImpl implements MysqlMockEngineFactory {

    @Override
    public MysqlMockEngine create(MysqlMockEngineBuilder builder) {
        return new MysqlMockEngineImpl(builder);
    }
}
