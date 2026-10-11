package cn.vv.test.mysqlmock.storage;

/** 行：稳定行 ID + 列值快照（出入防御性拷贝）。 */
public final class Row {

    private final long rowId;
    private Object[] values;

    Row(long rowId, Object[] values) {
        this.rowId = rowId;
        this.values = values.clone();
    }

    public long rowId() {
        return rowId;
    }

    public synchronized Object[] valuesCopy() {
        return values.clone();
    }

    synchronized void replaceValues(Object[] newValues) {
        this.values = newValues.clone();
    }
}
