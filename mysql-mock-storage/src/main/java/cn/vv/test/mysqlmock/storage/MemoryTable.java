package cn.vv.test.mysqlmock.storage;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.IndexDefinition;
import cn.vv.test.mysqlmock.schema.TableDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单表内存存储：行 + 主键/唯一 HashIndex + 自增指针；约束在同一同步块内原子维护（方案 §3.4）。 */
public final class MemoryTable {

    private final TableDefinition definition;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Map<IndexKey, Row>> uniqueIndexes = new LinkedHashMap<>();
    private long nextAutoIncrement = 1;
    private long rowIdSequence = 0;

    public MemoryTable(TableDefinition definition) {
        this.definition = definition;
        for (IndexDefinition index : definition.indexes()) {
            if (index.unique()) {
                uniqueIndexes.put(index.name(), new LinkedHashMap<IndexKey, Row>());
            }
        }
    }

    public TableDefinition definition() {
        return definition;
    }

    public synchronized int rowCount() {
        return rows.size();
    }

    public synchronized List<Row> snapshotRows() {
        return new ArrayList<>(rows);
    }

    /**
     * 插入一行：入参为完整列值数组（缺省列已由上层填充；自增列可传 null 由表分配）。
     * 约束检查（NOT NULL / 唯一）与行、索引写入在同一同步块内完成。
     */
    public synchronized Row insert(Object[] values) {
        Object[] full = values.clone();
        applyAutoIncrement(full);
        checkNotNull(full);
        Row row = new Row(++rowIdSequence, full);
        checkAndAddUniqueKeys(row);
        rows.add(row);
        return row;
    }

    /** 整行替换（UPDATE 路径）：索引键原子迁移；返回值是否发生变化（changed 行数语义）。 */
    public synchronized boolean replaceRow(Row row, Object[] newValues) {
        checkNotNull(newValues);
        Object[] oldValues = row.valuesCopy();
        removeUniqueKeys(row);
        row.replaceValues(newValues.clone());
        try {
            checkAndAddUniqueKeys(row);
        } catch (RuntimeException e) {
            row.replaceValues(oldValues);
            restoreUniqueKeys(row);
            throw e;
        }
        return !sameValues(oldValues, newValues);
    }

    public synchronized void delete(List<Row> targets) {
        for (Row row : targets) {
            removeUniqueKeys(row);
            rows.remove(row);
        }
    }

    public synchronized Row findByUniqueValues(String indexName, Object[] columnValues) {
        Map<IndexKey, Row> index = uniqueIndexes.get(indexName);
        if (index == null) {
            return null;
        }
        return index.get(IndexKey.of(columnValues));
    }

    /** 按稳定行 ID 查找（internal SELECT __rowid 定位）。 */
    public synchronized Row rowById(long rowId) {
        for (Row row : rows) {
            if (row.rowId() == rowId) {
                return row;
            }
        }
        return null;
    }

    private void applyAutoIncrement(Object[] values) {
        for (int i = 0; i < definition.columns().size(); i++) {
            ColumnDefinition column = definition.columns().get(i);
            if (column.autoIncrement() && values[i] == null) {
                values[i] = nextAutoIncrement;
            }
            if (column.autoIncrement() && values[i] instanceof Number) {
                long used = ((Number) values[i]).longValue();
                if (used >= nextAutoIncrement) {
                    nextAutoIncrement = used + 1;
                }
            }
        }
    }

    private void checkNotNull(Object[] values) {
        List<ColumnDefinition> columns = definition.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (values[i] == null && columns.get(i).notNull() && !columns.get(i).generated()) {
                throw MysqlMockException.nullViolation(columns.get(i).name(), definition.name());
            }
        }
    }

    private void checkAndAddUniqueKeys(Row row) {
        Object[] values = row.valuesCopy();
        for (Map.Entry<String, Map<IndexKey, Row>> entry : uniqueIndexes.entrySet()) {
            IndexDefinition index = findIndex(entry.getKey());
            Object[] parts = new Object[index.columns().size()];
            boolean hasNull = false;
            for (int i = 0; i < parts.length; i++) {
                parts[i] = values[definition.columnIndex(index.columns().get(i))];
                if (parts[i] == null) {
                    hasNull = true;
                }
            }
            if (hasNull) {
                continue;
            }
            IndexKey key = IndexKey.of(parts);
            Row existing = entry.getValue().get(key);
            if (existing != null && existing != row) {
                throw MysqlMockException.duplicateEntry(
                        key + " for index '" + index.name() + "'");
            }
            entry.getValue().put(key, row);
        }
    }

    private void removeUniqueKeys(Row row) {
        Object[] values = row.valuesCopy();
        for (Map.Entry<String, Map<IndexKey, Row>> entry : uniqueIndexes.entrySet()) {
            IndexDefinition index = findIndex(entry.getKey());
            Object[] parts = new Object[index.columns().size()];
            boolean hasNull = false;
            for (int i = 0; i < parts.length; i++) {
                parts[i] = values[definition.columnIndex(index.columns().get(i))];
                if (parts[i] == null) {
                    hasNull = true;
                }
            }
            if (!hasNull) {
                entry.getValue().remove(IndexKey.of(parts));
            }
        }
    }

    private void restoreUniqueKeys(Row row) {
        checkAndAddUniqueKeys(row);
    }

    private IndexDefinition findIndex(String name) {
        for (IndexDefinition index : definition.indexes()) {
            if (index.name().equals(name)) {
                return index;
            }
        }
        throw new IllegalStateException("索引不存在: " + name);
    }

    private boolean sameValues(Object[] left, Object[] right) {
        for (int i = 0; i < left.length; i++) {
            if (!CollationNormalizer.equalsNormalized(left[i], right[i])) {
                return false;
            }
        }
        return true;
    }

    synchronized TableSnapshot snapshotState() {
        List<Object[]> values = new ArrayList<>();
        long autoIncrement = nextAutoIncrement;
        long rowIds = rowIdSequence;
        for (Row row : rows) {
            values.add(row.valuesCopy());
        }
        return new TableSnapshot(definition, values, autoIncrement, rowIds);
    }

    synchronized void restoreState(TableSnapshot snapshot) {
        rows.clear();
        for (Map<IndexKey, Row> index : uniqueIndexes.values()) {
            index.clear();
        }
        nextAutoIncrement = snapshot.nextAutoIncrement;
        rowIdSequence = snapshot.rowIdSequence;
        // 行 ID 从 1 重排：恢复后行号与初始装载一致，自增指针保持在快照位（后续插入继续编号）
        long seq = 0;
        for (Object[] values : snapshot.values) {
            Row row = new Row(++seq, values);
            rows.add(row);
            checkAndAddUniqueKeys(row);
        }
    }
}
