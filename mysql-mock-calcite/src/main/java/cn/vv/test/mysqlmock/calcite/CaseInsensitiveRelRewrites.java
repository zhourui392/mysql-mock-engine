package cn.vv.test.mysqlmock.calcite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexWindowBounds;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;

/**
 * {@link CaseInsensitiveRewriter} 的关系算子改写：GROUP BY / DISTINCT、字符串 MIN / MAX、ORDER BY、
 * UNION 去重按归一化键执行。
 * <p>做法相同：在输入上追加归一化列，算子改用这些列，最后投影回原输出结构。分组输出的字符串键取
 * 组内任一原值（ANY_VALUE），与 MySQL 返回组内某一行原值的行为一致；MIN / MAX 改为 ARG_MIN / ARG_MAX，
 * 按归一化值比较、返回原值。UNION 去重改写为 UNION ALL 加全列分组；INTERSECT / EXCEPT 去重改写为
 * 半连接 / 反连接（各列按归一化值做 IS NOT DISTINCT FROM）加全列分组，结果取左侧原值。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class CaseInsensitiveRelRewrites {

    private static final String NORMALIZED_PREFIX = "$ci_";

    private CaseInsensitiveRelRewrites() {
    }

    static RelNode rewriteAggregate(Aggregate aggregate, UnaryOperator<RexNode> normalizer) {
        if (aggregate.getGroupSets().size() != 1) {
            return aggregate;
        }
        RelNode input = aggregate.getInput();
        List<Integer> stringKeys = stringFields(input, aggregate.getGroupSet().asList());
        List<Integer> distinctArgs = stringDistinctArgs(aggregate, input);
        List<Integer> minMaxArgs = stringMinMaxArgs(aggregate, input);
        if (stringKeys.isEmpty() && distinctArgs.isEmpty() && minMaxArgs.isEmpty()) {
            return aggregate;
        }
        RelBuilder builder = RelFactories.LOGICAL_BUILDER.create(aggregate.getCluster(), null);
        builder.push(input);
        Map<Integer, Integer> normalizedIndex = appendNormalized(builder, input,
                union(union(stringKeys, distinctArgs), minMaxArgs), normalizer);

        List<Integer> newKeys = new ArrayList<>();
        for (int key : aggregate.getGroupSet()) {
            newKeys.add(stringKeys.contains(key) ? normalizedIndex.get(key) : key);
        }
        List<RelBuilder.AggCall> calls = new ArrayList<>();
        for (AggregateCall call : aggregate.getAggCallList()) {
            calls.add(isStringMinMax(call, input) ? argMinMax(builder, call, normalizedIndex)
                    : builder.aggregateCall(remapDistinctArgs(call, normalizedIndex)));
        }
        for (int key : stringKeys) {
            calls.add(builder.aggregateCall(SqlStdOperatorTable.ANY_VALUE, builder.field(key)));
        }
        builder.aggregate(builder.groupKey(ImmutableBitSet.of(newKeys)), calls);
        return projectBack(builder, aggregate, newKeys, stringKeys);
    }

    /**
     * UNION 去重：改写为 UNION ALL 再按全部列分组，分组走 {@link #rewriteAggregate}。
     *
     * @param union      去重的 UNION
     * @param normalizer 归一化函数
     * @return 改写后的关系表达式；没有字符串列时返回原 UNION
     */
    static RelNode rewriteUnion(Union union, UnaryOperator<RexNode> normalizer) {
        boolean hasStringField = union.getRowType().getFieldList().stream()
                .anyMatch(field -> SqlTypeUtil.inCharFamily(field.getType()));
        if (!hasStringField) {
            return union;
        }
        RelNode unionAll = LogicalUnion.create(union.getInputs(), true);
        Aggregate distinct = LogicalAggregate.create(unionAll, ImmutableList.of(),
                ImmutableBitSet.range(unionAll.getRowType().getFieldCount()), null, ImmutableList.of());
        return rewriteAggregate(distinct, normalizer);
    }

    /**
     * INTERSECT / EXCEPT 去重：依次与后续输入做半连接（INTERSECT）或反连接（EXCEPT），再按全部列分组。
     *
     * @param setOp      去重的 INTERSECT 或 EXCEPT
     * @param normalizer 归一化函数
     * @return 改写后的关系表达式；没有字符串列时返回原算子
     */
    static RelNode rewriteIntersectOrMinus(SetOp setOp, UnaryOperator<RexNode> normalizer) {
        boolean hasStringField = setOp.getRowType().getFieldList().stream()
                .anyMatch(field -> SqlTypeUtil.inCharFamily(field.getType()));
        if (!hasStringField) {
            return setOp;
        }
        JoinRelType joinType = setOp.kind == SqlKind.INTERSECT ? JoinRelType.SEMI : JoinRelType.ANTI;
        int fieldCount = setOp.getRowType().getFieldCount();
        RelBuilder builder = RelFactories.LOGICAL_BUILDER.create(setOp.getCluster(), null);
        builder.push(setOp.getInputs().get(0));
        for (RelNode other : setOp.getInputs().subList(1, setOp.getInputs().size())) {
            builder.push(other);
            builder.join(joinType, matchAllFields(builder, fieldCount, normalizer));
        }
        RelNode filtered = builder.build();
        Aggregate distinct = LogicalAggregate.create(filtered, ImmutableList.of(), ImmutableBitSet.range(fieldCount),
                null, ImmutableList.of());
        return RelOptUtil.createCastRel(rewriteAggregate(distinct, normalizer), setOp.getRowType(), true);
    }

    /**
     * INTERSECT ALL / EXCEPT ALL：两侧按归一化的全部列分区编行号，再按「列 + 行号」做半连接或反连接。
     * 左侧第 k 个等值行保留，当且仅当右侧至少有 k 个（INTERSECT ALL）或少于 k 个（EXCEPT ALL）等值行，
     * 与按多重集计数的 MySQL 语义一致。
     *
     * @param setOp      INTERSECT ALL 或 EXCEPT ALL
     * @param normalizer 归一化函数
     * @return 改写后的关系表达式；没有字符串列时返回原算子
     */
    static RelNode rewriteIntersectOrMinusAll(SetOp setOp, UnaryOperator<RexNode> normalizer) {
        boolean hasStringField = setOp.getRowType().getFieldList().stream()
                .anyMatch(field -> SqlTypeUtil.inCharFamily(field.getType()));
        if (!hasStringField) {
            return setOp;
        }
        JoinRelType joinType = setOp.kind == SqlKind.INTERSECT ? JoinRelType.SEMI : JoinRelType.ANTI;
        int fieldCount = setOp.getRowType().getFieldCount();
        RelBuilder builder = RelFactories.LOGICAL_BUILDER.create(setOp.getCluster(), null);
        RelNode current = setOp.getInputs().get(0);
        for (RelNode other : setOp.getInputs().subList(1, setOp.getInputs().size())) {
            builder.push(numberRows(builder, current, normalizer)).push(numberRows(builder, other, normalizer));
            builder.join(joinType, matchAllFields(builder, fieldCount + 1, normalizer));
            List<RexNode> originalFields = new ArrayList<>(builder.fields().subList(0, fieldCount));
            current = builder.project(originalFields, current.getRowType().getFieldNames(), true).build();
        }
        return RelOptUtil.createCastRel(current, setOp.getRowType(), true);
    }

    private static RelNode numberRows(RelBuilder builder, RelNode input, UnaryOperator<RexNode> normalizer) {
        builder.push(input);
        List<RexNode> keys = new ArrayList<>();
        for (RexNode field : builder.fields()) {
            keys.add(normalizeIfString(field, normalizer));
        }
        RexNode rowNumber = builder.aggregateCall(SqlStdOperatorTable.ROW_NUMBER).over().partitionBy(keys)
                .orderBy(keys).rowsTo(RexWindowBounds.CURRENT_ROW).toRex();
        List<RexNode> projects = new ArrayList<>(builder.fields());
        projects.add(rowNumber);
        List<String> names = new ArrayList<>(input.getRowType().getFieldNames());
        names.add(NORMALIZED_PREFIX + "row_number");
        return builder.project(projects, names, true).build();
    }

    /**
     * 栈顶两个输入按前 fieldCount 列逐列匹配（字符串列按归一化值，NULL 视为相等）。
     *
     * @param builder    栈顶为左右两个输入的构造器
     * @param fieldCount 参与匹配的列数
     * @param normalizer 归一化函数
     * @return 连接条件
     */
    private static RexNode matchAllFields(RelBuilder builder, int fieldCount, UnaryOperator<RexNode> normalizer) {
        List<RexNode> conditions = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            conditions.add(builder.isNotDistinctFrom(normalizeIfString(builder.field(2, 0, i), normalizer),
                    normalizeIfString(builder.field(2, 1, i), normalizer)));
        }
        return builder.and(conditions);
    }

    private static RexNode normalizeIfString(RexNode node, UnaryOperator<RexNode> normalizer) {
        return CaseInsensitiveRewriter.isString(node) ? normalizer.apply(node) : node;
    }

    static RelNode rewriteSort(Sort sort, UnaryOperator<RexNode> normalizer) {
        RelNode input = sort.getInput();
        List<Integer> sortFields = new ArrayList<>();
        for (RelFieldCollation fieldCollation : sort.getCollation().getFieldCollations()) {
            sortFields.add(fieldCollation.getFieldIndex());
        }
        List<Integer> stringKeys = stringFields(input, sortFields);
        if (stringKeys.isEmpty()) {
            return sort;
        }
        RelBuilder builder = RelFactories.LOGICAL_BUILDER.create(sort.getCluster(), null);
        builder.push(input);
        Map<Integer, Integer> normalizedIndex = appendNormalized(builder, input, stringKeys, normalizer);

        List<RelFieldCollation> collations = new ArrayList<>();
        for (RelFieldCollation fieldCollation : sort.getCollation().getFieldCollations()) {
            Integer target = normalizedIndex.get(fieldCollation.getFieldIndex());
            collations.add(target == null ? fieldCollation : fieldCollation.withFieldIndex(target));
        }
        RelCollation collation = RelCollations.of(collations);
        RelNode sorted = LogicalSort.create(builder.build(), collation, sort.offset, sort.fetch);
        builder.push(sorted);
        List<RexNode> originalFields = new ArrayList<>();
        for (int i = 0; i < input.getRowType().getFieldCount(); i++) {
            originalFields.add(builder.field(i));
        }
        return builder.project(originalFields, input.getRowType().getFieldNames(), true).build();
    }

    private static Map<Integer, Integer> appendNormalized(RelBuilder builder, RelNode input, List<Integer> fields,
                                                          UnaryOperator<RexNode> normalizer) {
        int fieldCount = input.getRowType().getFieldCount();
        List<RexNode> projects = new ArrayList<>(builder.fields());
        List<String> names = new ArrayList<>(input.getRowType().getFieldNames());
        Map<Integer, Integer> normalizedIndex = new LinkedHashMap<>();
        for (int field : fields) {
            normalizedIndex.put(field, fieldCount + normalizedIndex.size());
            projects.add(normalizer.apply(builder.field(field)));
            names.add(NORMALIZED_PREFIX + field);
        }
        builder.project(projects, names, true);
        return normalizedIndex;
    }

    /**
     * 分组后投影回原结构：字符串键取 ANY_VALUE 列，类型对齐原聚合输出（含可空性）。
     *
     * @param builder    栈顶为新聚合的构造器
     * @param original   原聚合
     * @param newKeys    新分组键（与原分组键一一对应）
     * @param stringKeys 被归一化的原字符串分组键
     * @return 与原聚合输出结构一致的关系表达式
     */
    private static RelNode projectBack(RelBuilder builder, Aggregate original, List<Integer> newKeys,
                                       List<Integer> stringKeys) {
        List<Integer> sortedKeys = new ArrayList<>(ImmutableBitSet.of(newKeys).asList());
        int aggStart = sortedKeys.size();
        int anyValueStart = aggStart + original.getAggCallList().size();
        RexBuilder rexBuilder = original.getCluster().getRexBuilder();
        List<RelDataTypeField> originalFields = original.getRowType().getFieldList();

        List<RexNode> outputs = new ArrayList<>();
        int keyOrdinal = 0;
        for (int key : original.getGroupSet()) {
            int stringOrdinal = stringKeys.indexOf(key);
            int position = stringOrdinal >= 0 ? anyValueStart + stringOrdinal
                    : sortedKeys.indexOf(newKeys.get(keyOrdinal));
            outputs.add(castTo(rexBuilder, builder.field(position), originalFields.get(keyOrdinal).getType()));
            keyOrdinal++;
        }
        for (int i = 0; i < original.getAggCallList().size(); i++) {
            outputs.add(castTo(rexBuilder, builder.field(aggStart + i), originalFields.get(keyOrdinal + i).getType()));
        }
        return builder.project(outputs, original.getRowType().getFieldNames(), true).build();
    }

    private static RexNode castTo(RexBuilder rexBuilder, RexNode node, RelDataType type) {
        return node.getType().equals(type) ? node : rexBuilder.makeCast(type, node, true, false);
    }

    private static AggregateCall remapDistinctArgs(AggregateCall call, Map<Integer, Integer> normalizedIndex) {
        if (!call.isDistinct()) {
            return call;
        }
        List<Integer> args = new ArrayList<>();
        for (int arg : call.getArgList()) {
            args.add(normalizedIndex.getOrDefault(arg, arg));
        }
        return call.withArgList(args);
    }

    private static RelBuilder.AggCall argMinMax(RelBuilder builder, AggregateCall call,
                                                Map<Integer, Integer> normalizedIndex) {
        int arg = call.getArgList().get(0);
        boolean isMin = call.getAggregation().getKind() == SqlKind.MIN;
        RelBuilder.AggCall argCall = builder.aggregateCall(isMin ? SqlStdOperatorTable.ARG_MIN
                : SqlStdOperatorTable.ARG_MAX, builder.field(arg), builder.field(normalizedIndex.get(arg)));
        RelBuilder.AggCall filtered = call.filterArg >= 0 ? argCall.filter(builder.field(call.filterArg)) : argCall;
        return filtered.as(call.getName());
    }

    private static boolean isStringMinMax(AggregateCall call, RelNode input) {
        SqlKind kind = call.getAggregation().getKind();
        boolean isMinMax = (kind == SqlKind.MIN || kind == SqlKind.MAX) && call.getArgList().size() == 1;
        return isMinMax && !stringFields(input, call.getArgList()).isEmpty();
    }

    private static List<Integer> stringMinMaxArgs(Aggregate aggregate, RelNode input) {
        List<Integer> args = new ArrayList<>();
        for (AggregateCall call : aggregate.getAggCallList()) {
            if (isStringMinMax(call, input)) {
                args.addAll(call.getArgList());
            }
        }
        return args;
    }

    private static List<Integer> stringDistinctArgs(Aggregate aggregate, RelNode input) {
        List<Integer> args = new ArrayList<>();
        for (AggregateCall call : aggregate.getAggCallList()) {
            if (call.isDistinct()) {
                args.addAll(stringFields(input, call.getArgList()));
            }
        }
        return args;
    }

    private static List<Integer> stringFields(RelNode input, List<Integer> fields) {
        List<Integer> strings = new ArrayList<>();
        for (int field : fields) {
            RexNode ref = RexInputRef.of(field, input.getRowType());
            if (CaseInsensitiveRewriter.isString(ref) && !strings.contains(field)) {
                strings.add(field);
            }
        }
        return strings;
    }

    private static List<Integer> union(List<Integer> first, List<Integer> second) {
        List<Integer> merged = new ArrayList<>(first);
        for (int value : second) {
            if (!merged.contains(value)) {
                merged.add(value);
            }
        }
        return merged;
    }
}
