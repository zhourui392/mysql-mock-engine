package cn.vv.test.mysqlmock.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 表注册中心：表名（小写，database.table 限定仅记录）→ TableDefinition。 */
public final class SchemaCatalog {

    private final Map<String, TableDefinition> tables = new LinkedHashMap<>();

    public synchronized void register(TableDefinition definition) {
        String key = definition.name();
        if (tables.containsKey(key)) {
            throw new IllegalStateException("表已存在: " + key + "（CREATE TABLE 重复装载，IF NOT EXISTS 语义按已存在跳过请在上层处理）");
        }
        validateIndexes(definition);
        tables.put(key, definition);
    }

    public synchronized boolean exists(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    public synchronized TableDefinition get(String tableName) {
        return tables.get(tableName.toLowerCase());
    }

    public synchronized Map<String, TableDefinition> all() {
        return new LinkedHashMap<>(tables);
    }

    public synchronized void clear() {
        tables.clear();
    }

    private void validateIndexes(TableDefinition definition) {
        for (IndexDefinition index : definition.indexes()) {
            for (String column : index.columns()) {
                if (definition.columnIndex(column) < 0) {
                    throw new IllegalStateException(
                            "索引 " + index.name() + " 引用了不存在的列 " + column + "（表 " + definition.name() + "）");
                }
            }
        }
    }

    public List<String> tableNames() {
        return new java.util.ArrayList<>(tables.keySet());
    }
}
