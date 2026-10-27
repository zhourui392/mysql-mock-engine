package cn.vv.test.mysqlmock.calcite;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 文本预处理共用的词法工具：跳过注释与引号串、找配对括号、按顶层逗号切参数、找顶层关键字。
 * 「顶层」指不在括号、引号、注释内。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class SqlLexing {

    private SqlLexing() {
    }

    /**
     * 注释与引号串（含反引号标识符）的结束下标。
     *
     * @param sql   原始 SQL
     * @param start 当前下标
     * @return 片段结束下标；不是这类片段时返回 start
     */
    static int skipNonCode(String sql, int start) {
        char c = sql.charAt(start);
        if (c == '\'' || c == '"' || c == '`') {
            return skipQuoted(sql, start, c);
        }
        if (c == '#' || sql.startsWith("--", start)) {
            int lineEnd = sql.indexOf('\n', start);
            return lineEnd < 0 ? sql.length() : lineEnd + 1;
        }
        if (sql.startsWith("/*", start)) {
            int close = sql.indexOf("*/", start + 2);
            return close < 0 ? sql.length() : close + 2;
        }
        return start;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int j = start + 1;
        while (j < sql.length()) {
            char c = sql.charAt(j);
            if (c == '\\' && quote != '`') {
                j += 2;
            } else if (c != quote) {
                j++;
            } else if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                j += 2;
            } else {
                return j + 1;
            }
        }
        return j;
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    static int endOfWord(String sql, int start) {
        int j = start;
        while (j < sql.length() && isWordChar(sql.charAt(j))) {
            j++;
        }
        return j;
    }

    static int skipWhitespace(String sql, int from) {
        int j = from;
        while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) {
            j++;
        }
        return j;
    }

    /**
     * 与 {@code open} 处左括号配对的右括号下标。
     *
     * @param sql  SQL 文本
     * @param open 左括号下标
     * @return 右括号下标；不配对时返回 -1
     */
    static int closingParen(String sql, int open) {
        int depth = 0;
        int i = open;
        while (i < sql.length()) {
            int end = skipNonCode(sql, i);
            if (end > i) {
                i = end;
                continue;
            }
            char c = sql.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * 按顶层逗号切分参数，保留各段原文（去首尾空白）。
     *
     * @param args 括号内的参数文本
     * @return 参数列表；空文本返回空列表
     */
    static List<String> splitTopLevel(String args) {
        List<String> parts = new ArrayList<>();
        if (args.trim().isEmpty()) {
            return parts;
        }
        int depth = 0;
        int segmentStart = 0;
        int i = 0;
        while (i < args.length()) {
            int end = skipNonCode(args, i);
            if (end > i) {
                i = end;
                continue;
            }
            char c = args.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (c == ',' && depth == 0) {
                parts.add(args.substring(segmentStart, i).trim());
                segmentStart = i + 1;
            }
            i++;
        }
        parts.add(args.substring(segmentStart).trim());
        return parts;
    }

    /**
     * 顶层关键字（整词、大小写不敏感）最后一次出现的下标。
     *
     * @param text    文本
     * @param keyword 关键字，可含空格（如 {@code ORDER BY}，匹配时空白按任意空白处理）
     * @return 下标；未出现返回 -1
     */
    static int lastTopLevelKeyword(String text, String keyword) {
        int found = -1;
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            int end = skipNonCode(text, i);
            if (end > i) {
                i = end;
                continue;
            }
            char c = text.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (depth == 0 && matchesKeywordAt(text, i, keyword) >= 0) {
                found = i;
            }
            i++;
        }
        return found;
    }

    /**
     * {@code pos} 处是否为整词关键字。
     *
     * @param text    文本
     * @param pos     起点
     * @param keyword 关键字，词间空格匹配任意空白
     * @return 匹配时返回关键字结束下标，否则 -1
     */
    static int matchesKeywordAt(String text, int pos, String keyword) {
        if (pos > 0 && isWordChar(text.charAt(pos - 1))) {
            return -1;
        }
        String[] words = keyword.split(" ");
        int j = pos;
        for (int w = 0; w < words.length; w++) {
            int wordStart = w == 0 ? j : skipWhitespace(text, j);
            boolean isSeparated = w == 0 || wordStart > j;
            if (!isSeparated || !text.regionMatches(true, wordStart, words[w], 0, words[w].length())) {
                return -1;
            }
            j = wordStart + words[w].length();
        }
        return j < text.length() && isWordChar(text.charAt(j)) ? -1 : j;
    }
}
