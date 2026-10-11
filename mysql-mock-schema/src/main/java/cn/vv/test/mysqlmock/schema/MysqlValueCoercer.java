package cn.vv.test.mysqlmock.schema;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/** 值 → 存储表示的转换（按列类型，D-08：金额 BigDecimal、时间保精度）。 */
public final class MysqlValueCoercer {

    private MysqlValueCoercer() {
    }

    public static Object coerce(ColumnDefinition column, Object value) {
        if (value == null) {
            return null;
        }
        switch (column.type().category()) {
            case INT:
                return coerceInteger(value);
            case DECIMAL:
                return coerceDecimal(column, value);
            case DOUBLE:
                return value instanceof Number
                        ? ((Number) value).doubleValue()
                        : Double.parseDouble(String.valueOf(value).trim());
            case STRING:
                return String.valueOf(value);
            case TEMPORAL:
                return coerceTemporal(column, value);
            default:
                return value;
        }
    }

    private static Long coerceInteger(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value ? 1L : 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    /** DECIMAL 统一到列 scale（MySQL 语义：DECIMAL(p,s) 存储值固定 s 位小数）。 */
    private static BigDecimal coerceDecimal(ColumnDefinition column, Object value) {
        BigDecimal decimal;
        if (value instanceof BigDecimal) {
            decimal = (BigDecimal) value;
        } else if (value instanceof Long || value instanceof Integer) {
            decimal = BigDecimal.valueOf(((Number) value).longValue());
        } else if (value instanceof Number) {
            decimal = new BigDecimal(String.valueOf(value));
        } else {
            decimal = new BigDecimal(String.valueOf(value).trim());
        }
        if (column.scale() > 0) {
            return decimal.setScale(column.scale(), java.math.RoundingMode.HALF_UP);
        }
        return decimal;
    }

    private static Object coerceTemporal(ColumnDefinition column, Object value) {
        if (column.type() == MysqlType.DATE) {
            if (value instanceof java.sql.Date) {
                return value;
            }
            if (value instanceof Timestamp) {
                return new java.sql.Date(((Timestamp) value).getTime());
            }
            if (value instanceof java.util.Date) {
                return new java.sql.Date(((java.util.Date) value).getTime());
            }
            return java.sql.Date.valueOf(LocalDate.parse(String.valueOf(value).trim()));
        }
        if (value instanceof Timestamp) {
            return value;
        }
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        if (value instanceof Number) {
            return new Timestamp(((Number) value).longValue());
        }
        return parseTimestamp(String.valueOf(value).trim());
    }

    /** 支持 yyyy-MM-dd HH:mm:ss[.SSS]、yyyy-MM-dd'T'HH:mm:ss[.SSS]、yyyy-MM-dd（零点）。 */
    private static Timestamp parseTimestamp(String text) {
        String normalized = text.contains(" ") && !text.contains("T") ? text.replaceFirst(" ", "T") : text;
        try {
            return Timestamp.valueOf(LocalDateTime.parse(normalized));
        } catch (DateTimeParseException e) {
            return Timestamp.valueOf(LocalDate.parse(normalized).atStartOfDay());
        }
    }
}
