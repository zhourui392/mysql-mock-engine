package cn.vv.test.mysqlmock.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 查询结果：列名、java.sql.Types 列类型与物化行。 */
public final class QueryResult {

    private final List<String> columnNames;
    private final List<Integer> columnTypes;
    private final List<Object[]> rows;

    public QueryResult(List<String> columnNames, List<Integer> columnTypes, List<Object[]> rows) {
        this.columnNames = Collections.unmodifiableList(new ArrayList<>(columnNames));
        this.columnTypes = Collections.unmodifiableList(new ArrayList<>(columnTypes));
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    public List<String> columnNames() {
        return columnNames;
    }

    public List<Integer> columnTypes() {
        return columnTypes;
    }

    public List<Object[]> rows() {
        return rows;
    }

    public int rowCount() {
        return rows.size();
    }
}
