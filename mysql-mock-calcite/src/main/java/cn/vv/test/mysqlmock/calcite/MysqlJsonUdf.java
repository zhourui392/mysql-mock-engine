package cn.vv.test.mysqlmock.calcite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.calcite.runtime.JsonFunctions;

/**
 * MySQL JSON 函数（Calcite 未提供的部分）：JSON_EXTRACT、JSON_UNQUOTE、JSON_CONTAINS、JSON_CONTAINS_PATH、
 * JSON_SEARCH。路径支持 {@code $}、{@code .key}、{@code ."key"}、{@code [n]}；JSON_EXTRACT 与 JSON_CONTAINS_PATH
 * 另支持通配符 {@code .*}、{@code [*]}、{@code **}。
 * 输出格式对齐 MySQL（{@code [1, 2]}、{@code {"k": 1}}）。真假结果按 MySQL 返回 1 / 0。
 *
 * @author zhourui
 * @since 2026-10-23
 */
public final class MysqlJsonUdf {

    private MysqlJsonUdf() {
    }

    /**
     * MySQL JSON_EXTRACT(json, path)：返回 JSON 文本；路径不存在返回 NULL。路径含通配符时，全部命中值包成数组返回。
     *
     * @param json JSON 文档
     * @param path 路径
     * @return JSON 文本
     */
    public static String jsonExtract(String json, String path) {
        if (json == null || path == null) {
            return null;
        }
        List<Object> matches = MysqlJsonPath.lookupAll(MysqlJsonPath.readDocument(json), path);
        if (matches.isEmpty()) {
            return null;
        }
        return MysqlJsonPath.format(MysqlJsonPath.hasWildcard(path) ? matches : matches.get(0));
    }

    /**
     * MySQL JSON_UNQUOTE：去掉 JSON 字符串的引号并反转义；其他文本原样返回。
     *
     * @param text JSON 文本
     * @return 去引号结果
     */
    public static String jsonUnquote(String text) {
        boolean isQuoted = text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"");
        return isQuoted ? (String) JsonFunctions.dejsonize(text) : text;
    }

    /**
     * MySQL JSON_CONTAINS(target, candidate)。
     *
     * @param target    目标文档
     * @param candidate 候选文档
     * @return 1 / 0；任一侧 NULL 返回 NULL
     */
    public static Long jsonContains(String target, String candidate) {
        return jsonContains(target, candidate, "$");
    }

    /**
     * MySQL JSON_CONTAINS(target, candidate, path)：数组包含候选的每个元素、对象包含候选的每个键值、标量相等。
     *
     * @param target    目标文档
     * @param candidate 候选文档
     * @param path      目标内的路径
     * @return 1 / 0；任一侧 NULL 或路径不存在返回 NULL
     */
    public static Long jsonContains(String target, String candidate, String path) {
        if (target == null || candidate == null || path == null) {
            return null;
        }
        MysqlJsonPath.Lookup found = MysqlJsonPath.lookup(JsonFunctions.dejsonize(target), path);
        if (!found.isPresent()) {
            return null;
        }
        return toFlag(contains(found.value(), JsonFunctions.dejsonize(candidate)));
    }

    /**
     * MySQL JSON_CONTAINS_PATH(json, 'one' | 'all', path)。
     *
     * @param json  JSON 文档
     * @param mode  one / all
     * @param path1 路径
     * @return 1 / 0
     */
    public static Long jsonContainsPath(String json, String mode, String path1) {
        return containsPaths(json, mode, path1);
    }

    /**
     * 两路径形态。
     *
     * @param json  JSON 文档
     * @param mode  one / all
     * @param path1 路径 1
     * @param path2 路径 2
     * @return 1 / 0
     */
    public static Long jsonContainsPath(String json, String mode, String path1, String path2) {
        return containsPaths(json, mode, path1, path2);
    }

    /**
     * 三路径形态。
     *
     * @param json  JSON 文档
     * @param mode  one / all
     * @param path1 路径 1
     * @param path2 路径 2
     * @param path3 路径 3
     * @return 1 / 0
     */
    public static Long jsonContainsPath(String json, String mode, String path1, String path2, String path3) {
        return containsPaths(json, mode, path1, path2, path3);
    }

    /**
     * MySQL JSON_SEARCH(json, 'one' | 'all', pattern)：按 LIKE 模式（忽略大小写）找字符串值的路径。
     *
     * @param json    JSON 文档
     * @param mode    one / all
     * @param pattern LIKE 模式
     * @return one：首个路径的 JSON 字符串；all：多个时为路径数组；未命中返回 NULL
     */
    public static String jsonSearch(String json, String mode, String pattern) {
        if (json == null || mode == null || pattern == null) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        MysqlJsonPath.searchStrings(JsonFunctions.dejsonize(json), "$", pattern, paths);
        if (paths.isEmpty()) {
            return null;
        }
        boolean isOne = "one".equals(mode.toLowerCase(Locale.ROOT));
        return isOne || paths.size() == 1 ? MysqlJsonPath.format(paths.get(0)) : MysqlJsonPath.format(paths);
    }

    private static Long containsPaths(String json, String mode, String... paths) {
        if (json == null || mode == null) {
            return null;
        }
        boolean isAll = "all".equals(mode.toLowerCase(Locale.ROOT));
        Object document = JsonFunctions.dejsonize(json);
        for (String path : paths) {
            boolean isPresent = !MysqlJsonPath.lookupAll(document, path).isEmpty();
            if (isPresent != isAll) {
                return toFlag(isPresent);
            }
        }
        return toFlag(isAll);
    }

    @SuppressWarnings("unchecked")
    private static boolean contains(Object target, Object candidate) {
        if (target instanceof List) {
            List<Object> elements = (List<Object>) target;
            if (candidate instanceof List) {
                return ((List<Object>) candidate).stream().allMatch(item -> contains(target, item));
            }
            return elements.stream().anyMatch(element -> contains(element, candidate));
        }
        if (target instanceof Map) {
            if (!(candidate instanceof Map)) {
                return false;
            }
            Map<String, Object> targetMap = (Map<String, Object>) target;
            return ((Map<String, Object>) candidate).entrySet().stream().allMatch(entry ->
                    targetMap.containsKey(entry.getKey()) && contains(targetMap.get(entry.getKey()), entry.getValue()));
        }
        return MysqlJsonPath.scalarEquals(target, candidate);
    }

    private static Long toFlag(boolean value) {
        return value ? 1L : 0L;
    }
}
