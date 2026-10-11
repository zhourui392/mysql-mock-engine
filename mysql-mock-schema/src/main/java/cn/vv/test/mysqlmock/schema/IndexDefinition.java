package cn.vv.test.mysqlmock.schema;

import java.util.Collections;
import java.util.List;

/** 索引定义；v0.1 唯一索引参与约束，普通索引仅记录。 */
public final class IndexDefinition {

    public enum Kind { PRIMARY, UNIQUE, NORMAL }

    private final String name;
    private final Kind kind;
    private final List<String> columns;

    public IndexDefinition(String name, Kind kind, List<String> columns) {
        this.name = name;
        this.kind = kind;
        this.columns = Collections.unmodifiableList(columns);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public boolean unique() {
        return kind != Kind.NORMAL;
    }

    public List<String> columns() {
        return columns;
    }
}
