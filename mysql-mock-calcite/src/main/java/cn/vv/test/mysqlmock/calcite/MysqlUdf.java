package cn.vv.test.mysqlmock.calcite;

import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

/**
 * MySQL 风格 UDF（fun=all 未覆盖的部分）：NOW / CURDATE / DATE_FORMAT。
 * <p>IF 不注册：fun=all 的 BIG_QUERY 库已提供 IF(cond, a, b)（cond 非 TRUE 即取 else 分支，
 * 与 MySQL NULL 视作假一致）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlUdf {

    private MysqlUdf() {
    }

    /** 当前时间戳。 */
    public static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    /** 当前日期。 */
    public static Date curdate() {
        return new Date(System.currentTimeMillis());
    }

    /**
     * MySQL FIND_IN_SET(needle, list)：逗号分隔集合中的 1 起始下标，未命中 0，任一侧 NULL 返回 NULL。
     *
     * @param needle 待找值
     * @param list   逗号分隔集合
     * @return 1 起始下标或 0
     */
    public static Long findInSet(String needle, String list) {
        if (needle == null || list == null) {
            return null;
        }
        String target = needle;
        int index = 1;
        for (String part : list.split(",", -1)) {
            if (part.equals(target)) {
                return (long) index;
            }
            index++;
        }
        return 0L;
    }

    /**
     * MySQL DATE_FORMAT。
     *
     * @param timestamp 时间值
     * @param pattern   MySQL 格式串（如 '%Y-%m-%d %H:%i:%s'）
     * @return 格式化结果
     */
    public static String date_format(Timestamp timestamp, String pattern) {
        if (timestamp == null || pattern == null) {
            return null;
        }
        SimpleDateFormat mysqlFormat = new SimpleDateFormat(toJavaPattern(pattern));
        return mysqlFormat.format(timestamp);
    }

    /**
     * MySQL 格式串 → Java SimpleDateFormat 模式（覆盖常用子集）。
     *
     * @param mysqlPattern MySQL 格式串
     * @return Java 模式
     */
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
