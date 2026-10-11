package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.schema.SchemaCatalog;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 活视图 Schema：直连 Catalog 与 MemoryTable（表注册后立即可查）。 */
final class MockCalciteSchema implements Schema {

    private final SchemaCatalog catalog;
    private final Map<String, MemoryTable> tables;
    private final boolean withRowid;

    MockCalciteSchema(SchemaCatalog catalog, Map<String, MemoryTable> tables, boolean withRowid) {
        this.catalog = catalog;
        this.tables = tables;
        this.withRowid = withRowid;
    }

    @Override
    public Table getTable(String name) {
        MemoryTable table = tables.get(name.toLowerCase());
        return table == null ? null : new MockCalciteTable(table, withRowid);
    }

    @Override
    public Set<String> getTableNames() {
        return new HashSet<>(catalog.all().keySet());
    }

    @Override
    public Collection<Function> getFunctions(String name) {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getFunctionNames() {
        return Collections.emptySet();
    }

    @Override
    public Schema getSubSchema(String name) {
        return null;
    }

    @Override
    public Set<String> getSubSchemaNames() {
        return Collections.emptySet();
    }

    @Override
    public Table getTable(String name, boolean caseSensitive) {
        return caseSensitive ? getTable(name) : getTable(name);
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Schema snapshot(org.apache.calcite.schema.SchemaVersion version) {
        return this;
    }
}
