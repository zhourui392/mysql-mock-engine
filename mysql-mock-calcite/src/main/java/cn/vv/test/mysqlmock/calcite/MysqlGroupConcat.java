package cn.vv.test.mysqlmock.calcite;

import java.util.List;

/**
 * GROUP_CONCAT → LISTAGG。Calcite 自带的 GROUP_CONCAT 对 SEPARATOR / DISTINCT 形态静默返回 NULL，
 * LISTAGG 的 DISTINCT、分隔符与 WITHIN GROUP 排序都可用。多个表达式按 MySQL 语义先 CONCAT；默认分隔符为逗号。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class MysqlGroupConcat {

    private static final String DISTINCT = "DISTINCT";

    private static final String SEPARATOR = "SEPARATOR";

    private static final String ORDER_BY = "ORDER BY";

    private static final String DEFAULT_SEPARATOR = "','";

    private MysqlGroupConcat() {
    }

    static String toListAgg(String args) {
        String body = args.trim();
        int distinctEnd = SqlLexing.matchesKeywordAt(body, 0, DISTINCT);
        boolean isDistinct = distinctEnd >= 0;
        body = isDistinct ? body.substring(distinctEnd).trim() : body;

        String separator = DEFAULT_SEPARATOR;
        int separatorAt = SqlLexing.lastTopLevelKeyword(body, SEPARATOR);
        if (separatorAt >= 0) {
            separator = body.substring(separatorAt + SEPARATOR.length()).trim();
            body = body.substring(0, separatorAt).trim();
        }

        String orderBy = null;
        int orderAt = SqlLexing.lastTopLevelKeyword(body, ORDER_BY);
        if (orderAt >= 0) {
            orderBy = body.substring(SqlLexing.matchesKeywordAt(body, orderAt, ORDER_BY)).trim();
            body = body.substring(0, orderAt).trim();
        }

        List<String> expressions = SqlLexing.splitTopLevel(body);
        String value = expressions.size() == 1 ? expressions.get(0) : "CONCAT(" + String.join(", ", expressions) + ")";
        String listAgg = "LISTAGG(" + (isDistinct ? DISTINCT + " " : "") + value + ", " + separator + ")";
        return orderBy == null ? listAgg : listAgg + " WITHIN GROUP (ORDER BY " + orderBy + ")";
    }
}
