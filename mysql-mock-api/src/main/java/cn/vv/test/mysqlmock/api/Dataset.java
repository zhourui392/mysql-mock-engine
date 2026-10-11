package cn.vv.test.mysqlmock.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内存数据集：表名 → 行（列名 → 值），值按列类型二次转换。 */
public final class Dataset {

    private final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();

    public Dataset() {
    }

    public Dataset(Map<String, List<Map<String, Object>>> tables) {
        for (Map.Entry<String, List<Map<String, Object>>> entry : tables.entrySet()) {
            addTable(entry.getKey().toLowerCase(), entry.getValue());
        }
    }

    public static TableBuilder table(String tableName) {
        return new TableBuilder(tableName);
    }

    public Map<String, List<Map<String, Object>>> tables() {
        return tables;
    }

    void addTable(String name, List<Map<String, Object>> rows) {
        tables.put(name, rows);
    }

    /** 单表行构造器。 */
    public static final class TableBuilder {

        private final String tableName;
        private final List<Map<String, Object>> rows = new ArrayList<>();

        private TableBuilder(String tableName) {
            this.tableName = tableName;
        }

        /** row("id", 1L, "name", "Alex")：键值成对。 */
        public TableBuilder row(Object... columnValuePairs) {
            if (columnValuePairs.length % 2 != 0) {
                throw new IllegalArgumentException("row() 需要成对的 列名/值 参数");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columnValuePairs.length; i += 2) {
                row.put(String.valueOf(columnValuePairs[i]).toLowerCase(), columnValuePairs[i + 1]);
            }
            rows.add(row);
            return this;
        }

        public Dataset build() {
            Dataset dataset = new Dataset();
            dataset.addTable(tableName.toLowerCase(), rows);
            return dataset;
        }
    }
}
