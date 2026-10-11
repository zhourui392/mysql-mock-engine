package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.schema.SchemaCatalog;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.SchemaVersion;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 活视图 Schema：直连 Catalog 与 MemoryTable（表注册后立即可查）。
 * <p>database=null 暴露全部表（root 下 "db" / "internal"）；非空时仅暴露该库的表，
 * 支撑 database.table 限定名查询。UDF（NOW/CURDATE/DATE_FORMAT）挂在 schema 上随链解析。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MockCalciteSchema implements Schema {

    private static final Map<String, org.apache.calcite.schema.ScalarFunction> UDFS = udfs();

    private final SchemaCatalog catalog;
    private final Map<String, MemoryTable> tables;
    private final boolean withRowid;
    /** null 表示全部表；非空为 database 限定视图。 */
    private final String database;

    MockCalciteSchema(SchemaCatalog catalog, Map<String, MemoryTable> tables, boolean withRowid) {
        this(catalog, tables, withRowid, null);
    }

    private MockCalciteSchema(SchemaCatalog catalog, Map<String, MemoryTable> tables,
                              boolean withRowid, String database) {
        this.catalog = catalog;
        this.tables = tables;
        this.withRowid = withRowid;
        this.database = database;
    }

    @Override
    public Table getTable(String name) {
        MemoryTable table = tables.get(name.toLowerCase());
        return table == null ? null : new MockCalciteTable(table, withRowid);
    }

    @Override
    public Set<String> getTableNames() {
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, MemoryTable> entry : tables.entrySet()) {
            if (database == null || database.equals(entry.getValue().definition().database())) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    @Override
    public Schema getSubSchema(String name) {
        if (database != null) {
            return null;
        }
        String lower = name.toLowerCase();
        for (MemoryTable table : tables.values()) {
            if (lower.equals(table.definition().database())) {
                return new MockCalciteSchema(catalog, tables, withRowid, lower);
            }
        }
        return null;
    }

    @Override
    public Set<String> getSubSchemaNames() {
        Set<String> names = new HashSet<>();
        if (database == null) {
            for (MemoryTable table : tables.values()) {
                if (table.definition().database() != null) {
                    names.add(table.definition().database());
                }
            }
        }
        return names;
    }

    @Override
    public Collection<Function> getFunctions(String name) {
        org.apache.calcite.schema.ScalarFunction udf = UDFS.get(name.toUpperCase());
        return udf == null ? Collections.<Function>emptySet() : Collections.<Function>singleton(udf);
    }

    @Override
    public Set<String> getFunctionNames() {
        return UDFS.keySet();
    }

    @Override
    public Expression getExpression(SchemaPlus parentSchema, String name) {
        return org.apache.calcite.schema.Schemas.subSchemaExpression(parentSchema, name, getClass());
    }

    @Override
    public org.apache.calcite.rel.type.RelProtoDataType getType(String name) {
        return null;
    }

    @Override
    public Set<String> getTypeNames() {
        return Collections.emptySet();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Schema snapshot(SchemaVersion version) {
        return this;
    }

    private static Map<String, org.apache.calcite.schema.ScalarFunction> udfs() {
        Map<String, org.apache.calcite.schema.ScalarFunction> map = new LinkedHashMap<>();
        try {
            map.put("NOW", ScalarFunctionImpl.create(MysqlUdf.class.getMethod("now")));
            map.put("CURDATE", ScalarFunctionImpl.create(MysqlUdf.class.getMethod("curdate")));
            map.put("DATE_FORMAT", ScalarFunctionImpl.create(MysqlUdf.class.getMethod(
                    "date_format", java.sql.Timestamp.class, String.class)));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("UDF 方法缺失", e);
        }
        return Collections.unmodifiableMap(map);
    }
}
