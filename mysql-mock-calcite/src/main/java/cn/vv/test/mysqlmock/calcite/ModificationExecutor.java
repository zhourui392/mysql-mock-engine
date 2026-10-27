package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.AffectedRowsMode;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.UpdateResult;
import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.IndexDefinition;
import cn.vv.test.mysqlmock.schema.MysqlType;
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
    private long lastInsertId;

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
     * INSERT：源行（VALUES 字面量快路径 / Calcite 表达式回退 / SELECT 物化）→ 默认值/自增/生成列装配
     * → 按模式落表：PLAIN 直插；REPLACE 先删唯一键冲突行；ON DUPLICATE KEY UPDATE 冲突时改写既有行
     * （VALUES(col) 代入待插值，影响行数对齐 Connector/J 默认 found-rows：插=1 / 变=2 / 不变=1）。
     *
     * @param insert            已解析 INSERT
     * @param params            绑定参数
     * @param replaceInto       REPLACE INTO 语义（引擎层已改写为 INSERT）
     * @param onDuplicateClause ON DUPLICATE KEY UPDATE 赋值子句原文（null 表示无）
     * @return 影响行数与自增回填键
     */
    UpdateResult executeInsert(SqlInsert insert, Object[] params, boolean replaceInto,
                               String onDuplicateClause) {
        MemoryTable table = resolveTable(insert.getTargetTable());
        TableDefinition definition = table.definition();
        int[] targetIndexes = resolveInsertTargets(definition, insert.getTargetColumnList());
        List<UpdateAssignment> assignments = onDuplicateClause == null
                ? null : parseAssignments(definition, onDuplicateClause);
        if (replaceInto && assignments != null) {
            throw MysqlMockException.parseError("REPLACE 与 ON DUPLICATE KEY UPDATE 不能同时使用");
        }
        // SELECT 源：物化行值与 VALUES 行共用装配管道
        SqlNode source = insert.getSource();
        List<Object[]> selectRows = null;
        if (source.getKind() == SqlKind.SELECT) {
            selectRows = executeSelectSource(source, params);
        }
        int sourceParamCount = countParams(source);
        List<SqlNode> rowNodes = selectRows == null ? valueRows(source) : null;

        int affected = 0;
        List<Object> generatedKeys = new ArrayList<>();
        Long firstGeneratedId = null;
        int total = rowNodes != null ? rowNodes.size() : selectRows.size();
        for (int rowIndex = 0; rowIndex < total; rowIndex++) {
            Object[] operandValues = rowNodes != null
                    ? evaluateOperands(rowNodes.get(rowIndex), params, definition, targetIndexes)
                    : selectRows.get(rowIndex);
            if (operandValues.length != targetIndexes.length) {
                throw MysqlMockException.parseError("Column count doesn't match value count at row "
                        + (rowIndex + 1) + "（表 " + definition.name() + "）");
            }
            Object[] full = new Object[definition.columns().size()];
            boolean[] provided = new boolean[definition.columns().size()];
            for (int i = 0; i < targetIndexes.length; i++) {
                int index = targetIndexes[i];
                full[index] = coerce(definition.columns().get(index), operandValues[i]);
                provided[index] = true;
            }
            fillDefaults(definition, full, provided);
            evaluateGeneratedColumns(table, full);

            if (assignments != null) {
                Row conflict = findUniqueConflict(table, full);
                if (conflict != null) {
                    affected += applyOnDuplicateAssignments(table, conflict, full,
                            assignments, params, sourceParamCount);
                    continue;
                }
                firstGeneratedId = insertRow(table, full, generatedKeys, firstGeneratedId);
                affected++;
                continue;
            }
            if (replaceInto) {
                List<Row> conflicts = findAllUniqueConflicts(table, full);
                if (!conflicts.isEmpty()) {
                    table.delete(conflicts);
                    affected += conflicts.size();
                }
                firstGeneratedId = insertRow(table, full, generatedKeys, firstGeneratedId);
                affected++;
                continue;
            }
            firstGeneratedId = insertRow(table, full, generatedKeys, firstGeneratedId);
            affected++;
        }
        if (firstGeneratedId != null) {
            lastInsertId = firstGeneratedId;
        }
        return new UpdateResult(affected, generatedKeys);
    }

    /** 单行 operands 求值：字面量快路径，未命中走整行表达式 SELECT 回退（裸参数按列 CAST 锚定）。 */
    private Object[] evaluateOperands(SqlNode rowNode, Object[] params,
                                      TableDefinition definition, int[] targetIndexes) {
        List<SqlNode> operands = rowOperands(rowNode);
        Object[] fastValues = new Object[operands.size()];
        boolean fastPath = true;
        for (int i = 0; i < operands.size(); i++) {
            fastValues[i] = tryFastValue(operands.get(i), params);
            if (fastValues[i] == NEEDS_CALCITE) {
                fastPath = false;
                break;
            }
        }
        if (fastPath) {
            return fastValues;
        }
        return evaluateRowExpressions(operands, params, castTypes(definition, targetIndexes)).toArray();
    }

    /** INSERT ... SELECT 源：unparse 后交 Calcite 执行（函数名反引号还原，仅绑定源自身参数前缀）。 */
    private List<Object[]> executeSelectSource(SqlNode source, Object[] params) {
        String selectSql = unparse(source).replaceAll("`([A-Za-z_][A-Za-z0-9_]*)`\\s*\\(", "$1(");
        int count = countParams(source);
        Object[] bound = params == null || params.length == 0
                ? new Object[0]
                : java.util.Arrays.copyOf(params, Math.min(count, params.length));
        QueryResult result = queryEngine.executeSelect(selectSql, bound);
        return new ArrayList<>(result.rows());
    }

    /** AST 中动态参数的最大下标 + 1（SELECT 源绑定前缀截断与 ODKU 参数基址）。 */
    private static int countParams(SqlNode node) {
        if (node == null) {
            return 0;
        }
        if (node instanceof SqlDynamicParam) {
            return ((SqlDynamicParam) node).getIndex() + 1;
        }
        int max = 0;
        if (node instanceof SqlCall) {
            for (SqlNode operand : ((SqlCall) node).getOperandList()) {
                max = Math.max(max, countParams(operand));
            }
        } else if (node instanceof SqlNodeList) {
            for (SqlNode operand : (SqlNodeList) node) {
                max = Math.max(max, countParams(operand));
            }
        }
        return max;
    }

    /** 解析 ODKU 赋值子句：col = expr（顶层逗号切分；列不可为生成列）。 */
    private List<UpdateAssignment> parseAssignments(TableDefinition definition, String clause) {
        List<UpdateAssignment> assignments = new ArrayList<>();
        for (String segment : splitTopLevel(clause, ',')) {
            String trimmed = segment.trim();
            int eq = topLevelIndexOf(trimmed, '=');
            if (eq <= 0) {
                throw MysqlMockException.parseError("ON DUPLICATE KEY UPDATE 赋值无法解析: " + trimmed);
            }
            String column = stripIdentifier(trimmed.substring(0, eq).trim());
            int index = definition.columnIndex(column);
            if (index < 0) {
                throw MysqlMockException.parseError("Unknown column '" + column
                        + "' in ON DUPLICATE KEY UPDATE（表 " + definition.name() + "）");
            }
            if (definition.columns().get(index).generated()) {
                throw MysqlMockException.unsupported("不能写入生成列 " + column
                        + "（ON DUPLICATE KEY UPDATE，D-13）");
            }
            assignments.add(new UpdateAssignment(index, trimmed.substring(eq + 1).trim()));
        }
        if (assignments.isEmpty()) {
            throw MysqlMockException.parseError("ON DUPLICATE KEY UPDATE 缺少赋值子句");
        }
        return assignments;
    }

    /**
     * ODKU 冲突行改写：VALUES(col) 代入待插值字面量 → 合成 internal 单行 SELECT 求值赋值表达式
     * （参数按源参数量 + 出现序重排绑定）→ 共用行写回（值变化才触发 ON UPDATE / 生成列重算）。
     *
     * @return 2（值变化）/ 1（found-rows 口径未变化）
     */
    private int applyOnDuplicateAssignments(MemoryTable table, Row conflict, Object[] candidate,
                                            List<UpdateAssignment> assignments, Object[] params,
                                            int baseParamCount) {
        TableDefinition definition = table.definition();
        StringBuilder sql = new StringBuilder("SELECT ");
        int markerCount = 0;
        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String substituted = substituteValuesFunction(
                    assignments.get(i).exprText, definition, candidate);
            substituted = castMarkersToTargetType(substituted, MysqlTypeMapper.castTypeName(
                    definition.columns().get(assignments.get(i).columnIndex)));
            sql.append(substituted);
            markerCount += countTextMarkers(substituted);
        }
        sql.append(" FROM `internal`.`").append(definition.name())
                .append("` WHERE `__rowid` = ").append(conflict.rowId());
        Object[] bound = new Object[markerCount];
        for (int j = 0; j < markerCount; j++) {
            int original = baseParamCount + j;
            if (params == null || original >= params.length) {
                throw MysqlMockException.parseError("缺少绑定参数（第 " + (original + 1) + " 个 ? 无值）");
            }
            bound[j] = params[original];
        }
        QueryResult evaluated = markerCount == 0
                ? queryEngine.queryInternal(sql.toString())
                : queryEngine.queryInternal(sql.toString(), bound);
        if (evaluated.rowCount() != 1) {
            throw MysqlMockException.parseError("ON DUPLICATE KEY UPDATE 赋值求值失败: " + sql);
        }
        Object[] values = evaluated.rows().get(0);
        int[] assignedIndexes = new int[assignments.size()];
        for (int i = 0; i < assignments.size(); i++) {
            assignedIndexes[i] = assignments.get(i).columnIndex;
        }
        boolean changed = applyAssignmentsAndWriteBack(table, conflict, assignedIndexes, values);
        return changed ? 2 : 1;
    }

    /** 行写回公共段：赋值（按列 coerce）、值变化判定、ON UPDATE 时间戳、生成列重算、replaceRow。 */
    private boolean applyAssignmentsAndWriteBack(MemoryTable table, Row row,
                                                 int[] assignedIndexes, Object[] assignedValues) {
        TableDefinition definition = table.definition();
        Object[] oldValues = row.valuesCopy();
        Object[] newValues = oldValues.clone();
        boolean valueChanged = false;
        for (int i = 0; i < assignedIndexes.length; i++) {
            int index = assignedIndexes[i];
            Object value = coerce(definition.columns().get(index), assignedValues[i]);
            newValues[index] = value;
            if (!CollationNormalizer.equalsNormalized(oldValues[index], value)) {
                valueChanged = true;
            }
        }
        if (valueChanged) {
            for (int i = 0; i < definition.columns().size(); i++) {
                ColumnDefinition column = definition.columns().get(i);
                if (column.onUpdateCurrentTimestamp() && !contains(assignedIndexes, i)) {
                    newValues[i] = Timestamp.valueOf(LocalDateTime.now(clock));
                }
            }
        }
        evaluateGeneratedColumns(table, newValues);
        return table.replaceRow(row, newValues);
    }

    /** 唯一键冲突探测（按候选完整行值；任一分量为 NULL 的索引跳过）。 */
    private List<Row> findAllUniqueConflicts(MemoryTable table, Object[] candidate) {
        List<Row> conflicts = new ArrayList<>();
        for (IndexDefinition index : table.definition().indexes()) {
            if (!index.unique()) {
                continue;
            }
            Object[] parts = indexParts(table.definition(), index, candidate);
            if (parts == null) {
                continue;
            }
            Row hit = table.findByUniqueValues(index.name(), parts);
            if (hit != null && !conflicts.contains(hit)) {
                conflicts.add(hit);
            }
        }
        return conflicts;
    }

    private Row findUniqueConflict(MemoryTable table, Object[] candidate) {
        List<Row> conflicts = findAllUniqueConflicts(table, candidate);
        return conflicts.isEmpty() ? null : conflicts.get(0);
    }

    private static Object[] indexParts(TableDefinition definition, IndexDefinition index,
                                       Object[] values) {
        Object[] parts = new Object[index.columns().size()];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = values[definition.columnIndex(index.columns().get(i))];
            if (parts[i] == null) {
                return null;
            }
        }
        return parts;
    }

    /**
     * MySQL LAST_INSERT_ID()：最近一条生成过自增值的 INSERT 里第一个自动生成的值；显式给值的行不算。
     *
     * @return 自增值；还没有自动生成过时为 0
     */
    long lastInsertId() {
        return lastInsertId;
    }

    void recordLastInsertId(long value) {
        lastInsertId = value;
    }

    /**
     * 插入一行并登记 generated key。
     *
     * @param table            目标表
     * @param full             整行值
     * @param generatedKeys    本语句的 generated key
     * @param firstGeneratedId 本语句已自动生成的第一个自增值，没有时为 null
     * @return 更新后的第一个自动生成的自增值
     */
    private Long insertRow(MemoryTable table, Object[] full, List<Object> generatedKeys, Long firstGeneratedId) {
        TableDefinition definition = table.definition();
        boolean isAutoValueMissing = isAutoValueMissing(definition, full);
        Row row = table.insert(full);
        addKey(definition, row, generatedKeys);
        if (!isAutoValueMissing || firstGeneratedId != null) {
            return firstGeneratedId;
        }
        Object key = generatedKey(definition, row);
        return key instanceof Number ? Long.valueOf(((Number) key).longValue()) : null;
    }

    private static boolean isAutoValueMissing(TableDefinition definition, Object[] full) {
        for (int i = 0; i < definition.columns().size(); i++) {
            if (definition.columns().get(i).autoIncrement()) {
                return full[i] == null || full[i] instanceof Number && ((Number) full[i]).longValue() == 0;
            }
        }
        return false;
    }

    private void addKey(TableDefinition definition, Row row, List<Object> generatedKeys) {
        Object key = generatedKey(definition, row);
        if (key != null) {
            generatedKeys.add(key);
        }
    }

    /** ODKU 赋值项：目标列下标 + 表达式原文。 */
    private static final class UpdateAssignment {
        final int columnIndex;
        final String exprText;

        UpdateAssignment(int columnIndex, String exprText) {
            this.columnIndex = columnIndex;
            this.exprText = exprText;
        }
    }

    /**
     * VALUES(col) 代入：顶层扫描 VALUES( 列引用 )，以候选行值字面量替换
     * （MySQL 专属 VALUES() 不进 Calcite 解析）。
     */
    static String substituteValuesFunction(String expr, TableDefinition definition, Object[] candidate) {
        StringBuilder out = new StringBuilder(expr.length() + 16);
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuotedText(expr, i);
                out.append(expr, i, end);
                i = end;
                continue;
            }
            if (expr.regionMatches(true, i, "VALUES", 0, 6)) {
                int open = skipSpaces(expr, i + 6);
                if (open < n && expr.charAt(open) == '(') {
                    int close = findMatchingParen(expr, open);
                    String column = stripIdentifier(expr.substring(open + 1, close).trim());
                    int index = definition.columnIndex(column);
                    if (index < 0) {
                        throw MysqlMockException.parseError("Unknown column '" + column
                                + "' in VALUES()（表 " + definition.name() + "）");
                    }
                    out.append(renderLiteral(candidate[index]));
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 候选行值 → SQL 字面量（时间/字符串加引号转义）。 */
    static String renderLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "1" : "0";
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof java.util.Date) {
            return "'" + value + "'";
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static int skipSpaces(String sql, int pos) {
        while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static int skipQuotedText(String sql, int start) {
        char quote = sql.charAt(start);
        int j = start + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return j;
    }

    private static int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        int i = openIndex;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuotedText(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return text.length();
    }

    /** 顶层按分隔符切分（引号/括号感知）。 */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuotedText(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int topLevelIndexOf(String text, char target) {
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuotedText(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == target && depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int countTextMarkers(String sql) {
        int i = 0;
        int count = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuotedText(sql, i);
                continue;
            }
            if (c == '?') {
                count++;
            }
            i++;
        }
        return count;
    }

    /** ODKU 表达式中的裸 ? 按目标列类型 CAST 锚定（无上下文 ? 过不了校验）。 */
    static String castMarkersToTargetType(String expr, String typeName) {
        if (expr.indexOf('?') < 0) {
            return expr;
        }
        StringBuilder out = new StringBuilder(expr.length() + 24);
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuotedText(expr, i);
                out.append(expr, i, end);
                i = end;
                continue;
            }
            if (c == '?') {
                out.append("CAST(? AS ").append(typeName).append(")");
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String stripIdentifier(String token) {
        String trimmed = token.trim();
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1).replace("``", "`");
        }
        return trimmed;
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
        QueryResult located = queryEngine.queryInternal(sql.toString(), params);
        int matched = 0;
        int changed = 0;
        for (Object[] resultRow : located.rows()) {
            Row target = table.rowById((Long) resultRow[0]);
            if (target == null) {
                throw new IllegalStateException("UPDATE 定位到不存在的行 ID: " + resultRow[0]);
            }
            Object[] assigned = java.util.Arrays.copyOfRange(resultRow, 1, resultRow.length);
            if (applyAssignmentsAndWriteBack(table, target, setIndexes, assigned)) {
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
        QueryResult located = queryEngine.queryInternal(sql.toString(), params);
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
        if (column.type() == MysqlType.JSON && value != null) {
            return MysqlJsonPath.normalizeColumnValue(value, column.name());
        }
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
