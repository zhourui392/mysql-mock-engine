package cn.vv.test.mysqlmock.api;

/** 流式数据集装载句柄：engine.dataset().table("t").row(...).row(...).load()。 */
public interface DatasetHandle {

    DatasetHandle table(String tableName);

    DatasetHandle row(Object... columnValuePairs);

    void load();
}
