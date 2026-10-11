package cn.vv.test.mysqlmock.storage;

import java.util.ArrayList;
import java.util.List;

/** 复合唯一键（分量按 collation 语义归一化）。 */
final class IndexKey {

    private final List<Object> parts;

    private IndexKey(List<Object> parts) {
        this.parts = parts;
    }

    static IndexKey of(Object[] columnValues) {
        List<Object> normalized = new ArrayList<>(columnValues.length);
        for (Object value : columnValues) {
            normalized.add(CollationNormalizer.normalizeKeyPart(value));
        }
        return new IndexKey(normalized);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IndexKey && parts.equals(((IndexKey) other).parts);
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    @Override
    public String toString() {
        return parts.toString();
    }
}
