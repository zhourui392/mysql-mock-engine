package cn.vv.test.mysqlmock.calcite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@link MysqlDialectRewriter} 的 JSON 函数改写。
 * <ul>
 *   <li>JSON_OBJECT / JSON_ARRAY / JSON_ARRAYAGG / JSON_OBJECTAGG 仍用 Calcite 实现，外层套 MYSQL_JSON_FORMAT
 *   改成 MySQL 输出格式；嵌套在同类调用里时去掉这层包装，否则 Calcite 会把内层结果当字符串。</li>
 *   <li>JSON_SET / JSON_INSERT / JSON_REPLACE 改为 {@link MysqlJsonWriteUdf} 的调用，路径与值打包成 JSON 数组；
 *   JSON_REMOVE 多路径嵌套展开。值参数先转成 JSON 文本，JSON 表达式原样传入，保持 MySQL 的值类型。</li>
 * </ul>
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class MysqlJsonRewrites {

    private static final String FORMAT_CALL = "MYSQL_JSON_FORMAT(";

    private static final String BARE_PARAM = "?";

    private static final String TYPED_PARAM = "CAST(? AS VARCHAR)";

    /** 结果是 JSON 文本的表达式：作 JSON_SET 的值时按 JSON 写入，不当字符串。 */
    private static final List<String> JSON_EXPRESSION_PREFIXES = Arrays.asList(FORMAT_CALL, "JSON_EXTRACT(",
            "MYSQL_JSON_MODIFY(", "MYSQL_JSON_REMOVE(", "MYSQL_JSON_KEYS(");

    private MysqlJsonRewrites() {
    }

    /**
     * JSON 构造函数套上 MySQL 格式化。
     *
     * @param name 函数名
     * @param args 已改写的参数文本
     * @return 改写结果
     */
    static String formatBuilder(String name, String args) {
        List<String> parts = SqlLexing.splitTopLevel(args).stream().map(MysqlJsonRewrites::unwrapFormat)
                .collect(Collectors.toList());
        return FORMAT_CALL + name + "(" + String.join(", ", parts) + "))";
    }

    /**
     * JSON_SET / JSON_INSERT / JSON_REPLACE(doc, path, value, ...) → MYSQL_JSON_MODIFY(mode, doc, pairs)。
     * 路径与值拼成一个 JSON 数组 {@code [path1, value1, ...]} 一次传入：UDF 参数个数固定，且中间结果不经文本
     * 往返，DECIMAL 值的小数位不丢。
     *
     * @param name 函数名
     * @param args 已改写的参数文本
     * @return 改写结果；参数个数不对时原样返回
     */
    static String modify(String name, String args) {
        List<String> parts = SqlLexing.splitTopLevel(args);
        boolean hasPathValuePairs = parts.size() > 1 && parts.size() % 2 == 1;
        if (!hasPathValuePairs) {
            return name + "(" + args + ")";
        }
        String mode = name.toUpperCase(Locale.ROOT).substring("JSON_".length());
        StringBuilder pairs = new StringBuilder("CONCAT('['");
        for (int i = 1; i < parts.size(); i += 2) {
            pairs.append(i > 1 ? ", ', '" : "").append(", MYSQL_TO_JSON(").append(typedArg(parts.get(i)))
                    .append("), ', ', ").append(jsonValue(parts.get(i + 1)));
        }
        pairs.append(", ']')");
        return "MYSQL_JSON_MODIFY('" + mode + "', " + document(parts.get(0)) + ", " + pairs + ")";
    }

    /**
     * JSON_REMOVE(doc, path, ...) → 嵌套的 MYSQL_JSON_REMOVE。
     *
     * @param name 函数名
     * @param args 已改写的参数文本
     * @return 改写结果
     */
    static String remove(String name, String args) {
        List<String> parts = SqlLexing.splitTopLevel(args);
        if (parts.size() < 2) {
            return name + "(" + args + ")";
        }
        String document = parts.get(0);
        for (String path : parts.subList(1, parts.size())) {
            document = "MYSQL_JSON_REMOVE(" + document(document) + ", " + typedArg(path) + ")";
        }
        return document;
    }

    /**
     * JSON_EXTRACT(doc, path, ...)：内层是 JSON 函数时按精确数读文档。
     *
     * @param name 函数名
     * @param args 已改写的参数文本
     * @return 改写结果
     */
    static String extract(String name, String args) {
        List<String> parts = new ArrayList<>(SqlLexing.splitTopLevel(args));
        if (!parts.isEmpty()) {
            parts.set(0, document(parts.get(0)));
        }
        return name + "(" + parts.stream().map(MysqlJsonRewrites::typedArg).collect(Collectors.joining(", ")) + ")";
    }

    /**
     * 读文档的参数位：内层 JSON 函数的结果套 MYSQL_JSON_EXACT，外层保留其中 DECIMAL 的小数位
     * （MySQL 在函数之间传二进制 JSON）；列值、字面量与绑定参数仍按 JSON 文本读。
     *
     * @param document 文档参数
     * @return 改写后的参数
     */
    private static String document(String document) {
        return isJsonExpression(document) ? "MYSQL_JSON_EXACT(" + document + ")" : typedArg(document);
    }

    private static boolean isJsonExpression(String expression) {
        String upper = expression.toUpperCase(Locale.ROOT);
        return JSON_EXPRESSION_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    private static String unwrapFormat(String part) {
        if (!part.startsWith(FORMAT_CALL)) {
            return part;
        }
        int close = SqlLexing.closingParen(part, FORMAT_CALL.length() - 1);
        return part.substring(FORMAT_CALL.length(), close) + part.substring(close + 1);
    }

    private static String typedArg(String arg) {
        return BARE_PARAM.equals(arg) ? TYPED_PARAM : arg;
    }

    /**
     * 值参数转成非 NULL 的 JSON 文本：JSON 表达式原样（NULL 记为 JSON null），绑定参数由
     * {@link JsonValueParams} 在执行前编码，其余经 MYSQL_TO_JSON 编码。
     *
     * @param value 值参数
     * @return JSON 文本表达式
     */
    private static String jsonValue(String value) {
        if (BARE_PARAM.equals(value)) {
            return "CAST(? " + JsonValueParams.MARKER + " AS VARCHAR)";
        }
        return isJsonExpression(value) ? "COALESCE(" + value + ", 'null')" : "MYSQL_TO_JSON(" + value + ")";
    }
}
