package cn.vv.test.mysqlmock.calcite;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import org.apache.calcite.linq4j.function.NonDeterministic;

/**
 * MySQL 风格 UDF（fun=all 未覆盖的部分）：时间（NOW / CURDATE / DATE_FORMAT / STR_TO_DATE / TO_DAYS /
 * MAKEDATE / WEEKDAY / UNIX_TIMESTAMP / FROM_UNIXTIME / UTC_TIMESTAMP / TIME）与字符串（FIND_IN_SET / LOCATE / UUID /
 * STRCMP / HEX）。
 * <p>IF 不注册：fun=all 的 BIG_QUERY 库已提供 IF(cond, a, b)（cond 非 TRUE 即取 else 分支，
 * 与 MySQL NULL 视作假一致）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlUdf {

    /** MySQL TO_DAYS('1970-01-01') 的值：公元 0 年起的天数偏移。 */
    private static final long EPOCH_TO_DAYS = 719528L;

    private static final long MILLIS_PER_SECOND = 1000L;

    private static final int DATE_TEXT_LENGTH = "yyyy-MM-dd".length();

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
     * MySQL STR_TO_DATE：按 MySQL 格式串解析；解析失败返回 NULL（MySQL 同样返回 NULL 并告警）。
     *
     * @param text    文本
     * @param pattern MySQL 格式串
     * @return 时间值
     */
    public static Timestamp strToDate(String text, String pattern) {
        if (text == null || pattern == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat(toJavaPattern(pattern), Locale.ROOT);
        format.setLenient(false);
        try {
            return new Timestamp(format.parse(text).getTime());
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * MySQL TO_DAYS：公元 0 年起的天数。
     *
     * @param date 日期
     * @return 天数
     */
    public static Long toDays(Date date) {
        return date == null ? null : date.toLocalDate().toEpochDay() + EPOCH_TO_DAYS;
    }

    /**
     * MySQL TO_DAYS（时间参数取日期部分）。
     *
     * @param timestamp 时间
     * @return 天数
     */
    public static Long toDays(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate().toEpochDay() + EPOCH_TO_DAYS;
    }

    /**
     * MySQL DATEDIFF(a, b)：只看日期部分的天数差 a - b。参数按文本接收（DATE / DATETIME 列与字面量都会转成
     * {@code yyyy-MM-dd[ HH:mm:ss]}），取前 10 位。
     *
     * @param later   被减日期
     * @param earlier 减去的日期
     * @return 天数差
     */
    public static Long mysqlDatediff(String later, String earlier) {
        if (later == null || earlier == null) {
            return null;
        }
        return datePart(later).toEpochDay() - datePart(earlier).toEpochDay();
    }

    private static LocalDate datePart(String text) {
        String trimmed = text.trim();
        return LocalDate.parse(trimmed.length() > DATE_TEXT_LENGTH ? trimmed.substring(0, DATE_TEXT_LENGTH) : trimmed);
    }

    /**
     * MySQL MAKEDATE(year, dayofyear)；dayofyear 小于 1 返回 NULL。
     *
     * @param year      年
     * @param dayOfYear 年内第几天
     * @return 日期
     */
    public static Date makedate(Long year, Long dayOfYear) {
        if (year == null || dayOfYear == null || dayOfYear < 1) {
            return null;
        }
        return Date.valueOf(LocalDate.of(year.intValue(), 1, 1).plusDays(dayOfYear - 1));
    }

    /**
     * MySQL WEEKDAY：周一为 0。
     *
     * @param date 日期
     * @return 0–6
     */
    public static Long weekday(Date date) {
        return date == null ? null : (long) date.toLocalDate().getDayOfWeek().getValue() - 1;
    }

    /**
     * MySQL WEEKDAY（时间参数）。
     *
     * @param timestamp 时间
     * @return 0–6
     */
    public static Long weekday(Timestamp timestamp) {
        return timestamp == null ? null : weekday(Date.valueOf(timestamp.toLocalDateTime().toLocalDate()));
    }

    /**
     * MySQL UNIX_TIMESTAMP()：当前秒数。
     *
     * @return 秒
     */
    public static Long unixTimestamp() {
        return System.currentTimeMillis() / MILLIS_PER_SECOND;
    }

    /**
     * MySQL UNIX_TIMESTAMP(ts)：按 JVM 时区（即会话时区）换算秒数。
     *
     * @param timestamp 时间
     * @return 秒
     */
    public static Long unixTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.getTime() / MILLIS_PER_SECOND;
    }

    /**
     * MySQL FROM_UNIXTIME(seconds)。
     *
     * @param seconds 秒
     * @return 时间
     */
    public static Timestamp fromUnixtime(Long seconds) {
        return seconds == null ? null : new Timestamp(seconds * MILLIS_PER_SECOND);
    }

    /**
     * MySQL UTC_TIMESTAMP()。
     *
     * @return 当前 UTC 墙钟时间
     */
    public static Timestamp utcTimestamp() {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * MySQL LOCATE(substr, str)：1 起始位置，忽略大小写（_ci），未命中 0。
     *
     * @param needle   子串
     * @param haystack 原串
     * @return 位置
     */
    public static Long locate(String needle, String haystack) {
        return locate(needle, haystack, 1L);
    }

    /**
     * MySQL LOCATE(substr, str, pos)。
     *
     * @param needle   子串
     * @param haystack 原串
     * @param position 起始位置（1 起）
     * @return 位置
     */
    public static Long locate(String needle, String haystack, Long position) {
        if (needle == null || haystack == null || position == null) {
            return null;
        }
        if (position < 1 || position > haystack.length() + 1) {
            return 0L;
        }
        int found = haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT),
                position.intValue() - 1);
        return (long) found + 1;
    }

    /**
     * MySQL UUID()（改写为 MYSQL_UUID()：UUID 在 Calcite 是类型关键字）。
     *
     * @return 36 位 UUID 文本
     */
    public static String mysqlUuid() {
        return UUID.randomUUID().toString();
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

    /**
     * MySQL TIME(datetime)：取时间部分。
     *
     * @param value 日期时间
     * @return 时间
     */
    public static Time mysqlTime(Timestamp value) {
        return value == null ? null : Time.valueOf(value.toLocalDateTime().toLocalTime().withNano(0));
    }

    /**
     * MySQL TIME(text)：文本是日期时间时取空格后的部分，再按 {@code H:m[:s[.f]]} 解析，秒的小数部分舍去。
     *
     * @param text 日期时间或时间文本
     * @return 时间；无法解析时返回 NULL（MySQL 同样返回 NULL 并给出警告）
     */
    public static Time mysqlTime(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        String timePart = trimmed.substring(trimmed.lastIndexOf(' ') + 1);
        String[] fields = timePart.split(":");
        try {
            int hour = Integer.parseInt(fields[0]);
            int minute = fields.length > 1 ? Integer.parseInt(fields[1]) : 0;
            int second = fields.length > 2 ? (int) Double.parseDouble(fields[2]) : 0;
            return Time.valueOf(LocalTime.of(hour, minute, second));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * MySQL STRCMP：按 _ci 规则（忽略大小写与尾随空格）比较。
     *
     * @param left  左值
     * @param right 右值
     * @return -1 / 0 / 1；任一侧 NULL 返回 NULL
     */
    public static Long mysqlStrcmp(String left, String right) {
        if (left == null || right == null) {
            return null;
        }
        int compared = ciNormalize(left).compareTo(ciNormalize(right));
        return (long) Integer.signum(compared);
    }

    /**
     * MySQL HEX(n)：整数的十六进制大写形式。
     *
     * @param value 整数
     * @return 十六进制文本
     */
    public static String mysqlHex(long value) {
        return Long.toHexString(value).toUpperCase(Locale.ROOT);
    }

    /**
     * MySQL HEX(str)：字符串 UTF-8 字节的十六进制大写形式。
     *
     * @param text 字符串
     * @return 十六进制文本
     */
    public static String mysqlHex(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /**
     * MySQL LAST_INSERT_ID(expr)：返回 expr，并把它记为当前引擎后续 LAST_INSERT_ID() 的值。
     * 标为非确定性，避免 Calcite 在计划阶段把常量调用折叠掉、少记一次。
     *
     * @param value 新值
     * @return 新值；NULL 时不记录
     */
    @NonDeterministic
    public static Long mysqlLastInsertId(Long value) {
        if (value != null) {
            LastInsertIdContext.record(value);
        }
        return value;
    }

    private static String ciNormalize(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
