package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import cn.vv.test.mysqlmock.storage.Row;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcite 表适配：扫描内存行快照；internal 形态额外暴露稳定行 ID（UPDATE/DELETE 定位用）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MockCalciteTable extends AbstractTable implements ScannableTable {

    static final String ROWID_COLUMN = "__rowid";

    private final MemoryTable table;
    private final boolean withRowid;

    MockCalciteTable(MemoryTable table, boolean withRowid) {
        this.table = table;
        this.withRowid = withRowid;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
        List<org.apache.calcite.rel.type.RelDataTypeField> fields = new ArrayList<>();
        if (withRowid) {
            fields.add(new RelDataTypeFieldImpl(ROWID_COLUMN, 0,
                    factory.createSqlType(org.apache.calcite.sql.type.SqlTypeName.BIGINT)));
        }
        int offset = withRowid ? 1 : 0;
        List<ColumnDefinition> columns = table.definition().columns();
        for (int i = 0; i < columns.size(); i++) {
            fields.add(new RelDataTypeFieldImpl(columns.get(i).name(), i + offset,
                    MysqlTypeMapper.toRelType(columns.get(i), factory)));
        }
        return new RelRecordType(fields);
    }

    @Override
    public Enumerable<Object[]> scan(DataContext context) {
        List<Object[]> result = new ArrayList<>();
        for (Row row : table.snapshotRows()) {
            Object[] values = row.valuesCopy();
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof java.sql.Timestamp) {
                    java.sql.Timestamp ts = (java.sql.Timestamp) values[i];
                    values[i] = ts.getTime()
                            + java.util.TimeZone.getDefault().getOffset(ts.getTime());  // TZPROBE-HACK-C
                }
            }
            if (withRowid) {
                Object[] withId = new Object[values.length + 1];
                withId[0] = row.rowId();
                System.arraycopy(values, 0, withId, 1, values.length);
                result.add(withId);
            } else {
                result.add(values);
            }
        }
        return Linq4j.asEnumerable(result);
    }
}
