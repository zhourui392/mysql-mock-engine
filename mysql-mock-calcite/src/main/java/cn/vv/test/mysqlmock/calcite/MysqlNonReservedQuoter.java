package cn.vv.test.mysqlmock.calcite;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 预处理：MySQL 非保留字作标识符时加反引号。
 * <p>{@code value}、{@code date}、{@code start} 等在 MySQL 是非保留字，业务表常直接用作列名且 SQL 不加反引号；
 * Calcite 解析器把它们当保留字，直接报语法错。这里只给「作标识符」的出现加反引号，以下用法保持关键字：
 * 后跟 {@code (}（函数调用、{@code INSERT ... VALUE (...)}）、后跟字符串字面量（{@code DATE '2026-01-01'}）、
 * 时间单位前面是数值 / 占位符 / 右括号 / 字符串（{@code INTERVAL ? DAY}）或位于 {@code EXTRACT(DAY FROM ...)}、
 * 类型名前面是 {@code AS}（{@code CAST(x AS DATE)}）。幂等；跳过注释与引号串。
 *
 * <p>名单只收「MySQL 8.0 非保留 + Calcite 拒作列名 + 业务表常见列名」的交集；{@code END}、{@code NEW} 等在
 * DML 里另有语法角色的词不收。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class MysqlNonReservedQuoter {

    static final Set<String> WORDS = new HashSet<>(Arrays.asList(
            "value", "year", "month", "day", "hour", "minute", "second", "date", "time", "timestamp",
            "position", "language", "result", "start", "member", "method", "period", "scope",
            "module", "percent", "open", "none"));

    private static final Set<String> TIME_UNITS = new HashSet<>(Arrays.asList(
            "year", "month", "day", "hour", "minute", "second"));

    private static final Set<String> TYPE_NAMES = new HashSet<>(Arrays.asList("date", "time", "timestamp"));

    private static final String STRING_TOKEN = "'";

    private static final Set<String> TIMESTAMP_FUNCTIONS = new HashSet<>(Arrays.asList(
            "timestampadd", "timestampdiff"));

    private MysqlNonReservedQuoter() {
    }

    static String quote(String sql) {
        StringBuilder out = new StringBuilder(sql.length() * 2);
        String[] recentTokens = {"", ""};
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            int end = SqlLexing.skipNonCode(sql, i);
            if (end > i) {
                out.append(sql, i, end);
                if (c == '\'' || c == '"') {
                    pushToken(recentTokens, STRING_TOKEN);
                }
            } else if (Character.isLetter(c) || c == '_') {
                end = SqlLexing.endOfWord(sql, i);
                String word = sql.substring(i, end);
                String lower = word.toLowerCase(Locale.ROOT);
                out.append(isIdentifierUse(lower, recentTokens, sql, end) ? "`" + word + "`" : word);
                pushToken(recentTokens, lower);
            } else {
                end = i + 1;
                out.append(c);
                if (!Character.isWhitespace(c)) {
                    pushToken(recentTokens, String.valueOf(c));
                }
            }
            i = end;
        }
        return out.toString();
    }

    /**
     * 记录新记号：recentTokens[0] 是前一个记号，recentTokens[1] 是再前一个。
     *
     * @param recentTokens 最近两个记号
     * @param token        新记号
     */
    private static void pushToken(String[] recentTokens, String token) {
        recentTokens[1] = recentTokens[0];
        recentTokens[0] = token;
    }

    private static boolean isIdentifierUse(String word, String[] recentTokens, String sql, int wordEnd) {
        if (!WORDS.contains(word)) {
            return false;
        }
        int nextStart = SqlLexing.skipWhitespace(sql, wordEnd);
        char next = nextStart < sql.length() ? sql.charAt(nextStart) : '\0';
        if (next == '(' || next == '\'') {
            return false;
        }
        if (TIME_UNITS.contains(word) && isUnitPosition(recentTokens, sql, nextStart)) {
            return false;
        }
        return !(TYPE_NAMES.contains(word) && "as".equals(recentTokens[0]));
    }

    /**
     * 时间单位是否处在关键字位置：{@code INTERVAL 1 DAY}、{@code INTERVAL ? DAY}、{@code INTERVAL (a+b) DAY}、
     * {@code INTERVAL '1' DAY}、{@code EXTRACT(DAY FROM x)}、{@code TIMESTAMPADD(DAY, ...)}。
     * {@code INSERT INTO t (day, ...)} 里的列名不算。
     *
     * @param recentTokens 前两个有效记号（小写）
     * @param sql          原始 SQL
     * @param nextStart    单位之后第一个非空白字符的下标
     * @return 处在关键字位置时为 true
     */
    private static boolean isUnitPosition(String[] recentTokens, String sql, int nextStart) {
        String previousToken = recentTokens[0];
        if ("(".equals(previousToken)) {
            return TIMESTAMP_FUNCTIONS.contains(recentTokens[1])
                    || sql.regionMatches(true, nextStart, "from", 0, "from".length());
        }
        boolean isNumber = !previousToken.isEmpty() && Character.isDigit(previousToken.charAt(0));
        return isNumber || "?".equals(previousToken) || ")".equals(previousToken)
                || STRING_TOKEN.equals(previousToken);
    }
}
