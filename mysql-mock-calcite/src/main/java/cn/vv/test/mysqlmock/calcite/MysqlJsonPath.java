package cn.vv.test.mysqlmock.calcite;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.calcite.runtime.JsonFunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.vv.test.mysqlmock.api.MysqlMockException;

/**
 * {@link MysqlJsonUdf} 的路径求值与 MySQL 风格序列化。文档是 Calcite {@code JsonFunctions.dejsonize}
 * 的结果（Map / List / 标量）。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class MysqlJsonPath {

    static final Comparator<String> MYSQL_KEY_ORDER = Comparator.comparingInt(String::length)
            .thenComparing(Comparator.naturalOrder());

    /**
     * MYSQL_JSON_EXACT 加在内层 JSON 函数结果前的标记。JSON 文本不会以控制字符开头；改写器只把 MYSQL_JSON_EXACT
     * 放在读文档的 UDF 参数位，标记不会出现在查询结果里。
     */
    static final String EXACT_DOCUMENT_MARKER = "\u0001";

    private static final ObjectMapper EXACT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private MysqlJsonPath() {
    }

    /**
     * 路径求值结果：区分「不存在」和「值为 JSON null」。
     *
     * @author zhourui
     * @since 2026-10-23
     */
    static final class Lookup {

        private static final Lookup ABSENT = new Lookup(false, null);

        private final boolean isPresent;

        private final Object value;

        private Lookup(boolean isPresent, Object value) {
            this.isPresent = isPresent;
            this.value = value;
        }

        boolean isPresent() {
            return isPresent;
        }

        Object value() {
            return value;
        }
    }

    /**
     * 单值路径求值；路径含通配符或范围时报错（MySQL 在 JSON_CONTAINS 等函数里同样拒绝）。
     *
     * @param document 文档
     * @param path     路径
     * @return 求值结果
     */
    static Lookup lookup(Object document, String path) {
        List<MysqlJsonPathStep> steps = parse(path);
        if (hasWildcard(steps)) {
            throw MysqlMockException.unsupported("此处 JSON 路径不能含 * 或 **: " + path);
        }
        List<Object> matches = evaluate(document, steps);
        return matches.isEmpty() ? Lookup.ABSENT : new Lookup(true, matches.get(0));
    }

    /**
     * 路径求值，支持多值步骤：{@code .*} 取对象全部成员，{@code [*]} 取数组全部元素，{@code [m to n]} 取一段元素，
     * {@code **} 取自身及全部后代。
     *
     * @param document 文档
     * @param path     路径
     * @return 全部命中值（文档顺序）
     */
    static List<Object> lookupAll(Object document, String path) {
        return evaluate(document, parse(path));
    }

    static boolean hasWildcard(String path) {
        return hasWildcard(parse(path));
    }

    private static boolean hasWildcard(List<MysqlJsonPathStep> steps) {
        return steps.stream().anyMatch(MysqlJsonPathStep::isMultiValued);
    }

    /**
     * 解析路径为步骤序列。
     *
     * @param path 路径，以 {@code $} 开头
     * @return 步骤序列（不含 {@code $}）
     */
    static List<MysqlJsonPathStep> parse(String path) {
        String trimmed = path.trim();
        if (!trimmed.startsWith("$")) {
            throw MysqlMockException.unsupported("JSON 路径必须以 $ 开头，收到 " + path);
        }
        List<MysqlJsonPathStep> steps = new ArrayList<>();
        int i = 1;
        while (i < trimmed.length()) {
            i = readStep(trimmed, i, steps);
        }
        return steps;
    }

    private static int readStep(String path, int start, List<MysqlJsonPathStep> steps) {
        if (path.startsWith("**", start)) {
            steps.add(MysqlJsonPathStep.of(MysqlJsonPathStep.Kind.DESCENDANT));
            return start + 2;
        }
        char c = path.charAt(start);
        if (c == '[') {
            int close = path.indexOf(']', start);
            if (close < 0) {
                throw MysqlMockException.unsupported("JSON 路径缺少 ]: " + path);
            }
            steps.add(MysqlJsonPathStep.arrayStep(path.substring(start + 1, close), path));
            return close + 1;
        }
        if (c != '.') {
            throw MysqlMockException.unsupported("无法解析 JSON 路径: " + path);
        }
        if (path.startsWith("*", start + 1)) {
            steps.add(MysqlJsonPathStep.of(MysqlJsonPathStep.Kind.ANY_MEMBER));
            return start + 2;
        }
        String[] keyAndNext = readKey(path, start + 1);
        steps.add(MysqlJsonPathStep.key(keyAndNext[0]));
        return Integer.parseInt(keyAndNext[1]);
    }

    private static String[] readKey(String path, int start) {
        if (start < path.length() && path.charAt(start) == '"') {
            int close = path.indexOf('"', start + 1);
            return new String[]{path.substring(start + 1, close), String.valueOf(close + 1)};
        }
        int end = start;
        while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '['
                && !path.startsWith("**", end)) {
            end++;
        }
        return new String[]{path.substring(start, end), String.valueOf(end)};
    }

    static List<Object> evaluate(Object document, List<MysqlJsonPathStep> steps) {
        List<Object> current = new ArrayList<>();
        current.add(document);
        for (MysqlJsonPathStep step : steps) {
            List<Object> next = new ArrayList<>();
            for (Object node : current) {
                step.apply(node, next);
            }
            current = next;
        }
        return current;
    }

    /**
     * JSON 列写入值规范化：MySQL 按二进制格式存储 JSON，读出时是规范格式（键排序、逗号冒号后带空格）。
     * 数据集里直接给出的 Map / List 按 JSON 值处理。
     *
     * @param value  写入值（JSON 文本，或数据集里的 Map / List）
     * @param column 列名（用于报错）
     * @return 规范格式的 JSON 文本
     */
    static String normalizeColumnValue(Object value, String column) {
        if (value instanceof Map || value instanceof List) {
            return format(value);
        }
        try {
            return format(JsonFunctions.dejsonize(String.valueOf(value)));
        } catch (RuntimeException e) {
            throw MysqlMockException.invalidJson(column, e);
        }
    }

    /**
     * 读 JSON 函数的文档参数。带 {@link #EXACT_DOCUMENT_MARKER} 的文本来自内层 JSON 函数，按精确数解析：MySQL 在
     * 函数之间传二进制 JSON，DECIMAL 值保留小数位；其余文本按 MySQL 解析 JSON 文本的规则，小数为 double。
     *
     * @param json JSON 文本
     * @return Map / List / 标量
     */
    static Object readDocument(String json) {
        return json.startsWith(EXACT_DOCUMENT_MARKER) ? parseExact(json) : JsonFunctions.dejsonize(json);
    }

    /**
     * 解析 SQL 表达式产出的 JSON 文本：小数按 BigDecimal 保留小数位（MySQL 里 DECIMAL 值写进 JSON 后仍是 DECIMAL）。
     * 读用户给的 JSON 文本用 {@code JsonFunctions.dejsonize}，小数按 double，与 MySQL 解析 JSON 文本一致。
     *
     * @param json JSON 文本
     * @return Map / List / 标量
     */
    static Object parseExact(String json) {
        if (json.startsWith(EXACT_DOCUMENT_MARKER)) {
            json = json.substring(EXACT_DOCUMENT_MARKER.length());
        }
        try {
            return EXACT_MAPPER.readValue(json, Object.class);
        } catch (IOException e) {
            throw MysqlMockException.parseError("JSON 文本无法解析: " + json, e);
        }
    }

    @SuppressWarnings("unchecked")
    static void searchStrings(Object node, String path, String pattern, List<String> found) {
        if (node instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) node).entrySet()) {
                String key = entry.getKey();
                String child = PLAIN_KEY.matcher(key).matches() ? path + "." + key : path + ".\"" + key + "\"";
                searchStrings(entry.getValue(), child, pattern, found);
            }
        } else if (node instanceof List) {
            List<Object> elements = (List<Object>) node;
            for (int i = 0; i < elements.size(); i++) {
                searchStrings(elements.get(i), path + "[" + i + "]", pattern, found);
            }
        } else if (node instanceof String && likeIgnoreCase((String) node, pattern)) {
            found.add(path);
        }
    }

    private static boolean likeIgnoreCase(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                .matcher(value).matches();
    }

    static boolean scalarEquals(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return left == null ? right == null : left.equals(right);
    }

    /**
     * MySQL 的 JSON 文本格式：逗号、冒号后带一个空格；对象的键按 MySQL 存储顺序排列（先按长度，再按字节序）。
     *
     * @param value Map / List / 标量
     * @return JSON 文本
     */
    @SuppressWarnings("unchecked")
    static String format(Object value) {
        if (value instanceof Map) {
            Map<String, Object> members = (Map<String, Object>) value;
            List<String> keys = new ArrayList<>(members.keySet());
            keys.sort(MYSQL_KEY_ORDER);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                out.append(out.length() > 1 ? ", " : "").append(quote(key)).append(": ").append(format(members.get(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object element : (List<Object>) value) {
                out.append(out.length() > 1 ? ", " : "").append(format(element));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        return value == null ? "null" : value.toString().toLowerCase(Locale.ROOT);
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
