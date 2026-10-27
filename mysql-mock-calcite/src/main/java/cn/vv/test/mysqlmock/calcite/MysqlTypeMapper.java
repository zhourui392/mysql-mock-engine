package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.MysqlType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

/** MySQL 类型 → Calcite 类型的唯一转换点（D-07）。 */
final class MysqlTypeMapper {

    private MysqlTypeMapper() {
    }

    /**
     * CAST 类型名：UPDATE/INSERT 改写为 internal SELECT 时，为裸动态参数锚定目标列类型
     * （无上下文的 ? 无法通过校验）。
     *
     * @param column 目标列
     * @return Calcite SQL 类型名（如 BIGINT / DECIMAL(18, 2) / VARCHAR）
     */
    static String castTypeName(ColumnDefinition column) {
        switch (column.type()) {
            case DECIMAL:
                int precision = column.precision() > 0 ? column.precision() : 10;
                int scale = Math.max(column.scale(), 0);
                return "DECIMAL(" + precision + ", " + scale + ")";
            case FLOAT:
            case DOUBLE:
                return "DOUBLE";
            case CHAR:
                return "CHAR";
            case DATE:
                return "DATE";
            case DATETIME:
            case TIMESTAMP:
                return "TIMESTAMP";
            default:
                // 整型（BIGINT）与字符串族（VARCHAR，不设长度避免截断语义）
                return sqlTypeName(column.type()) == SqlTypeName.BIGINT ? "BIGINT" : "VARCHAR";
        }
    }

    static RelDataType toRelType(ColumnDefinition column, RelDataTypeFactory factory) {
        SqlTypeName typeName = sqlTypeName(column.type());
        RelDataType type;
        switch (column.type()) {
            case DECIMAL:
                int precision = column.precision() > 0 ? column.precision() : 10;
                int scale = Math.max(column.scale(), 0);
                type = factory.createSqlType(SqlTypeName.DECIMAL, precision, scale);
                break;
            case CHAR:
                // MySQL 读取 CHAR 时去掉尾随空格；Calcite 的 CHAR(n) 经过计算后会补满到 n 位，按 VARCHAR(n) 建模
                type = factory.createSqlType(SqlTypeName.VARCHAR,
                        column.precision() > 0 ? column.precision() : 1);
                break;
            case VARCHAR:
                type = factory.createSqlType(SqlTypeName.VARCHAR,
                        column.precision() > 0 ? column.precision() : 65535);
                break;
            default:
                type = factory.createSqlType(typeName);
                break;
        }
        if (column.type().category() == cn.vv.test.mysqlmock.schema.MysqlType.Category.STRING) {
            // MySQL utf8mb4 全 unicode；不设 UTF-8 时中文字面量会被 CAST 到 ISO-8859-1 失败
            type = factory.createTypeWithCharsetAndCollation(type,
                    java.nio.charset.StandardCharsets.UTF_8,
                    org.apache.calcite.sql.SqlCollation.IMPLICIT);
        }
        boolean nullable = !column.notNull() && !column.primaryKey() && !column.autoIncrement();
        return factory.createTypeWithNullability(type, nullable);
    }

    private static SqlTypeName sqlTypeName(MysqlType type) {
        switch (type) {
            case TINYINT:
            case BOOLEAN:
            case SMALLINT:
            case MEDIUMINT:
            case INT:
            case BIGINT:
                // 整型存储侧统一 Long（MysqlValueCoercer）；映射 INTEGER/TINYINT 的
                // enumerable 内部类型是 Integer，直接喂 Long 会 ClassCastException
                return SqlTypeName.BIGINT;
            case DECIMAL:
                return SqlTypeName.DECIMAL;
            case FLOAT:
                return SqlTypeName.REAL;
            case DOUBLE:
                return SqlTypeName.DOUBLE;
            case CHAR:
                return SqlTypeName.CHAR;
            case JSON:
            case VARCHAR:
            case TINYTEXT:
            case TEXT:
            case MEDIUMTEXT:
            case LONGTEXT:
                return SqlTypeName.VARCHAR;
            case DATE:
                return SqlTypeName.DATE;
            case DATETIME:
            case TIMESTAMP:
                return SqlTypeName.TIMESTAMP;
            default:
                throw new IllegalArgumentException("未映射的 MySQL 类型: " + type);
        }
    }
}
