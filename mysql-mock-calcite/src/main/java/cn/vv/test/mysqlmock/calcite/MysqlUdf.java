package cn.vv.test.mysqlmock.calcite;

import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

/** MySQL 风格 UDF（fun=all 未覆盖的部分）：NOW / CURDATE / DATE_FORMAT / IF。 */
public final class MysqlUdf {

    private MysqlUdf() {
    }

    public static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    public static Date curdate() {
        return new Date(System.currentTimeMillis());
    }

    public static String date_format(Timestamp timestamp, String pattern) {
        if (timestamp == null || pattern == null) {
            return null;
        }
        SimpleDateFormat mysqlFormat = new SimpleDateFormat(toJavaPattern(pattern));
        return mysqlFormat.format(timestamp);
    }

    public static String if_(Boolean condition, String thenValue, String elseValue) {
        return Boolean.TRUE.equals(condition) ? thenValue : elseValue;
    }

    public static Long if_(Boolean condition, Long thenValue, Long elseValue) {
        return Boolean.TRUE.equals(condition) ? thenValue : elseValue;
    }

    public static java.math.BigDecimal if_(Boolean condition, java.math.BigDecimal thenValue, java.math.BigDecimal elseValue) {
        return Boolean.TRUE.equals(condition) ? thenValue : elseValue;
    }

    public static Long if_(Boolean condition, Long thenValue, java.math.BigDecimal elseValue) {
        return Boolean.TRUE.equals(condition) ? thenValue : null;
    }

    /** MySQL 格式串 → Java SimpleDateFormat 模式（覆盖常用子集）。 */
    static String toJavaPattern(String mysqlPattern) {
        StringBuilder sb = new StringBuilder();
        char[] chars = mysqlPattern.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '%' && i + 1 < chars.length) {
                char next = chars[++i];
                switch (next) {
                    case 'Y': sb.append("yyyy"); break;
                    case 'y': sb.append("yy"); break;
                    case 'm': sb.append("MM"); break;
                    case 'c': sb.append("M"); break;
                    case 'd': sb.append("dd"); break;
                    case 'e': sb.append("d"); break;
                    case 'H': sb.append("HH"); break;
                    case 'k': sb.append("H"); break;
                    case 'i': sb.append("mm"); break;
                    case 's': sb.append("ss"); break;
                    case 'S': sb.append("SSS"); break;
                    case 'f': sb.append("SSS"); break;
                    case 'T': sb.append("HH:mm:ss"); break;
                    case 'r': sb.append("hh:mm:ss a"); break;
                    case 'p': sb.append("a"); break;
                    case 'W': sb.append("EEEE"); break;
                    case 'a': sb.append("a"); break;
                    case 'b': sb.append("MMM"); break;
                    case 'M': sb.append("MMMM"); break;
                    case 'j': sb.append("DDD"); break;
                    case '%': sb.append('%'); break;
                    default: sb.append(next); break;
                }
            } else if (c == '\'' || c == '"') {
                sb.append('\'').append('\'');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
