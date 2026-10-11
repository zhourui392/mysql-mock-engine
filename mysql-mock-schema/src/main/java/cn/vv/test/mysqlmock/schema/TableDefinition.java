package cn.vv.test.mysqlmock.schema;

import java.util.ArrayList;
import java.util.List;

/** 表定义（不可变）；表名与列名统一小写存储，支持 database.table 限定解析。 */
public final class TableDefinition {

    /** 引擎缺省排序规则：未显式声明 COLLATE 时按 _ci + PAD SPACE 语义（D-16）。 */
    public static final String DEFAULT_COLLATION = "utf8mb4_general_ci";

    private final String database;
    private final String name;
    private final List<ColumnDefinition> columns;
    private final List<IndexDefinition> indexes;
    private final String engine;
    private final String charset;
    private final String collation;
    private final String comment;

    public TableDefinition(String database, String name, List<ColumnDefinition> columns,
                           List<IndexDefinition> indexes, String engine, String charset,
                           String collation, String comment) {
        this.database = database;
        this.name = name.toLowerCase();
        this.columns = new ArrayList<>(columns);
        this.indexes = new ArrayList<>(indexes);
        this.engine = engine;
        this.charset = charset;
        this.collation = collation != null ? collation : DEFAULT_COLLATION;
        this.comment = comment;
    }

    public String database() {
        return database;
    }

    public String name() {
        return name;
    }

    public List<ColumnDefinition> columns() {
        return columns;
    }

    public List<IndexDefinition> indexes() {
        return indexes;
    }

    public IndexDefinition primaryIndex() {
        for (IndexDefinition index : indexes) {
            if (index.kind() == IndexDefinition.Kind.PRIMARY) {
                return index;
            }
        }
        return null;
    }

    public List<IndexDefinition> uniqueIndexes() {
        List<IndexDefinition> result = new ArrayList<>();
        for (IndexDefinition index : indexes) {
            if (index.kind() == IndexDefinition.Kind.UNIQUE) {
                result.add(index);
            }
        }
        return result;
    }

    public ColumnDefinition column(String columnName) {
        for (ColumnDefinition column : columns) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return column;
            }
        }
        return null;
    }

    public int columnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }

    public String engine() {
        return engine;
    }

    public String charset() {
        return charset;
    }

    public String collation() {
        return collation;
    }

    public String comment() {
        return comment;
    }
}
