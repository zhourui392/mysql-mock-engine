package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.AffectedRowsMode;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.UpdateResult;
import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.MysqlValueCoercer;
import cn.vv.test.mysqlmock.schema.TableDefinition;
import cn.vv.test.mysqlmock.storage.CollationNormalizer;
import cn.vv.test.mysqlmock.storage.GeneratedColumnEvaluator;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import cn.vv.test.mysqlmock.storage.Row;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自有 DML 执行器（D-03：Calcite 表修改通道不可用）。写路径统一编排：
 * WHERE 匹配与 SET 表达式经 Calcite internal schema（SELECT __rowid）求值，
 * 约束检查 / STORED 生成列 / 行变更 / 索引更新在 MemoryTable 同步块内原子完成。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class ModificationExecutor {

    /** 字面量快路径无法处理时的标记。 */
    private static final Object NEEDS_CALCITE = new Object();

    private final Map<String, MemoryTable> tables;
    private final CalciteQueryEngine queryEngine;
    private final GeneratedColumnEvaluator generatedColumnEvaluator;
    private final Clock clock;
    private final AffectedRowsMode affectedRowsMode;

    ModificationExecutor(Map<String, MemoryTable> tables, CalciteQueryEngine queryEngine,
                         GeneratedColumnEvaluator generatedColumnEvaluator, Clock clock,
                         AffectedRowsMode affectedRowsMode) {
        this.tables = tables;
        this.queryEngine = queryEngine;
        this.generatedColumnEvaluator = generatedColumnEvaluator;
        this.clock = clock;
        this.affectedRowsMode = affectedRowsMode;
    }

    /**
     * INSERT：VALUES 行求值（字面量快路径 + Calcite 表达式回退）→ 默认值/自增/生成列装配 → 入表。
     *
     * @param insert 已解析 INSERT
     * @param params 绑定参数
     * @return 影响行数与自增回填键
     */
    UpdateResult executeInsert(SqlInsert insert, Object[] params) {
        MemoryTable table = resolveTable(insert.getTargetTable());
        TableDefinition definition = table.definition();
        int[] targetIndexes = resolveInsertTargets(definition, insert.getTargetColumnList());
        List<SqlNode> rowNodes = valueRows(insert.getSource());
        int affected = 0;
        List<Object> generatedKeys = new ArrayList<>();
        for (SqlNode rowNode : rowNodes) {
            List<SqlNode> operands = rowOperands(rowNode);
            if (operands.size() != targetIndexes.length) {
                throw MysqlMockException.parseError("Column count doesn't match value count at row "
                        + (affected + 1) + "（表 " + definition.name() + "）");
            }
            Object[] full = new Object[definition.columns().size()];
            boolean[] provided = new boolean[definition.columns().size()];
            boolean fastPath = true;
            Object[] fastValues = new Object[operands.size()];
            for (int i = 0; i < operands.size(); i++) {
                fastValues[i] = tryFastValue(operands.get(i), params);
                if (fastValues[i] == NEEDS_CALCITE) {
                    fastPath = false;
                    break;
                }
            }
            if (fastPath) {
                for (int i = 0; i < targetIndexes.length; i++) {
                    int index = targetIndexes[i];
                    full[index] = coerce(definition.columns().get(index), fastValues[i]);
                    provided[index] = true;
                }
            } else {
                List<Object> values = evaluateRowExpressions(operands, params, castTypes(definition, targetIndexes));
                for (int i = 0; i < targetIndexes.length; i++) {
                    int index = targetIndexes[i];
                    full[index] = coerce(definition.columns().get(index), values.get(i));
                    provided[index] = true;
                }
            }
            fillDefaults(definition, full, provided);
            evaluateGeneratedColumns(table, full);
            Row row = table.insert(full);
            affected++;
            Object key = generatedKey(definition, row);
            if (key != null) {
                generatedKeys.add(key);
            }
        }
        return new UpdateResult(affected, generatedKeys);
    }

    /**
     * UPDATE：internal SELECT __rowid + SET 表达式求值 → 行写回（索引迁移、ON UPDATE 时间戳、生成列重算）。
     *
     * @param update 已解析 UPDATE
     * @param params 绑定参数
     * @return 影响行数（MATCHED/CHANGED 口径，D-10）
     */
    UpdateResult executeUpdate(SqlUpdate update, Object[] params) {
        MemoryTable table = resolveTable(update.getTargetTable());
        TableDefinition definition = table.definition();
        int[] setIndexes = resolveSetTargets(definition, update.getTargetColumnList());
        List<SqlNode> expressions = update.getSourceExpressionList();
        StringBuilder sql = new StringBuilder("SELECT `__rowid`");
        for (int i = 0; i < expressions.size(); i++) {
            sql.append(", ").append(setExpressionSql(expressions.get(i),
                    definition.columns().get(setIndexes[i])));
        }
        sql.append(" FROM `internal`.`").append(definition.name()).append("`");
        if (update.getCondition() != null) {
            sql.append(" WHERE ").append(unparse(update.getCondition()));
        }
        cn.vv.test.mysqlmock.api.QueryResult located = queryEngine.queryInternal(sql.toString(), params);
        int matched = 0;
        int changed = 0;
        for (Object[] resultRow : located.rows()) {
            Row target = table.rowById((Long) resultRow[0]);
            if (target == null) {
                throw new IllegalStateException("UPDATE 定位到不存在的行 ID: " + resultRow[0]);
            }
            Object[] oldValues = target.valuesCopy();
            Object[] newValues = oldValues.clone();
            boolean valueChanged = false;
            for (int i = 0; i < setIndexes.length; i++) {
                int index = setIndexes[i];
                Object value = coerce(definition.columns().get(index), resultRow[i + 1]);
                newValues[index] = value;
                if (!CollationNormalizer.equalsNormalized(oldValues[index], value)) {
                    valueChanged = true;
                }
            }
            if (valueChanged) {
                for (int i = 0; i < definition.columns().size(); i++) {
                    ColumnDefinition column = definition.columns().get(i);
                    if (column.onUpdateCurrentTimestamp() && !contains(setIndexes, i)) {
                        newValues[i] = Timestamp.valueOf(LocalDateTime.now(clock));
                    }
                }
            }
            evaluateGeneratedColumns(table, newValues);
            if (table.replaceRow(target, newValues)) {
                changed++;
            }
            matched++;
        }
        return new UpdateResult(affectedRowsMode == AffectedRowsMode.MATCHED ? matched : changed, null);
    }

    /**
     * DELETE：internal SELECT __rowid 定位 → 批量删除（索引同步拆除）。
     *
     * @param delete 已解析 DELETE
     * @param params 绑定参数
     * @return 影响行数
     */
    UpdateResult executeDelete(SqlDelete delete, Object[] params) {
        MemoryTable table = resolveTable(delete.getTargetTable());
        TableDefinition definition = table.definition();
        StringBuilder sql = new StringBuilder("SELECT `__rowid` FROM `internal`.`")
                .append(definition.name()).append("`");
        if (delete.getCondition() != null) {
            sql.append(" WHERE ").append(unparse(delete.getCondition()));
        }
        cn.vv.test.mysqlmock.api.QueryResult located = queryEngine.queryInternal(sql.toString(), params);
        List<Row> targets = new ArrayList<>();
        for (Object[] resultRow : located.rows()) {
            Row target = table.rowById((Long) resultRow[0]);
            if (target == null) {
                throw new IllegalStateException("DELETE 定位到不存在的行 ID: " + resultRow[0]);
            }
            targets.add(target);
        }
        table.delete(targets);
        return new UpdateResult(targets.size(), null);
    }

    /** 数据集行装载（YAML / Dataset API 共用）：按列名提供值，缺省列走默认值装配。 */
    void insertDatasetRow(String tableName, Map<String, Object> row) {
        MemoryTable table = tables.get(tableName.toLowerCase());
        if (table == null) {
            throw MysqlMockException.tableNotExists(tableName);
        }
        TableDefinition definition = table.definition();
        Object[] full = new Object[definition.columns().size()];
        boolean[] provided = new boolean[definition.columns().size()];
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            int index = definition.columnIndex(entry.getKey());
            if (index < 0) {
                throw MysqlMockException.parseError("数据集引用了不存在的列 " + entry.getKey()
                        + "（表 " + definition.name() + "）");
            }
            if (definition.columns().get(index).generated()) {
                throw MysqlMockException.unsupported("数据集不能提供生成列 " + entry.getKey()
                        + " 的值（表 " + definition.name() + "，D-13）");
            }
            full[index] = coerce(definition.columns().get(index), entry.getValue());
            provided[index] = true;
        }
        fillDefaults(definition, full, provided);
        evaluateGeneratedColumns(table, full);
        table.insert(full);
    }

    private int[] resolveInsertTargets(TableDefinition definition, SqlNodeList columnList) {
        if (columnList == null) {
            // 无列清单：VALUES 对应全部非生成列（MySQL 语义）
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < definition.columns().size(); i++) {
                if (!definition.columns().get(i).generated()) {
                    indexes.add(i);
                }
            }
            int[] result = new int[indexes.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = indexes.get(i);
            }
            return result;
        }
        return resolveColumns(definition, columnList);
    }

    private int[] resolveSetTargets(TableDefinition definition, SqlNodeList columnList) {
        if (columnList == null) {
            throw MysqlMockException.parseError("UPDATE 缺少 SET 列（表 " + definition.name() + "）");
        }
        return resolveColumns(definition, columnList);
    }

    private int[] resolveColumns(TableDefinition definition, SqlNodeList columnList) {
        int[] result = new int[columnList.size()];
        for (int i = 0; i < columnList.size(); i++) {
            SqlNode node = columnList.get(i);
            if (!(node instanceof SqlIdentifier)) {
                throw MysqlMockException.parseError("列名无法解析: " + node);
            }
            String name = ((SqlIdentifier) node).getSimple().toLowerCase();
            int index = definition.columnIndex(name);
            if (index < 0) {
                throw MysqlMockException.parseError("Unknown column '" + name + "'（表 "
                        + definition.name() + "）");
            }
            if (definition.columns().get(index).generated()) {
                throw MysqlMockException.unsupported("不能写入生成列 " + name + "（表 "
                        + definition.name() + "，D-13）");
            }
            if (contains(result, i, index)) {
                throw MysqlMockException.parseError("Column '" + name + "' specified twice（表 "
                        + definition.name() + "）");
            }
            result[i] = index;
        }
        return result;
    }

    private List<SqlNode> valueRows(SqlNode source) {
        List<SqlNode> rows = new ArrayList<>();
        if (source instanceof SqlBasicCall && source.getKind() == SqlKind.VALUES) {
            // Calcite 1.42 形态：VALUES (..),(..) 为一个 VALUES call，operands 即各行
            for (SqlNode node : ((SqlBasicCall) source).getOperandList()) {
                rows.add(node);
            }
            return rows;
        }
        if (source instanceof SqlNodeList) {
            for (SqlNode node : (SqlNodeList) source) {
                rows.add(node);
            }
            return rows;
        }
        throw MysqlMockException.unsupported("INSERT 仅支持 VALUES 形态（INSERT ... SELECT 暂不支持）: "
                + shortSql(source));
    }

    private List<SqlNode> rowOperands(SqlNode rowNode) {
        if (rowNode instanceof SqlBasicCall && rowNode.getKind() == SqlKind.ROW) {
            return ((SqlBasicCall) rowNode).getOperandList();
        }
        List<SqlNode> single = new ArrayList<>();
        single.add(rowNode);
        return single;
    }

    /** 字面量 / 动态参数快路径；返回 NEEDS_CALCITE 表示须走表达式求值。 */
    private Object tryFastValue(SqlNode node, Object[] params) {
        if (node instanceof SqlDynamicParam) {
            int index = ((SqlDynamicParam) node).getIndex();
            if (params == null || index >= params.length) {
                throw MysqlMockException.parseError("缺少绑定参数（第 " + (index + 1) + " 个 ? 无值）");
            }
            return params[index];
        }
        if (node instanceof SqlLiteral) {
            SqlLiteral literal = (SqlLiteral) node;
            SqlTypeName typeName = literal.getTypeName();
            if (typeName == SqlTypeName.NULL) {
                return null;
            }
            if (typeName == SqlTypeName.CHAR) {
                return literal.getValueAs(String.class);
            }
            if (typeName == SqlTypeName.BOOLEAN) {
                return literal.getValueAs(Boolean.class);
            }
            if (typeName == SqlTypeName.DECIMAL || typeName == SqlTypeName.DOUBLE
                    || typeName == SqlTypeName.REAL || typeName == SqlTypeName.FLOAT) {
                Object value = literal.getValue();
                if (value instanceof BigDecimal) {
                    return value;
                }
                return NEEDS_CALCITE;
            }
            if (typeName == SqlTypeName.TINYINT || typeName == SqlTypeName.SMALLINT
                    || typeName == SqlTypeName.INTEGER || typeName == SqlTypeName.BIGINT) {
                Object value = literal.getValue();
                if (value instanceof Long || value instanceof Integer) {
                    return ((Number) value).longValue();
                }
                if (value instanceof BigDecimal) {
                    return value;
                }
                return NEEDS_CALCITE;
            }
            if (typeName == SqlTypeName.TIMESTAMP || typeName == SqlTypeName.DATE
                    || typeName == SqlTypeName.TIME) {
                return literal.getValueAs(String.class);
            }
            return NEEDS_CALCITE;
        }
        return NEEDS_CALCITE;
    }

    /** 表达式行求值：整行改写为无 FROM 的 SELECT 交 Calcite；裸参数按目标列类型 CAST；参数按出现顺序重排绑定。 */
    private List<Object> evaluateRowExpressions(List<SqlNode> operands, Object[] params,
                                                String[] castTypes) {
        List<Integer> originalIndexes = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < operands.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            SqlNode operand = operands.get(i);
            if (isBareParam(operand) && castTypes != null && i < castTypes.length) {
                sql.append("CAST(? AS ").append(castTypes[i]).append(")");
            } else {
                sql.append(unparse(operand));
            }
        }
        for (SqlNode operand : operands) {
            collectParamIndexes(operand, originalIndexes);
        }
        Object[] bound = new Object[originalIndexes.size()];
        for (int i = 0; i < bound.length; i++) {
            int original = originalIndexes.get(i);
            if (params == null || original >= params.length) {
                throw MysqlMockException.parseError("缺少绑定参数（第 " + (original + 1) + " 个 ? 无值）");
            }
            bound[i] = params[original];
        }
        cn.vv.test.mysqlmock.api.QueryResult result = queryEngine.executeSelect(sql.toString(), bound);
        if (result.rowCount() != 1) {
            throw MysqlMockException.parseError("INSERT 行表达式求值失败: " + sql);
        }
        Object[] row = result.rows().get(0);
        List<Object> values = new ArrayList<>(row.length);
        for (Object value : row) {
            values.add(value);
        }
        return values;
    }

    /** SET / VALUES 项的 SQL：裸动态参数按目标列类型 CAST 锚定（无上下文 ? 过不了校验）。 */
    private String setExpressionSql(SqlNode expression, ColumnDefinition column) {
        if (isBareParam(expression)) {
            return "CAST(? AS " + MysqlTypeMapper.castTypeName(column) + ")";
        }
        return unparse(expression);
    }

    private boolean isBareParam(SqlNode node) {
        return node instanceof SqlDynamicParam;
    }

    private String[] castTypes(TableDefinition definition, int[] targetIndexes) {
        String[] types = new String[targetIndexes.length];
        for (int i = 0; i < targetIndexes.length; i++) {
            types[i] = MysqlTypeMapper.castTypeName(definition.columns().get(targetIndexes[i]));
        }
        return types;
    }

    private void collectParamIndexes(SqlNode node, List<Integer> indexes) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlDynamicParam) {
            indexes.add(((SqlDynamicParam) node).getIndex());
            return;
        }
        if (node instanceof SqlCall) {
            for (SqlNode operand : ((SqlCall) node).getOperandList()) {
                collectParamIndexes(operand, indexes);
            }
        }
    }

    private void fillDefaults(TableDefinition definition, Object[] full, boolean[] provided) {
        for (int i = 0; i < definition.columns().size(); i++) {
            if (provided[i]) {
                continue;
            }
            ColumnDefinition column = definition.columns().get(i);
            if (column.generated()) {
                continue;
            }
            if (column.hasDefault()) {
                if (column.defaultCurrentTimestamp()) {
                    full[i] = coerce(column, Timestamp.valueOf(LocalDateTime.now(clock)));
                } else {
                    full[i] = coerce(column, column.defaultValue());
                }
            } else if (column.notNull() && !column.autoIncrement()) {
                // MySQL 语义：缺列且 NOT NULL 无默认 → 1364（显式插 NULL 才是 1048）
                throw MysqlMockException.noDefaultValue(column.name(), definition.name());
            }
        }
    }

    /** STORED 生成列按声明序求值（允许引用更早声明的生成列）。 */
    private void evaluateGeneratedColumns(MemoryTable table, Object[] full) {
        TableDefinition definition = table.definition();
        for (int i = 0; i < definition.columns().size(); i++) {
            ColumnDefinition column = definition.columns().get(i);
            if (column.generated()) {
                full[i] = generatedColumnEvaluator.evaluate(definition, column, full);
                full[i] = coerce(column, full[i]);
            }
        }
    }

    /** 自增列（或单列主键）值作为 generated key 回填。 */
    private Object generatedKey(TableDefinition definition, Row row) {
        Object[] values = row.valuesCopy();
        for (int i = 0; i < definition.columns().size(); i++) {
            if (definition.columns().get(i).autoIncrement()) {
                return values[i];
            }
        }
        if (definition.primaryIndex() != null && definition.primaryIndex().columns().size() == 1) {
            return values[definition.columnIndex(definition.primaryIndex().columns().get(0))];
        }
        return null;
    }

    private MemoryTable resolveTable(SqlNode targetTable) {
        if (!(targetTable instanceof SqlIdentifier)) {
            throw MysqlMockException.parseError("目标表名无法解析: " + shortSql(targetTable));
        }
        List<String> names = ((SqlIdentifier) targetTable).names;
        String tableName = names.get(names.size() - 1).toLowerCase();
        MemoryTable table = tables.get(tableName);
        if (table == null) {
            throw MysqlMockException.tableNotExists(tableName);
        }
        return table;
    }

    private Object coerce(ColumnDefinition column, Object value) {
        return MysqlValueCoercer.coerce(column, value);
    }

    private boolean contains(int[] array, int value) {
        return contains(array, array.length, value);
    }

    private boolean contains(int[] array, int filled, int value) {
        for (int i = 0; i < filled && i < array.length; i++) {
            if (array[i] == value) {
                return true;
            }
        }
        return false;
    }

    private String unparse(SqlNode node) {
        return node.toSqlString(MysqlSqlDialect.DEFAULT).getSql();
    }

    private String shortSql(SqlNode node) {
        String sql = unparse(node);
        return sql.length() > 60 ? sql.substring(0, 60) : sql;
    }
}
