package cn.vv.test.mysqlmock.storage;

import java.math.BigDecimal;
import java.util.Locale;

/** 唯一键与等值比较的归一化（D-16：清单内 _ci 语义 = 大小写不敏感 + PAD SPACE 忽略尾随空格）。 */
public final class CollationNormalizer {

    private CollationNormalizer() {
    }

    /** 唯一索引键分量归一化。 */
    public static Object normalizeKeyPart(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return stripTrailingSpaces((String) value).toLowerCase(Locale.ROOT);
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros();
        }
        return value;
    }

    /** 等值比较（含字符串 _ci + PAD SPACE 语义）。 */
    public static boolean equalsNormalized(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        return normalizeKeyPart(left).equals(normalizeKeyPart(right));
    }

    /** 排序比较（归一化后；null 排最前）。 */
    public static int compareNormalized(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        Object l = normalizeKeyPart(left);
        Object r = normalizeKeyPart(right);
        if (l instanceof String && r instanceof String) {
            return ((String) l).compareTo((String) r);
        }
        if (l instanceof Number && r instanceof Number) {
            return Double.compare(((Number) l).doubleValue(), ((Number) r).doubleValue());
        }
        if (l instanceof Comparable && l.getClass() == r.getClass()) {
            @SuppressWarnings("unchecked")
            int cmp = ((Comparable<Object>) l).compareTo(r);
            return cmp;
        }
        return String.valueOf(l).compareTo(String.valueOf(r));
    }

    private static String stripTrailingSpaces(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }
}
