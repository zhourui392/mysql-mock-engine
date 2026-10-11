package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.QueryResult;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * 物化结果集元数据：列名 / 别名 / Types 类型投影。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MysqlMockResultSetMetaData implements ResultSetMetaData {

    private final QueryResult result;

    MysqlMockResultSetMetaData(QueryResult result) {
        this.result = result;
    }

    @Override
    public int getColumnCount() throws java.sql.SQLException {
        return result.columnNames().size();
    }

    @Override
    public String getColumnLabel(int column) throws java.sql.SQLException {
        return name(column);
    }

    @Override
    public String getColumnName(int column) throws java.sql.SQLException {
        return name(column);
    }

    @Override
    public int getColumnType(int column) throws java.sql.SQLException {
        check(column);
        return result.columnTypes().get(column - 1);
    }

    @Override
    public String getColumnTypeName(int column) throws java.sql.SQLException {
        return JdbcTypes.typeNameOfSqlType(getColumnType(column));
    }

    @Override
    public String getColumnClassName(int column) throws java.sql.SQLException {
        return JdbcTypes.columnClassName(getColumnType(column));
    }

    @Override
    public int getPrecision(int column) throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getColumnDisplaySize(int column) throws java.sql.SQLException {
        return Math.max(getPrecision(column), 16);
    }

    @Override
    public int getScale(int column) throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int isNullable(int column) throws java.sql.SQLException {
        return columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean isAutoIncrement(int column) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean isReadOnly(int column) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean isWritable(int column) throws java.sql.SQLException {
        return true;
    }

    @Override
    public String getSchemaName(int column) throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getTableName(int column) throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getCatalogName(int column) throws java.sql.SQLException {
        return "";
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
        return iface.isInstance(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        throw new SQLException("ResultSetMetaData 不实现 " + iface.getName());
    }

    private String name(int column) throws java.sql.SQLException {
        check(column);
        return result.columnNames().get(column - 1);
    }

    private void check(int column) throws java.sql.SQLException {
        if (column < 1 || column > result.columnNames().size()) {
            throw SqlExceptions.of("列下标越界: " + column);
        }
    }
}
