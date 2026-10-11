package cn.vv.test.mysqlmock.api;

/** 引擎实现工厂 SPI；实现位于 mysql-mock-calcite 模块，api 层经反射装配。 */
public interface MysqlMockEngineFactory {

    MysqlMockEngine create(MysqlMockEngineBuilder builder);
}
