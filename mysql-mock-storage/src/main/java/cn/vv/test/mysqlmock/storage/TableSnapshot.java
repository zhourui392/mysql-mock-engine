package cn.vv.test.mysqlmock.storage;

import cn.vv.test.mysqlmock.schema.TableDefinition;

import java.util.ArrayList;
import java.util.List;

/** 单表快照（含自增指针与行 ID 序列）。 */
final class TableSnapshot {

    final TableDefinition definition;
    final List<Object[]> values;
    final long nextAutoIncrement;
    final long rowIdSequence;

    TableSnapshot(TableDefinition definition, List<Object[]> values, long nextAutoIncrement, long rowIdSequence) {
        this.definition = definition;
        this.values = new ArrayList<>(values);
        this.nextAutoIncrement = nextAutoIncrement;
        this.rowIdSequence = rowIdSequence;
    }
}
