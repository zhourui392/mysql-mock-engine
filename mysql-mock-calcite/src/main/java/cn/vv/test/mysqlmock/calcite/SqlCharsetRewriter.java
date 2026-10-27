package cn.vv.test.mysqlmock.calcite;

/**
 * SQL 预处理：为单引号字符串字面量加 _UTF8 字符集 introducer。
 * <p>Calcite 类型工厂默认字面量字符集 ISO-8859-1，与列类型 UTF-8 相异时会生成
 * CAST(... AS VARCHAR CHARACTER SET ISO-8859-1) 并在运行期编码失败（中文即触发）；
 * introducer 使字面量在解析层即为 UTF-8。幂等；跳过注释 / 反引号 / 双引号与 N'' 字面量，
 * 以及 {@code DATE '...'}、{@code TIMESTAMP '...'}、{@code INTERVAL '1' DAY} 这类类型字面量（加了 introducer 解析失败）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class SqlCharsetRewriter {

    private static final String INTRODUCER = "_UTF8";

    private static final java.util.Set<String> TYPED_LITERAL_KEYWORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("date", "time", "timestamp", "interval"));

    private SqlCharsetRewriter() {
    }

    static String introduceUtf8(String sql) {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = endOfLine(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '#') {
                int end = endOfLine(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                out.append(sql, i, end);
                i = end;
            } else if (c == '\'') {
                int end = skipQuoted(sql, i, c);
                if (!endsWithIntroducerOrNational(out) && !endsWithTypedLiteralKeyword(out)) {
                    out.append(INTRODUCER);
                }
                out.append(sql, i, end);
                i = end;
            } else if (c == '`' || c == '"') {
                int end = skipQuoted(sql, i, c);
                out.append(sql, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int endOfLine(String sql, int start) {
        int end = sql.indexOf('\n', start);
        return end < 0 ? sql.length() : end + 1;
    }

    /** 跳过引号串（含成对转义 ''），返回其后下标。 */
    private static int skipQuoted(String sql, int start, char quote) {
        int j = start + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return j;
    }

    private static boolean endsWithIntroducerOrNational(StringBuilder out) {
        int length = out.length();
        if (length == 0) {
            return false;
        }
        char last = out.charAt(length - 1);
        if (last == 'N' || last == 'n') {
            return true;
        }
        return length >= INTRODUCER.length()
                && out.substring(length - INTRODUCER.length()).equalsIgnoreCase(INTRODUCER);
    }

    private static boolean endsWithTypedLiteralKeyword(StringBuilder out) {
        int end = out.length();
        while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isLetter(out.charAt(start - 1))) {
            start--;
        }
        boolean isStandaloneWord = start == 0 || !Character.isLetterOrDigit(out.charAt(start - 1))
                && out.charAt(start - 1) != '_' && out.charAt(start - 1) != '`';
        return isStandaloneWord && TYPED_LITERAL_KEYWORDS.contains(
                out.substring(start, end).toLowerCase(java.util.Locale.ROOT));
    }
}
