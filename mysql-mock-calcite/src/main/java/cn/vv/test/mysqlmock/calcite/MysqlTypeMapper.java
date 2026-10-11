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
                type = factory.createSqlType(SqlTypeName.CHAR,
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
        boolean nullable = !column.notNull() && !column.primaryKey() && !column.autoIncrement();
        return factory.createTypeWithNullability(type, nullable);
    }

    private static SqlTypeName sqlTypeName(MysqlType type) {
        switch (type) {
            case TINYINT:
                return SqlTypeName.TINYINT;
            case BOOLEAN:
                return SqlTypeName.BOOLEAN;
            case SMALLINT:
                return SqlTypeName.SMALLINT;
            case MEDIUMINT:
            case INT:
                return SqlTypeName.INTEGER;
            case BIGINT:
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
