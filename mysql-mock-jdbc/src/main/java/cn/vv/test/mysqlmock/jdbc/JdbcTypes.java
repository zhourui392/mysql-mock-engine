package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.MysqlType;

/**
 * MySQL 列类型 → java.sql.Types / MySQL 类型名（JDBC 元数据投影）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class JdbcTypes {

    private JdbcTypes() {
    }

    static int sqlType(ColumnDefinition column) {
        switch (column.type()) {
            case TINYINT:
            case BOOLEAN:
                return java.sql.Types.TINYINT;
            case SMALLINT:
                return java.sql.Types.SMALLINT;
            case MEDIUMINT:
            case INT:
                return java.sql.Types.INTEGER;
            case BIGINT:
                return java.sql.Types.BIGINT;
            case DECIMAL:
                return java.sql.Types.DECIMAL;
            case FLOAT:
                return java.sql.Types.REAL;
            case DOUBLE:
                return java.sql.Types.DOUBLE;
            case CHAR:
                return java.sql.Types.CHAR;
            case JSON:
            case VARCHAR:
                return java.sql.Types.VARCHAR;
            case TINYTEXT:
            case TEXT:
            case MEDIUMTEXT:
            case LONGTEXT:
                return java.sql.Types.LONGVARCHAR;
            case DATE:
                return java.sql.Types.DATE;
            case DATETIME:
            case TIMESTAMP:
                return java.sql.Types.TIMESTAMP;
            default:
                return java.sql.Types.OTHER;
        }
    }

    static String typeName(ColumnDefinition column) {
        switch (column.type()) {
            case TINYINT:
            case BOOLEAN:
                return column.precision() > 0 ? "tinyint(" + column.precision() + ")" : "tinyint";
            case SMALLINT:
                return "smallint";
            case MEDIUMINT:
                return "mediumint";
            case INT:
                return "int";
            case BIGINT:
                return "bigint";
            case DECIMAL:
                return "decimal(" + column.precision() + "," + Math.max(column.scale(), 0) + ")";
            case FLOAT:
                return "float";
            case DOUBLE:
                return "double";
            case CHAR:
                return "char(" + Math.max(column.precision(), 1) + ")";
            case JSON:
                return "json";
            case VARCHAR:
                return "varchar(" + (column.precision() > 0 ? column.precision() : 65535) + ")";
            case TINYTEXT:
                return "tinytext";
            case TEXT:
                return "text";
            case MEDIUMTEXT:
                return "mediumtext";
            case LONGTEXT:
                return "longtext";
            case DATE:
                return "date";
            case DATETIME:
                return column.scale() > 0 ? "datetime(" + column.scale() + ")" : "datetime";
            case TIMESTAMP:
                return column.scale() > 0 ? "timestamp(" + column.scale() + ")" : "timestamp";
            default:
                return column.type().name().toLowerCase();
        }
    }

    /** Types 常量 → 简化 MySQL 类型名（结果集元数据）。 */
    static String typeNameOfSqlType(int sqlType) {
        switch (sqlType) {
            case java.sql.Types.TINYINT: return "tinyint";
            case java.sql.Types.SMALLINT: return "smallint";
            case java.sql.Types.INTEGER: return "int";
            case java.sql.Types.BIGINT: return "bigint";
            case java.sql.Types.DECIMAL: return "decimal";
            case java.sql.Types.REAL: return "float";
            case java.sql.Types.DOUBLE: return "double";
            case java.sql.Types.CHAR: return "char";
            case java.sql.Types.VARCHAR: return "varchar";
            case java.sql.Types.LONGVARCHAR: return "text";
            case java.sql.Types.DATE: return "date";
            case java.sql.Types.TIMESTAMP: return "datetime";
            default: return "varchar";
        }
    }

    static String columnClassName(int sqlType) {
        switch (sqlType) {
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
            case java.sql.Types.BIGINT:
                return Long.class.getName();
            case java.sql.Types.DECIMAL: return java.math.BigDecimal.class.getName();
            case java.sql.Types.REAL:
            case java.sql.Types.DOUBLE: return Double.class.getName();
            case java.sql.Types.DATE: return java.sql.Date.class.getName();
            case java.sql.Types.TIMESTAMP: return java.sql.Timestamp.class.getName();
            default: return String.class.getName();
        }
    }

    /** MysqlType 分类转 JDBC 用（DatabaseMetaData NULLABLE 常量等场景复用）。 */
    static boolean isNumeric(MysqlType type) {
        switch (type.category()) {
            case INT:
            case DECIMAL:
            case DOUBLE:
                return true;
            default:
                return false;
        }
    }
}
