package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.storage.MemoryTable;
import cn.vv.test.mysqlmock.storage.Row;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.ArrayList;
import java.util.List;

/** Calcite 表适配：扫描内存行快照；internal 形态额外暴露稳定行 ID（UPDATE/DELETE 定位用）。 */
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
        List<RelDataTypeField> fields = new ArrayList<>();
        if (withRowid) {
            fields.add(new RelDataTypeFieldImpl(ROWID_COLUMN, 0,
                    factory.createTypeWithNullability(factory.createSqlType(org.apache.calcite.sql.type.SqlTypeName.BIGINT), false)));
        }
        int offset = withRowid ? 1 : 0;
        List<cn.vv.test.mysqlmock.schema.ColumnDefinition> columns = table.definition().columns();
        for (int i = 0; i < columns.size(); i++) {
            RelDataType type = MysqlTypeMapper.toRelType(columns.get(i), factory);
            fields.add(new RelDataTypeFieldImpl(columns.get(i).name(), i + offset, type));
        }
        return new RelRecordType(fields);
    }

    @Override
    public Enumerable<Object[]> scan(DataContext context) {
        List<Object[]> result = new ArrayList<>();
        for (Row row : table.snapshotRows()) {
            Object[] values = row.valuesCopy();
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

    /** 轻量字段实现（避免依赖 RelDataTypeFactory 的 builder 细节）。 */
    private static final class RelDataTypeFieldImpl implements RelDataTypeField {

        private final String name;
        private final int index;
        private final RelDataType type;

        RelDataTypeFieldImpl(String name, int index, RelDataType type) {
            this.name = name;
            this.index = index;
            this.type = type;
        }

        @Override
        public String getKey() {
            return name;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public RelDataType getType() {
            return type;
        }

        @Override
        public boolean isDynamicStar() {
            return false;
        }

        @Override
        public String toString() {
            return "#" + index + ": " + name;
        }
    }
}
