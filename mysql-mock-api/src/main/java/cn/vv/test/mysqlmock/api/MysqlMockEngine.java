package cn.vv.test.mysqlmock.api;

import java.io.InputStream;

/**
 * 内存 MySQL 模拟引擎入口。
 * <p>SELECT 经 Calcite 执行；INSERT/UPDATE/DELETE 由自有执行器完成（方案 D-03）。
 */
public interface MysqlMockEngine extends AutoCloseable {

    /** 装载一条或多条 DDL（分号分隔，识别引号与注释）。 */
    void executeDdl(String ddlScript);

    /** 执行查询并物化结果。 */
    QueryResult query(String sql, Object... params);

    /** 执行 INSERT/UPDATE/DELETE，返回影响行数（默认 matched 语义，D-10）与自增回填。 */
    UpdateResult execute(String sql, Object... params);

    /** 装载 YAML 数据集（表名 → 行列表）。 */
    void loadDataset(InputStream datasetYaml);

    void loadDataset(Dataset dataset);

    /** 流式构造数据集并装载：engine.dataset().table("t").row("id",1,...).load()。 */
    DatasetHandle dataset();

    /** 整库快照（含自增指针与索引）。 */
    DatabaseSnapshot snapshot();

    /** 恢复快照。 */
    void restore(DatabaseSnapshot snapshot);

    /** 回到初始装载状态（重放 DDL 与数据集，RECREATE 隔离使用）。 */
    void reset();

    /** JDBC 接入面（D-05）。 */
    javax.sql.DataSource dataSource();

    @Override
    void close();

    static MysqlMockEngineBuilder builder() {
        return new MysqlMockEngineBuilder();
    }
}
