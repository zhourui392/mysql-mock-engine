package cn.vv.test.mysqlmock.storage;

import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.TableDefinition;

/** STORED 生成列求值 SPI；实现位于 calcite 模块（表达式经 Calcite 解析，D-13）。 */
public interface GeneratedColumnEvaluator {

    /** 装载期校验：表达式须落在 R3 白名单内，超出即抛 MysqlMockException。 */
    void validate(TableDefinition table, ColumnDefinition column);

    /** 按当前行值求生成列值。 */
    Object evaluate(TableDefinition table, ColumnDefinition column, Object[] currentValues);
}
