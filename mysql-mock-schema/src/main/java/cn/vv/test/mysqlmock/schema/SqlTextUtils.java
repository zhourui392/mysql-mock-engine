package cn.vv.test.mysqlmock.schema;

/** SQL 文本扫描工具：字符串 / 注释感知的括号匹配与顶层逗号切分。 */
final class SqlTextUtils {

    private SqlTextUtils() {
    }

    /** 从 openIndex（指向 '('）起找匹配的 ')' 下标；跳过字符串与注释。 */
    static int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        int i = openIndex;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isQuoteStart(text, i)) {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** 顶层（深度 0、字符串外）逗号下标列表。 */
    static java.util.List<Integer> topLevelCommas(String text) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isQuoteStart(text, i)) {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(i);
            }
            i++;
        }
        return result;
    }

    /** 去掉字符串外的一段注释后返回新的扫描位置；无注释返回原位置。 */
    static int skipCommentIfPresent(String text, int i) {
        if (i + 1 < text.length() && text.charAt(i) == '-' && text.charAt(i + 1) == '-') {
            int end = text.indexOf('\n', i);
            return end < 0 ? text.length() : end + 1;
        }
        if (i + 1 < text.length() && text.charAt(i) == '#') {
            int end = text.indexOf('\n', i);
            return end < 0 ? text.length() : end + 1;
        }
        if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
            int end = text.indexOf("*/", i + 2);
            return end < 0 ? text.length() : end + 2;
        }
        return i;
    }

    static boolean isQuoteStart(String text, int i) {
        char c = text.charAt(i);
        return c == '\'' || c == '"' || c == '`';
    }

    /** 跳过引号串（含 '' 转义与反引号 `` 转义），返回其后下标。 */
    static int skipQuoted(String text, int i) {
        char quote = text.charAt(i);
        int j = i + 1;
        while (j < text.length()) {
            if (text.charAt(j) == quote) {
                if (j + 1 < text.length() && text.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return j;
    }

    /** 解析 MySQL 双单引号转义。 */
    static String unescape(String quoted) {
        return quoted.replace("''", "'");
    }
}
