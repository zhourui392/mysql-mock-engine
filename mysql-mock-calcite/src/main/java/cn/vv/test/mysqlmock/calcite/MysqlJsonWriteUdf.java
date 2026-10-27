package cn.vv.test.mysqlmock.calcite;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.calcite.runtime.JsonFunctions;

import cn.vv.test.mysqlmock.api.MysqlMockException;

/**
 * MySQL 构造与修改 JSON 的函数：JSON_SET / JSON_INSERT / JSON_REPLACE、JSON_REMOVE、JSON_KEYS、JSON_QUOTE、
 * JSON_OVERLAPS，以及给 Calcite 自带 JSON_OBJECT / JSON_ARRAY / JSON_ARRAYAGG 输出改格式的 MYSQL_JSON_FORMAT。
 * <p>Calcite 自带的 JSON_SET 系列把数字和 JSON 值都写成字符串，所以改由这里实现。值参数由
 * {@link MysqlJsonRewrites} 先转成 JSON 文本：普通值经 {@link #mysqlToJson} 编码，JSON 表达式原样传入。
 *
 * @author zhourui
 * @since 2026-10-23
 */
public final class MysqlJsonWriteUdf {

    private static final String MODE_SET = "SET";

    private static final String MODE_INSERT = "INSERT";

    private static final String MODE_REPLACE = "REPLACE";

    private MysqlJsonWriteUdf() {
    }

    /**
     * JSON_SET / JSON_INSERT / JSON_REPLACE：按路径从左到右依次修改，与 MySQL 一致。
     * <p>文档按 {@link MysqlJsonPath#readDocument} 读；值来自 SQL 表达式，按精确数解析，保留 DECIMAL 小数位。
     *
     * @param mode  SET / INSERT / REPLACE
     * @param json  JSON 文档
     * @param pairs 路径与值交替排列的 JSON 数组 {@code [path1, value1, ...]}
     * @return 修改后的文档；文档或任一路径为 NULL 时返回 NULL
     */
    @SuppressWarnings("unchecked")
    public static String mysqlJsonModify(String mode, String json, String pairs) {
        if (json == null || mode == null || pairs == null) {
            return null;
        }
        Object document = MysqlJsonPath.readDocument(json);
        List<Object> pathValuePairs = (List<Object>) MysqlJsonPath.parseExact(pairs);
        for (int i = 0; i + 1 < pathValuePairs.size(); i += 2) {
            Object path = pathValuePairs.get(i);
            if (path == null) {
                return null;
            }
            document = modifySteps(document, mode, singleValuePath(path.toString()), pathValuePairs.get(i + 1));
        }
        return MysqlJsonPath.format(document);
    }

    /**
     * 单个路径的 JSON_REMOVE；多个路径由改写器嵌套调用。
     *
     * @param json JSON 文档
     * @param path 路径
     * @return 删除后的文档；文档或路径为 NULL 时返回 NULL
     */
    @SuppressWarnings("unchecked")
    public static String mysqlJsonRemove(String json, String path) {
        if (json == null || path == null) {
            return null;
        }
        Object document = MysqlJsonPath.readDocument(json);
        List<MysqlJsonPathStep> steps = singleValuePath(path);
        if (steps.isEmpty()) {
            throw MysqlMockException.unsupported("JSON_REMOVE 不能删除 $ 本身");
        }
        List<Object> parents = MysqlJsonPath.evaluate(document, steps.subList(0, steps.size() - 1));
        Object parent = parents.isEmpty() ? null : parents.get(0);
        MysqlJsonPathStep last = steps.get(steps.size() - 1);
        if (parent instanceof Map && last.kind() == MysqlJsonPathStep.Kind.KEY) {
            ((Map<String, Object>) parent).remove(last.key());
        } else if (parent instanceof List && last.kind() == MysqlJsonPathStep.Kind.INDEX
                && isWithin(last.index(((List<Object>) parent).size()), (List<Object>) parent)) {
            ((List<Object>) parent).remove(last.index(((List<Object>) parent).size()));
        }
        return MysqlJsonPath.format(document);
    }

