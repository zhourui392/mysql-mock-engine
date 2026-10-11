package cn.vv.test.mysqlmock.schema;

/** MySQL 列类型分类（v0.1 白名单，方案 R1）。 */
public enum MysqlType {

    TINYINT(Category.INT),
    SMALLINT(Category.INT),
    MEDIUMINT(Category.INT),
    INT(Category.INT),
    BIGINT(Category.INT),
    DECIMAL(Category.DECIMAL),
    FLOAT(Category.DOUBLE),
    DOUBLE(Category.DOUBLE),
    REAL(Category.DOUBLE),
    CHAR(Category.STRING),
    VARCHAR(Category.STRING),
    TINYTEXT(Category.STRING),
    TEXT(Category.STRING),
    MEDIUMTEXT(Category.STRING),
    LONGTEXT(Category.STRING),
    JSON(Category.STRING),
    DATE(Category.TEMPORAL),
    DATETIME(Category.TEMPORAL),
    TIMESTAMP(Category.TEMPORAL),
    BOOLEAN(Category.INT);

    public enum Category { INT, DECIMAL, DOUBLE, STRING, TEMPORAL }

    private final Category category;

    MysqlType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** 解析类型 token；白名单外类型返回 null（由调用方按 strict 拒绝）。 */
    public static MysqlType parse(String token) {
        switch (token.toUpperCase()) {
            case "TINYINT":
            case "BOOL":
            case "BOOLEAN":
                return BOOLEAN;
            case "SMALLINT":
                return SMALLINT;
            case "MEDIUMINT":
                return MEDIUMINT;
            case "INT":
            case "INTEGER":
                return INT;
            case "BIGINT":
                return BIGINT;
            case "DECIMAL":
            case "NUMERIC":
                return DECIMAL;
            case "FLOAT":
                return FLOAT;
            case "DOUBLE":
            case "REAL":
                return DOUBLE;
            case "CHAR":
                return CHAR;
            case "VARCHAR":
            case "VARCHARACTER":
                return VARCHAR;
            case "TINYTEXT":
                return TINYTEXT;
            case "TEXT":
                return TEXT;
            case "MEDIUMTEXT":
                return MEDIUMTEXT;
            case "LONGTEXT":
                return LONGTEXT;
            case "JSON":
                return JSON;
            case "DATE":
                return DATE;
            case "DATETIME":
                return DATETIME;
            case "TIMESTAMP":
                return TIMESTAMP;
            default:
                return null;
        }
    }
}
