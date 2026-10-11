package cn.vv.test.mysqlmock.schema;

import java.util.ArrayList;
import java.util.List;

/** 按分号切分 SQL 脚本；字符串与注释感知。 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            int afterComment = SqlTextUtils.skipCommentIfPresent(script, i);
            if (afterComment != i) {
                if (script.charAt(i) == '/' && afterComment < n) {
                    current.append(' ');
                }
                i = afterComment;
                continue;
            }
            if (SqlTextUtils.isQuoteStart(script, i)) {
                int end = SqlTextUtils.skipQuoted(script, i);
                current.append(script, i, end);
                i = end;
                continue;
            }
            if (c == ';') {
                addIfNotEmpty(statements, current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addIfNotEmpty(statements, current.toString());
        return statements;
    }

    private static void addIfNotEmpty(List<String> statements, String statement) {
        String trimmed = statement.trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }
}
