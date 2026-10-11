package cn.vv.test.mysqlmock.api;

import javax.sql.DataSource;

/** DataSource 工厂 SPI；实现位于 mysql-mock-jdbc 模块，引擎经反射装配（避免模块循环依赖）。 */
public interface MysqlMockDataSourceFactory {

    DataSource create(MysqlMockEngine engine);
}
