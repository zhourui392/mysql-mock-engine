package cn.vv.test.mysqlmock.calcite;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.vv.test.mysqlmock.api.MysqlMockException;

/**
 * JSON 路径的一个步骤：{@code .key}、{@code [n]}、{@code [last - n]}、{@code [m to n]}、{@code .*}、{@code [*]}、
 * {@code **}。数组下标用在非数组值上时，MySQL 把该值视为单元素数组，{@code [0]} / {@code [last]} 取到它本身。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class MysqlJsonPathStep {

    private static final Pattern LAST = Pattern.compile("last(?:\\s*-\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static final Pattern RANGE_SEPARATOR = Pattern.compile("\\s+to\\s+", Pattern.CASE_INSENSITIVE);

    private static final String ANY = "*";

    private final Kind kind;

    private final String key;

    private final Position from;

    private final Position to;

    private MysqlJsonPathStep(Kind kind, String key, Position from, Position to) {
        this.kind = kind;
        this.key = key;
        this.from = from;
        this.to = to;
    }

    static MysqlJsonPathStep key(String key) {
        return new MysqlJsonPathStep(Kind.KEY, key, null, null);
    }

    static MysqlJsonPathStep of(Kind kind) {
        return new MysqlJsonPathStep(kind, null, null, null);
    }

    /**
     * 解析方括号内的数组选择器。
     *
     * @param selector 方括号内文本
     * @param path     完整路径（用于报错）
     * @return 步骤
     */
    static MysqlJsonPathStep arrayStep(String selector, String path) {
        String trimmed = selector.trim();
        if (ANY.equals(trimmed)) {
            return of(Kind.ANY_ELEMENT);
        }
        String[] bounds = RANGE_SEPARATOR.split(trimmed);
        if (bounds.length == 2) {
            return new MysqlJsonPathStep(Kind.RANGE, null, Position.parse(bounds[0], path),
                    Position.parse(bounds[1], path));
        }
        return new MysqlJsonPathStep(Kind.INDEX, null, Position.parse(trimmed, path), null);
    }

    Kind kind() {
        return kind;
    }

    String key() {
        return key;
    }

    /**
     * 单下标步骤在给定长度数组里的位置。
     *
     * @param size 数组长度
     * @return 下标；{@code [last - n]} 超出开头时为负数
     */
    int index(int size) {
        return from.resolve(size);
    }

    /**
     * 是否可能命中多个值：通配符与范围。MySQL 的 JSON_EXTRACT 对这类路径总是返回数组，修改类函数不接受这类路径。
     *
     * @return 多值步骤时为 true
     */
    boolean isMultiValued() {
        return kind != Kind.KEY && kind != Kind.INDEX;
    }

    /**
     * 在一个节点上执行本步骤，命中值追加到 out。
     *
     * @param node 当前节点
     * @param out  命中值
     */
    @SuppressWarnings("unchecked")
    void apply(Object node, List<Object> out) {
        switch (kind) {
            case KEY:
                if (node instanceof Map && ((Map<String, Object>) node).containsKey(key)) {
                    out.add(((Map<String, Object>) node).get(key));
                }
                break;
            case INDEX:
                applyIndex(node, out);
                break;
            case RANGE:
                applyRange(node, out);
                break;
            case ANY_MEMBER:
                if (node instanceof Map) {
                    out.addAll(((Map<String, Object>) node).values());
                }
                break;
            case ANY_ELEMENT:
                if (node instanceof List) {
                    out.addAll((List<Object>) node);
                }
                break;
            default:
                collectSelfAndDescendants(node, out);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyIndex(Object node, List<Object> out) {
        List<Object> elements = node instanceof List ? (List<Object>) node : Collections.singletonList(node);
        int index = from.resolve(elements.size());
        if (index >= 0 && index < elements.size()) {
            out.add(elements.get(index));
        }
    }

    @SuppressWarnings("unchecked")
    private void applyRange(Object node, List<Object> out) {
        if (!(node instanceof List)) {
            return;
        }
        List<Object> elements = (List<Object>) node;
        int first = Math.max(0, from.resolve(elements.size()));
        int last = Math.min(elements.size() - 1, to.resolve(elements.size()));
        for (int i = first; i <= last; i++) {
            out.add(elements.get(i));
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectSelfAndDescendants(Object node, List<Object> out) {
        out.add(node);
        Iterable<Object> children = node instanceof Map ? ((Map<String, Object>) node).values()
                : node instanceof List ? (List<Object>) node : Collections.emptyList();
        for (Object child : children) {
            collectSelfAndDescendants(child, out);
        }
    }

    /**
     * 步骤类型。
     *
     * @author zhourui
     * @since 2026-10-23
     */
    enum Kind {
        KEY, INDEX, RANGE, ANY_MEMBER, ANY_ELEMENT, DESCENDANT
    }

    /**
     * 数组位置：{@code n} 从开头数，{@code last - n} 从末尾数。
     *
     * @author zhourui
     * @since 2026-10-23
     */
    private static final class Position {

        private final int offset;

        private final boolean isFromLast;

        private Position(int offset, boolean isFromLast) {
            this.offset = offset;
            this.isFromLast = isFromLast;
        }

        static Position parse(String text, String path) {
            String trimmed = text.trim();
            Matcher last = LAST.matcher(trimmed);
            if (last.matches()) {
                return new Position(last.group(1) == null ? 0 : Integer.parseInt(last.group(1)), true);
            }
            if (NUMBER.matcher(trimmed).matches()) {
                return new Position(Integer.parseInt(trimmed), false);
            }
            throw MysqlMockException.unsupported("无法解析 JSON 数组下标 [" + text + "]: " + path);
        }

        int resolve(int size) {
            return isFromLast ? size - 1 - offset : offset;
        }
    }
}
