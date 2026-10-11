package cn.vv.test.mysqlmock.api;

/** 引擎异常，携带 MySQL vendorCode / SQLState（方案 §4 错误语义）。 */
public class MysqlMockException extends RuntimeException {

    public static final int ER_DUP_ENTRY = 1062;
    public static final int ER_BAD_NULL_ERROR = 1048;
    public static final int ER_NO_SUCH_TABLE = 1146;
    public static final int ER_PARSE_ERROR = 1064;
    public static final int ER_NOT_SUPPORTED = 1235;

    private final int vendorCode;
    private final String sqlState;

    public MysqlMockException(String message, int vendorCode, String sqlState) {
        super(message);
        this.vendorCode = vendorCode;
        this.sqlState = sqlState;
    }

    public MysqlMockException(String message, int vendorCode, String sqlState, Throwable cause) {
        super(message, cause);
        this.vendorCode = vendorCode;
        this.sqlState = sqlState;
    }

    public static MysqlMockException duplicateEntry(String detail) {
        return new MysqlMockException("Duplicate entry: " + detail, ER_DUP_ENTRY, "23000");
    }

    public static MysqlMockException nullViolation(String column, String table) {
        return new MysqlMockException(
                "Column '" + column + "' cannot be null (table " + table + ")", ER_BAD_NULL_ERROR, "23000");
    }

    public static MysqlMockException tableNotExists(String table) {
        return new MysqlMockException("Table '" + table + "' doesn't exist", ER_NO_SUCH_TABLE, "42S02");
    }

    public static MysqlMockException parseError(String detail) {
        return new MysqlMockException("You have an error in your SQL syntax: " + detail, ER_PARSE_ERROR, "42000");
    }

    /** strict：解析失败，保留底层 cause 便于定位。 */
    public static MysqlMockException parseError(String detail, Throwable cause) {
        return new MysqlMockException("You have an error in your SQL syntax: " + detail,
                ER_PARSE_ERROR, "42000", cause);
    }

    /** strict：未支持特性显式失败，不静默（R9）。 */
    public static MysqlMockException unsupported(String detail) {
        return new MysqlMockException("This version of mysql-mock doesn't yet support: " + detail,
                ER_NOT_SUPPORTED, "42000");
    }

    public int vendorCode() {
        return vendorCode;
    }

    public String sqlState() {
        return sqlState;
    }
}
