package cn.vv.test.mysqlmock.calcite;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 预处理：把 Calcite 不认识的 MySQL 写法改写为等价的 Calcite 写法。
 * <p>运算符：{@code col->'$.p'}、{@code col->>'$.p'}、{@code [NOT] REGEXP / RLIKE}（_ci 下忽略大小写）。
 * 函数：{@code TRIM(x)}、{@code CAST/CONVERT ... AS CHAR / SIGNED / UNSIGNED}、{@code CONVERT(x USING cs)}、
 * {@code GROUP_CONCAT([DISTINCT] ... [ORDER BY ...] [SEPARATOR 's'])}、参数化 {@code INTERVAL}、两参
 * {@code DATEDIFF}（Calcite 内置的 DATEDIFF 是三参，改名交给 UDF）、{@code DAY / MID / ISNULL / FIELD / UUID}、
 * {@code CURTIME / TIME / STRCMP / HEX / LAST_INSERT_ID}、JSON 构造与修改函数（{@link MysqlJsonRewrites}）。
 * 其余函数由 UDF 提供（{@link MysqlUdf}、{@link MysqlJsonUdf}、{@link MysqlJsonWriteUdf}）。跳过注释与引号串；不认识的形态原样保留，交给 Calcite 报错。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class MysqlDialectRewriter {

    private static final String OPERAND = "(?:`[^`]+`|[A-Za-z_][\\w$]*)(?:\\.(?:`[^`]+`|[A-Za-z_][\\w$]*))*"
            + "|'(?:[^'\\\\]|\\\\.|'')*'|\\?";

    private static final Pattern LEFT_OPERAND = Pattern.compile("(" + OPERAND + ")\\s*$");

    private static final Pattern REGEXP_LEFT = Pattern.compile("(" + OPERAND + ")\\s+(NOT\\s+)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CHAR_WITH_LENGTH = Pattern.compile("(?i)CHAR\\s*(\\(\\s*\\d+\\s*\\))");

    private static final Pattern INTEGER_TYPE = Pattern.compile("(?i)(SIGNED|UNSIGNED)(\\s+INT(EGER)?)?");

    private static final Pattern INTEGER_LITERAL = Pattern.compile("-?\\d+");

    private static final Set<String> INTERVAL_UNITS = new HashSet<>(Arrays.asList(
            "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR"));

    private static final String ARROW = "->";

    private static final String UNQUOTE_ARROW = "->>";

    private static final Map<String, BinaryOperator<String>> CALLS = new HashMap<>();

    /** UDF、REGEXP_LIKE、CONCAT、TIMESTAMPADD 的参数类型：裸 {@code ?} 参数 Calcite 推不出类型，按位置补 CAST（末项适用于后续位置）。 */
    private static final Map<String, String[]> PARAM_TYPES = new HashMap<>();

    /** 返回 1 / 0 的函数：MySQL 允许直接当条件，Calcite 要求布尔。 */
    private static final Set<String> FLAG_FUNCTIONS = new HashSet<>(Arrays.asList(
            "JSON_CONTAINS", "JSON_CONTAINS_PATH", "JSON_OVERLAPS", "FIND_IN_SET", "LOCATE"));

    private static final Set<String> PREDICATE_KEYWORDS = new HashSet<>(Arrays.asList(
            "WHERE", "AND", "OR", "NOT", "HAVING", "ON", "WHEN"));

    private static final Set<String> COMPARISON_KEYWORDS = new HashSet<>(Arrays.asList(
            "IS", "IN", "BETWEEN", "LIKE", "NOT", "REGEXP", "RLIKE"));

    private static final String COMPARISON_CHARS = "=<>!+-*/%";

    static {
        PARAM_TYPES.put("LOCATE", new String[]{"VARCHAR", "VARCHAR", "BIGINT"});
        PARAM_TYPES.put("FIND_IN_SET", new String[]{"VARCHAR"});
        PARAM_TYPES.put("FROM_UNIXTIME", new String[]{"BIGINT"});
        PARAM_TYPES.put("UNIX_TIMESTAMP", new String[]{"TIMESTAMP"});
        PARAM_TYPES.put("TO_DAYS", new String[]{"TIMESTAMP"});
        PARAM_TYPES.put("WEEKDAY", new String[]{"TIMESTAMP"});
        PARAM_TYPES.put("MAKEDATE", new String[]{"BIGINT"});
        PARAM_TYPES.put("STR_TO_DATE", new String[]{"VARCHAR"});
        PARAM_TYPES.put("DATE_FORMAT", new String[]{"TIMESTAMP", "VARCHAR"});
        PARAM_TYPES.put("MYSQL_DATEDIFF", new String[]{"VARCHAR"});
        PARAM_TYPES.put("REGEXP_LIKE", new String[]{"VARCHAR"});
        PARAM_TYPES.put("CONCAT", new String[]{"VARCHAR"});
        PARAM_TYPES.put("CONCAT_WS", new String[]{"VARCHAR"});
        PARAM_TYPES.put("TIMESTAMPADD", new String[]{"VARCHAR", "INTEGER", "TIMESTAMP"});
        PARAM_TYPES.put("TIMESTAMPDIFF", new String[]{"VARCHAR", "TIMESTAMP"});
        for (String json : Arrays.asList("JSON_EXTRACT", "JSON_UNQUOTE", "JSON_CONTAINS", "JSON_CONTAINS_PATH",
                "JSON_SEARCH", "JSON_QUOTE", "JSON_OVERLAPS", "MYSQL_JSON_KEYS", "MYSQL_TIME", "MYSQL_STRCMP",
                "MYSQL_HEX")) {
            PARAM_TYPES.put(json, new String[]{"VARCHAR"});
        }
        for (String name : PARAM_TYPES.keySet()) {
            CALLS.put(name, (original, args) -> typedCall(original, SqlLexing.splitTopLevel(args)));
        }
        CALLS.put("TRIM", (name, args) -> rewriteTrim(args));
        CALLS.put("CAST", (name, args) -> rewriteCast(args));
        CALLS.put("CONVERT", (name, args) -> rewriteConvert(args));
        CALLS.put("GROUP_CONCAT", (name, args) -> MysqlGroupConcat.toListAgg(args));
        CALLS.put("DATE_ADD", MysqlDialectRewriter::rewriteDateArithmetic);
        CALLS.put("ADDDATE", MysqlDialectRewriter::rewriteDateArithmetic);
        CALLS.put("DATE_SUB", MysqlDialectRewriter::rewriteDateArithmetic);
        CALLS.put("SUBDATE", MysqlDialectRewriter::rewriteDateArithmetic);
        CALLS.put("DATEDIFF", (name, args) -> rewriteDateDiff(args));
        CALLS.put("DAY", (name, args) -> "DAYOFMONTH(" + args + ")");
        CALLS.put("MID", (name, args) -> "SUBSTRING(" + args + ")");
        CALLS.put("ISNULL", (name, args) -> "((" + args + ") IS NULL)");
        CALLS.put("FIELD", (name, args) -> rewriteField(args));
        CALLS.put("UUID", (name, args) -> args.trim().isEmpty() ? "MYSQL_UUID()" : name + "(" + args + ")");
        CALLS.put("CURTIME", (name, args) -> args.trim().isEmpty() ? "CURRENT_TIME" : name + "(" + args + ")");
        CALLS.put("TIME", (name, args) -> renameSingleArg(name, args, "MYSQL_TIME"));
        CALLS.put("STRCMP", (name, args) -> typedCall("MYSQL_STRCMP", SqlLexing.splitTopLevel(args)));
        CALLS.put("HEX", (name, args) -> typedCall("MYSQL_HEX", SqlLexing.splitTopLevel(args)));
        CALLS.put("JSON_KEYS", (name, args) -> typedCall("MYSQL_JSON_KEYS", SqlLexing.splitTopLevel(args)));
        for (String builder : Arrays.asList("JSON_OBJECT", "JSON_ARRAY", "JSON_OBJECTAGG", "JSON_ARRAYAGG")) {
            CALLS.put(builder, MysqlJsonRewrites::formatBuilder);
        }
        for (String modify : Arrays.asList("JSON_SET", "JSON_INSERT", "JSON_REPLACE")) {
            CALLS.put(modify, MysqlJsonRewrites::modify);
        }
        CALLS.put("JSON_REMOVE", MysqlJsonRewrites::remove);
        CALLS.put("JSON_EXTRACT", MysqlJsonRewrites::extract);
    }

    private MysqlDialectRewriter() {
    }

    /**
     * 改写入口。
     *
     * @param sql          已给非保留字列名加引号的 SQL
     * @param lastInsertId 当前 LAST_INSERT_ID() 的值，按字面量代入；带参数的 LAST_INSERT_ID(expr) 改为 UDF 调用
     * @return Calcite 可解析的 SQL
     */
    static String rewrite(String sql, long lastInsertId) {
        Map<String, BinaryOperator<String>> calls = new HashMap<>(CALLS);
        calls.put("LAST_INSERT_ID", (name, args) -> args.trim().isEmpty() ? "CAST(" + lastInsertId + " AS BIGINT)"
                : "MYSQL_LAST_INSERT_ID(CAST(" + args + " AS BIGINT))");
        return rewriteCalls(rewriteOperators(sql), calls);
    }

    private static String rewriteCalls(String sql, Map<String, BinaryOperator<String>> calls) {
        StringBuilder out = new StringBuilder(sql.length() * 2);
        int i = 0;
        while (i < sql.length()) {
            int end = SqlLexing.skipNonCode(sql, i);
            char c = sql.charAt(i);
            if (end > i) {
                out.append(sql, i, end);
            } else if (Character.isLetter(c) || c == '_') {
                end = appendWordOrCall(sql, i, out, calls);
            } else {
                out.append(c);
                end = i + 1;
            }
            i = end;
        }
        return out.toString();
    }

    /**
     * 处理 start 处的单词：是待改写函数的调用时整体改写（参数先递归改写），否则原样输出。
     *
     * @param sql   SQL 文本
     * @param start 单词起点
     * @param out   输出缓冲
     * @param calls 函数名到改写器的映射
     * @return 下一个待扫描下标
     */
    private static int appendWordOrCall(String sql, int start, StringBuilder out,
                                        Map<String, BinaryOperator<String>> calls) {
        int wordEnd = SqlLexing.endOfWord(sql, start);
        String word = sql.substring(start, wordEnd);
        int open = SqlLexing.skipWhitespace(sql, wordEnd);
        BinaryOperator<String> handler = calls.get(word.toUpperCase(Locale.ROOT));
        boolean isQualified = start > 0 && sql.charAt(start - 1) == '.';
        int close = open < sql.length() && sql.charAt(open) == '(' ? SqlLexing.closingParen(sql, open) : -1;
        if (handler == null || isQualified || close < 0) {
            out.append(word);
            return wordEnd;
        }
        String replacement = handler.apply(word, rewriteCalls(sql.substring(open + 1, close), calls));
        boolean isBarePredicate = FLAG_FUNCTIONS.contains(word.toUpperCase(Locale.ROOT))
                && isPredicateContext(out) && !isFollowedByComparison(sql, close + 1);
        out.append(isBarePredicate ? "(" + replacement + " <> 0)" : replacement);
        return close + 1;
    }

    /**
     * 拼函数调用，裸 {@code ?} 参数按 {@link #PARAM_TYPES} 补 CAST。
     *
     * @param name 函数名
     * @param args 已切分的参数
     * @return 函数调用文本
     */
    private static String typedCall(String name, List<String> args) {
        String[] types = PARAM_TYPES.get(name.toUpperCase(Locale.ROOT));
        StringBuilder call = new StringBuilder(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            boolean isBareParam = types != null && "?".equals(arg);
            call.append(i > 0 ? ", " : "")
                    .append(isBareParam ? "CAST(? AS " + types[Math.min(i, types.length - 1)] + ")" : arg);
        }
        return call.append(')').toString();
    }

    /**
     * 输出缓冲末尾是否为条件位置：前一个记号是 WHERE / AND / OR 等关键字，或是分组用的左括号。
     *
     * @param out 已输出的 SQL
     * @return 条件位置时为 true
     */
    private static boolean isPredicateContext(StringBuilder out) {
        int end = trimmedEnd(out, out.length());
        if (end > 0 && out.charAt(end - 1) == '(') {
            int beforeParen = trimmedEnd(out, end - 1);
            boolean isGroupingParen = beforeParen == 0 || !SqlLexing.isWordChar(out.charAt(beforeParen - 1))
                    && out.charAt(beforeParen - 1) != ')';
            return isGroupingParen;
        }
        int start = end;
        while (start > 0 && SqlLexing.isWordChar(out.charAt(start - 1))) {
            start--;
        }
        return PREDICATE_KEYWORDS.contains(out.substring(start, end).toUpperCase(Locale.ROOT));
    }

    private static int trimmedEnd(StringBuilder out, int from) {
        int end = from;
        while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static boolean isFollowedByComparison(String sql, int from) {
        int next = SqlLexing.skipWhitespace(sql, from);
        if (next >= sql.length()) {
            return false;
        }
        if (COMPARISON_CHARS.indexOf(sql.charAt(next)) >= 0) {
            return true;
        }
        String word = sql.substring(next, SqlLexing.endOfWord(sql, next)).toUpperCase(Locale.ROOT);
        return COMPARISON_KEYWORDS.contains(word);
    }

    private static String rewriteOperators(String sql) {
        StringBuilder out = new StringBuilder(sql.length() * 2);
        int i = 0;
        while (i < sql.length()) {
            int end = SqlLexing.skipNonCode(sql, i);
            if (end > i) {
                out.append(sql, i, end);
            } else if (sql.startsWith(ARROW, i)) {
                end = rewriteJsonArrow(sql, i, out);
            } else if (SqlLexing.matchesKeywordAt(sql, i, "REGEXP") >= 0
                    || SqlLexing.matchesKeywordAt(sql, i, "RLIKE") >= 0) {
                end = rewriteRegexp(sql, i, out);
            } else {
                out.append(sql.charAt(i));
                end = i + 1;
            }
            i = end;
        }
        return out.toString();
    }

    private static int rewriteJsonArrow(String sql, int arrow, StringBuilder out) {
        boolean isUnquote = sql.startsWith(UNQUOTE_ARROW, arrow);
        int pathStart = SqlLexing.skipWhitespace(sql, arrow + (isUnquote ? UNQUOTE_ARROW : ARROW).length());
        int pathEnd = pathStart < sql.length() ? SqlLexing.skipNonCode(sql, pathStart) : pathStart;
        Matcher left = LEFT_OPERAND.matcher(out);
        if (pathEnd == pathStart || !left.find()) {
            out.append(ARROW);
            return arrow + ARROW.length();
        }
        String extract = "JSON_EXTRACT(" + left.group(1) + ", " + sql.substring(pathStart, pathEnd) + ")";
        out.setLength(left.start());
        out.append(isUnquote ? "JSON_UNQUOTE(" + extract + ")" : extract);
        return pathEnd;
    }

    private static int rewriteRegexp(String sql, int keyword, StringBuilder out) {
        int keywordEnd = SqlLexing.endOfWord(sql, keyword);
        int rightStart = SqlLexing.skipWhitespace(sql, keywordEnd);
        int rightEnd = endOfOperand(sql, rightStart);
        Matcher left = REGEXP_LEFT.matcher(out);
        if (rightEnd == rightStart || !left.find()) {
            out.append(sql, keyword, keywordEnd);
            return keywordEnd;
        }
        String call = "REGEXP_LIKE(" + left.group(1) + ", " + sql.substring(rightStart, rightEnd) + ", 'i')";
        boolean isNegated = left.group(2) != null;
        out.setLength(left.start());
        out.append(isNegated ? "NOT " + call : call).append(' ');
        return rightEnd;
    }

    private static int endOfOperand(String sql, int start) {
        if (start >= sql.length()) {
            return start;
        }
        char c = sql.charAt(start);
        if (c == '\'' || c == '?') {
            return c == '?' ? start + 1 : SqlLexing.skipNonCode(sql, start);
        }
        int j = start;
        while (j < sql.length() && (SqlLexing.isWordChar(sql.charAt(j)) || sql.charAt(j) == '.')) {
            j++;
        }
        return j;
    }

    private static String rewriteTrim(String args) {
        boolean hasSpecifier = SqlLexing.matchesKeywordAt(args.trim(), 0, "BOTH") >= 0
                || SqlLexing.matchesKeywordAt(args.trim(), 0, "LEADING") >= 0
                || SqlLexing.matchesKeywordAt(args.trim(), 0, "TRAILING") >= 0
                || SqlLexing.lastTopLevelKeyword(args, "FROM") >= 0;
        return hasSpecifier ? "TRIM(" + args + ")" : "TRIM(BOTH ' ' FROM " + args + ")";
    }

    private static String rewriteCast(String args) {
        int as = SqlLexing.lastTopLevelKeyword(args, "AS");
        if (as < 0) {
            return "CAST(" + args + ")";
        }
        return "CAST(" + args.substring(0, as) + "AS " + toCalciteType(args.substring(as + 2).trim()) + ")";
    }

    private static String rewriteConvert(String args) {
        int using = SqlLexing.lastTopLevelKeyword(args, "USING");
        if (using >= 0) {
            return "(" + args.substring(0, using).trim() + ")";
        }
        List<String> parts = SqlLexing.splitTopLevel(args);
        return parts.size() == 2 ? "CAST(" + parts.get(0) + " AS " + toCalciteType(parts.get(1)) + ")"
                : "CONVERT(" + args + ")";
    }

    /**
     * MySQL CAST 目标类型 → Calcite：CHAR 无长度时是不定长串，SIGNED / UNSIGNED 是 64 位整数。
     *
     * @param mysqlType MySQL 类型名
     * @return Calcite 类型名
     */
    private static String toCalciteType(String mysqlType) {
        if ("CHAR".equalsIgnoreCase(mysqlType)) {
            return "VARCHAR";
        }
        Matcher charWithLength = CHAR_WITH_LENGTH.matcher(mysqlType);
        if (charWithLength.matches()) {
            return "VARCHAR" + charWithLength.group(1);
        }
        return INTEGER_TYPE.matcher(mysqlType).matches() ? "BIGINT" : mysqlType;
    }

    /**
     * DATE_ADD / DATE_SUB / ADDDATE / SUBDATE：间隔量不是整数字面量时改写为 TIMESTAMPADD
     * （Calcite 的 INTERVAL 只接受字面量）；整数字面量形态 Calcite 原生支持，保持原样。
     *
     * @param name 函数名（原样）
     * @param args 参数文本
     * @return 改写结果
     */
    private static String rewriteDateArithmetic(String name, String args) {
        List<String> parts = SqlLexing.splitTopLevel(args);
        String upperName = name.toUpperCase(Locale.ROOT);
        if (parts.size() != 2) {
            return name + "(" + args + ")";
        }
        String amount = parts.get(1);
        String unit = "DAY";
        int intervalEnd = SqlLexing.matchesKeywordAt(amount, 0, "INTERVAL");
        if (intervalEnd >= 0) {
            String rest = amount.substring(intervalEnd).trim();
            int unitStart = rest.lastIndexOf(' ');
            unit = rest.substring(unitStart + 1).replace("`", "").toUpperCase(Locale.ROOT);
            amount = unitStart < 0 ? "" : rest.substring(0, unitStart).trim();
        }
        boolean isNative = intervalEnd >= 0 && INTEGER_LITERAL.matcher(amount).matches()
                && upperName.startsWith("DATE_");
        if (isNative || amount.isEmpty() || !INTERVAL_UNITS.contains(unit)) {
            return name + "(" + args + ")";
        }
        String sign = upperName.contains("SUB") ? "-" : "";
        return "TIMESTAMPADD(" + unit + ", " + sign + "CAST(" + amount + " AS INTEGER), " + parts.get(0) + ")";
    }

    /**
     * 单参形态改名交给 UDF（如 MySQL 的 TIME(x)），其余形态保持原样（Calcite 的 TIME(h, m, s)）。
     *
     * @param name   原函数名
     * @param args   参数文本
     * @param target UDF 名
     * @return 改写结果
     */
    private static String renameSingleArg(String name, String args, String target) {
        List<String> parts = SqlLexing.splitTopLevel(args);
        return parts.size() == 1 ? typedCall(target, parts) : name + "(" + args + ")";
    }

    private static String rewriteDateDiff(String args) {
        List<String> parts = SqlLexing.splitTopLevel(args);
        if (parts.size() != 2) {
            return "DATEDIFF(" + args + ")";
        }
        return typedCall("MYSQL_DATEDIFF", parts);
    }

    /**
     * FIELD(x, a, b, ...) → CASE：等值比较随后经 _ci 改写，与 MySQL 忽略大小写一致。
     *
     * @param args 参数文本
     * @return CASE 表达式
     */
    private static String rewriteField(String args) {
        List<String> parts = SqlLexing.splitTopLevel(args);
        if (parts.size() < 2) {
            return "FIELD(" + args + ")";
        }
        StringBuilder caseExpression = new StringBuilder("(CASE");
        for (int i = 1; i < parts.size(); i++) {
            caseExpression.append(" WHEN ").append(parts.get(0)).append(" = ").append(parts.get(i))
                    .append(" THEN ").append(i);
        }
        return caseExpression.append(" ELSE 0 END)").toString();
    }
}