    /**
     * 标记内层 JSON 函数的结果，外层按精确数读文档，见 {@link MysqlJsonPath#readDocument}。
     *
     * @param json 内层 JSON 函数的结果
     * @return 带标记的文本；NULL 时返回 NULL
     */
    public static String mysqlJsonExact(String json) {
        return json == null ? null : MysqlJsonPath.EXACT_DOCUMENT_MARKER + json;
    }

    /**
     * 普通 SQL 值编码为 JSON 文本：字符串加引号，数字与布尔原样，NULL 为 JSON null。
     *
     * @param value SQL 值
     * @return JSON 文本
     */
    public static String mysqlToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return MysqlJsonPath.quote((String) value);
        }
        return value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString();
    }

    /**
     * 把 JSON 文本改为 MySQL 输出格式。
     *
     * @param json JSON 文本
     * @return MySQL 格式的 JSON 文本
     */
    public static String mysqlJsonFormat(String json) {
        return json == null ? null : MysqlJsonPath.format(MysqlJsonPath.parseExact(json));
    }

    /**
     * MySQL JSON_QUOTE：字符串转为 JSON 字符串字面量。
     *
     * @param text 字符串
     * @return 带引号、已转义的文本
     */
    public static String jsonQuote(String text) {
        return text == null ? null : MysqlJsonPath.quote(text);
    }

    /**
     * MySQL JSON_KEYS(json)。
     *
     * @param json JSON 文档
     * @return 顶层键数组；不是对象时返回 NULL
     */
    public static String mysqlJsonKeys(String json) {
        return mysqlJsonKeys(json, "$");
    }

    /**
     * MySQL JSON_KEYS(json, path)：键按 MySQL 存储顺序排列。
     *
     * @param json JSON 文档
     * @param path 路径
     * @return 键数组；路径不存在或不是对象时返回 NULL
     */
    @SuppressWarnings("unchecked")
    public static String mysqlJsonKeys(String json, String path) {
        if (json == null || path == null) {
            return null;
        }
        MysqlJsonPath.Lookup found = MysqlJsonPath.lookup(JsonFunctions.dejsonize(json), path);
        if (!found.isPresent() || !(found.value() instanceof Map)) {
            return null;
        }
        List<String> keys = new ArrayList<>(((Map<String, Object>) found.value()).keySet());
        keys.sort(MysqlJsonPath.MYSQL_KEY_ORDER);
        return MysqlJsonPath.format(keys);
    }

    /**
     * MySQL JSON_OVERLAPS：两个对象有相同的键值对，或两侧（数组按元素、标量按自身）有相同的值。
     *
     * @param left  JSON 文档
     * @param right JSON 文档
     * @return 1 / 0；任一侧 NULL 返回 NULL
     */
    @SuppressWarnings("unchecked")
    public static Long jsonOverlaps(String left, String right) {
        if (left == null || right == null) {
            return null;
        }
        Object leftValue = JsonFunctions.dejsonize(left);
        Object rightValue = JsonFunctions.dejsonize(right);
        if (leftValue instanceof Map && rightValue instanceof Map) {
            Map<String, Object> rightMembers = (Map<String, Object>) rightValue;
            boolean hasSharedMember = ((Map<String, Object>) leftValue).entrySet().stream().anyMatch(entry ->
                    rightMembers.containsKey(entry.getKey()) && jsonEquals(entry.getValue(),
                            rightMembers.get(entry.getKey())));
            return hasSharedMember ? 1L : 0L;
        }
        List<Object> rightElements = elements(rightValue);
        boolean hasSharedElement = elements(leftValue).stream()
                .anyMatch(item -> rightElements.stream().anyMatch(other -> jsonEquals(item, other)));
        return hasSharedElement ? 1L : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Object modifySteps(Object document, String mode, List<MysqlJsonPathStep> steps, Object value) {
        if (steps.isEmpty()) {
            return MODE_INSERT.equals(mode) ? document : value;
        }
        List<Object> parents = MysqlJsonPath.evaluate(document, steps.subList(0, steps.size() - 1));
        if (parents.isEmpty()) {
            return document;
        }
        Object parent = parents.get(0);
        MysqlJsonPathStep last = steps.get(steps.size() - 1);
        if (last.kind() == MysqlJsonPathStep.Kind.KEY && parent instanceof Map) {
            setMember((Map<String, Object>) parent, last.key(), mode, value);
        } else if (last.kind() == MysqlJsonPathStep.Kind.INDEX && parent instanceof List) {
            setElement((List<Object>) parent, last.index(((List<Object>) parent).size()), mode, value);
        } else if (last.kind() == MysqlJsonPathStep.Kind.INDEX) {
            return writeIntoNonArray(document, mode, steps, parent, value);
        }
        return document;
    }

    /**
     * 非数组值上的下标写入（MySQL 把该值视为单元素数组）：{@code [0]} 指该值本身，SET / REPLACE 替换它；
     * 下标越界时 SET / INSERT 把它包成数组再追加新值。
     *
     * @param document 文档
     * @param mode     SET / INSERT / REPLACE
     * @param steps    完整路径
     * @param current  路径去掉末级下标后指向的值
     * @param value    新值
     * @return 修改后的文档
     */
    private static Object writeIntoNonArray(Object document, String mode, List<MysqlJsonPathStep> steps,
                                            Object current, Object value) {
        List<MysqlJsonPathStep> currentPath = steps.subList(0, steps.size() - 1);
        int index = steps.get(steps.size() - 1).index(1);
        if (index == 0 && !MODE_INSERT.equals(mode)) {
            return modifySteps(document, MODE_SET, currentPath, value);
        }
        if (index > 0 && !MODE_REPLACE.equals(mode)) {
            List<Object> wrapped = new ArrayList<>(Arrays.asList(current, value));
            return modifySteps(document, MODE_SET, currentPath, wrapped);
        }
        return document;
    }

    private static void setMember(Map<String, Object> parent, String key, String mode, Object value) {
        boolean shouldWrite = parent.containsKey(key) ? !MODE_INSERT.equals(mode) : !MODE_REPLACE.equals(mode);
        if (shouldWrite) {
            parent.put(key, value);
        }
    }

    /**
     * 写数组元素：下标越界时 SET / INSERT 追加到末尾（MySQL 行为），REPLACE 不变。
     *
     * @param parent 数组
     * @param index  下标
     * @param mode   SET / INSERT / REPLACE
     * @param value  值
     */
    private static void setElement(List<Object> parent, int index, String mode, Object value) {
        if (isWithin(index, parent) && !MODE_INSERT.equals(mode)) {
            parent.set(index, value);
        } else if (index >= parent.size() && !MODE_REPLACE.equals(mode)) {
            parent.add(value);
        }
    }

    private static List<MysqlJsonPathStep> singleValuePath(String path) {
        if (MysqlJsonPath.hasWildcard(path)) {
            throw MysqlMockException.unsupported("修改 JSON 的路径不能含 *、** 或范围: " + path);
        }
        return MysqlJsonPath.parse(path);
    }

    private static boolean isWithin(int index, List<Object> elements) {
        return index >= 0 && index < elements.size();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> elements(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.singletonList(value);
    }

    private static boolean jsonEquals(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return MysqlJsonPath.scalarEquals(left, right);
        }
        boolean isContainer = left instanceof Map || left instanceof List;
        return isContainer ? MysqlJsonPath.format(left).equals(MysqlJsonPath.format(right)) : Objects.equals(left, right);
    }
}
