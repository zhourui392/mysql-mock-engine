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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final Map<String, List<org.apache.calcite.schema.ScalarFunction>> UDFS = udfs();

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
        List<org.apache.calcite.schema.ScalarFunction> overloads = UDFS.get(name.toUpperCase());
        return overloads == null ? Collections.<Function>emptySet() : new ArrayList<Function>(overloads);
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

    private static Map<String, List<org.apache.calcite.schema.ScalarFunction>> udfs() {
        Map<String, List<org.apache.calcite.schema.ScalarFunction>> map = new LinkedHashMap<>();
        register(map, MysqlUdf.class, "NOW", "now");
        register(map, MysqlUdf.class, "CURDATE", "curdate");
        register(map, MysqlUdf.class, "DATE_FORMAT", "date_format");
        register(map, MysqlUdf.class, "FIND_IN_SET", "findInSet");
        register(map, MysqlUdf.class, "STR_TO_DATE", "strToDate");
        register(map, MysqlUdf.class, "TO_DAYS", "toDays");
        register(map, MysqlUdf.class, "MAKEDATE", "makedate");
        register(map, MysqlUdf.class, "MYSQL_DATEDIFF", "mysqlDatediff");
        register(map, MysqlUdf.class, "WEEKDAY", "weekday");
        register(map, MysqlUdf.class, "UNIX_TIMESTAMP", "unixTimestamp");
        register(map, MysqlUdf.class, "FROM_UNIXTIME", "fromUnixtime");
        register(map, MysqlUdf.class, "UTC_TIMESTAMP", "utcTimestamp");
        register(map, MysqlUdf.class, "LOCATE", "locate");
        register(map, MysqlUdf.class, "MYSQL_UUID", "mysqlUuid");
        register(map, MysqlJsonUdf.class, "JSON_EXTRACT", "jsonExtract");
        register(map, MysqlJsonUdf.class, "JSON_UNQUOTE", "jsonUnquote");
        register(map, MysqlJsonUdf.class, "JSON_CONTAINS", "jsonContains");
        register(map, MysqlJsonUdf.class, "JSON_CONTAINS_PATH", "jsonContainsPath");
        register(map, MysqlJsonUdf.class, "JSON_SEARCH", "jsonSearch");
        register(map, MysqlUdf.class, "MYSQL_TIME", "mysqlTime");
        register(map, MysqlUdf.class, "MYSQL_STRCMP", "mysqlStrcmp");
        register(map, MysqlUdf.class, "MYSQL_HEX", "mysqlHex");
        register(map, MysqlUdf.class, "MYSQL_LAST_INSERT_ID", "mysqlLastInsertId");
        register(map, MysqlJsonWriteUdf.class, "MYSQL_JSON_MODIFY", "mysqlJsonModify");
        register(map, MysqlJsonWriteUdf.class, "MYSQL_JSON_REMOVE", "mysqlJsonRemove");
        register(map, MysqlJsonWriteUdf.class, "MYSQL_TO_JSON", "mysqlToJson");
        register(map, MysqlJsonWriteUdf.class, "MYSQL_JSON_FORMAT", "mysqlJsonFormat");
        register(map, MysqlJsonWriteUdf.class, "MYSQL_JSON_EXACT", "mysqlJsonExact");
        register(map, MysqlJsonWriteUdf.class, "MYSQL_JSON_KEYS", "mysqlJsonKeys");
        register(map, MysqlJsonWriteUdf.class, "JSON_QUOTE", "jsonQuote");
        register(map, MysqlJsonWriteUdf.class, "JSON_OVERLAPS", "jsonOverlaps");
        return Collections.unmodifiableMap(map);
    }

    /**
     * 同名 public static 方法全部注册为重载（参数个数或类型不同）。
     *
     * @param map        注册表
     * @param owner      UDF 所在类
     * @param sqlName    SQL 函数名
     * @param methodName Java 方法名
     */
    private static void register(Map<String, List<org.apache.calcite.schema.ScalarFunction>> map, Class<?> owner,
                                 String sqlName, String methodName) {
        List<org.apache.calcite.schema.ScalarFunction> overloads = new ArrayList<>();
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(methodName)) {
                overloads.add(ScalarFunctionImpl.create(method));
            }
        }
        if (overloads.isEmpty()) {
            throw new IllegalStateException("UDF 方法缺失: " + owner.getSimpleName() + "." + methodName);
        }
        map.put(sqlName, Collections.unmodifiableList(overloads));
    }
}
