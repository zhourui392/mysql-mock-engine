package cn.vv.test.mysqlmock.compat.diff;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 差分结果归一（D-09）：只归一协议表示——数值按 BigDecimal 值等价、时间统一
 * 'yyyy-MM-dd HH:mm:ss[.SSS]' 字符串、布尔→1/0、无 ORDER BY 的行集排序后比较；
 * 不改写语义。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class ResultNormalizer {

    private ResultNormalizer() {
    }

    /** 单值归一。 */
    static Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return String.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "1" : "0";
        }
        if (value instanceof Timestamp) {
            return formatTimestamp((Timestamp) value);
        }
        if (value instanceof java.time.LocalDateTime) {
            return formatNanos(value.toString().replace('T', ' '));
        }
        if (value instanceof java.time.LocalDate || value instanceof java.time.LocalTime) {
            return value.toString();
        }
        if (value instanceof java.sql.Date) {
            return value.toString();
        }
        if (value instanceof java.util.Date) {
            return formatTimestamp(new Timestamp(((java.util.Date) value).getTime()));
        }
        if (value instanceof byte[]) {
            return Arrays.toString((byte[]) value);
        }
        return String.valueOf(value);
    }

    /** 行集归一：列名小写、值归一；ordered=false 时行排序（无 ORDER BY 不比顺序）。 */
    static List<String> normalizeRows(List<Object[]> rows, boolean ordered) {
        List<String> lines = new ArrayList<>();
        for (Object[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    line.append('|');
                }
                line.append(normalizeValue(row[i]));
            }
            lines.add(line.toString());
        }
        if (!ordered) {
            Collections.sort(lines);
        }
        return lines;
    }

    /** 统一 'yyyy-MM-dd HH:mm:ss[.SSS]'，毫秒三位补齐（协议表示归一）。 */
    private static String formatTimestamp(Timestamp timestamp) {
        return formatNanos(timestamp.toString().replace('T', ' '));
    }

    private static String formatNanos(String text) {
        int dot = text.indexOf('.');
        if (dot < 0) {
            return text;
        }
        String millis = text.substring(dot + 1);
        if (millis.replaceAll("0+", "").isEmpty()) {
            return text.substring(0, dot);
        }
        // .5 → .500（两侧精度位数差异归一）
        if (millis.length() < 3) {
            StringBuilder padded = new StringBuilder(millis);
            while (padded.length() < 3) {
                padded.append('0');
            }
            millis = padded.toString();
        }
        return text.substring(0, dot + 1) + millis;
    }
}
