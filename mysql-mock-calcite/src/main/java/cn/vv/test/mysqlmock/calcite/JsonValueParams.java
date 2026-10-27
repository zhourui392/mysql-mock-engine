package cn.vv.test.mysqlmock.calcite;

/**
 * JSON 修改函数里作为值的绑定参数：改写器把它写成 {@code CAST(? <标记注释> AS VARCHAR)}（标记见 {@link #MARKER}），执行前按参数的 Java 类型
 * 编码成 JSON 文本（数字、布尔保持原类型，字符串加引号）。Calcite 推不出裸参数的类型，只能先按 VARCHAR 绑定，
 * 不编码时 Long、BigDecimal 参数会被写成 JSON 字符串，与 MySQL 不一致。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class JsonValueParams {

    static final String MARKER = "/*json-value*/";

    private JsonValueParams() {
    }

    /**
     * 按 SQL 中带标记的参数位置编码参数值。
     *
     * @param sql    改写后的 SQL
     * @param params 绑定参数
     * @return 编码后的参数；没有带标记的参数时返回原数组
     */
    static Object[] encode(String sql, Object[] params) {
        if (params == null || !sql.contains(MARKER)) {
            return params;
        }
        Object[] encoded = params.clone();
        int paramIndex = 0;
        int i = 0;
        while (i < sql.length()) {
            int end = SqlLexing.skipNonCode(sql, i);
            if (end > i) {
                i = end;
                continue;
            }
            if (sql.charAt(i) == '?') {
                boolean isJsonValue = sql.startsWith(MARKER, SqlLexing.skipWhitespace(sql, i + 1));
                if (isJsonValue && paramIndex < encoded.length) {
                    encoded[paramIndex] = MysqlJsonWriteUdf.mysqlToJson(encoded[paramIndex]);
                }
                paramIndex++;
            }
            i++;
        }
        return encoded;
    }
}
