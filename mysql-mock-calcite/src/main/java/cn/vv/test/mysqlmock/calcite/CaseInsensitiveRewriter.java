package cn.vv.test.mysqlmock.calcite;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexFieldCollation;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexWindow;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlTrimFunction;
import org.apache.calcite.sql.type.SqlTypeUtil;

import com.google.common.collect.ImmutableList;

/**
 * 逻辑计划改写：让字符串比较按 utf8mb4_general_ci 语义执行（D-16，与唯一键侧 CollationNormalizer 同口径）。
 * <p>Calcite 只做二进制比较，列类型上的 collation 对等值、GROUP BY 不生效，对 LIKE 还会报类型不可比。
 * 这里在优化前改写计划：比较两侧、GROUP BY / DISTINCT 键、ORDER BY 键、窗口 PARTITION BY / ORDER BY 键、
 * UNION / INTERSECT / EXCEPT 去重键套上 {@code LOWER(RTRIM(x))}，字符串 MIN / MAX（含窗口内）改为按归一化值取原值；
 * LIKE 只套 {@code LOWER}（MySQL 的 LIKE 不忽略尾随空格）。投影结果不改，返回存储原值。
 * INTERSECT ALL / EXCEPT ALL 按归一化值计数。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class CaseInsensitiveRewriter extends RelHomogeneousShuttle {

    private final RexBuilder rexBuilder;

    private final ComparisonShuttle comparisonShuttle = new ComparisonShuttle();

    private boolean hasRewrittenSort;

    CaseInsensitiveRewriter(RexBuilder rexBuilder) {
        this.rexBuilder = rexBuilder;
    }

    @Override
    public RelNode visit(RelNode other) {
        RelNode rel = super.visit(other).accept(comparisonShuttle);
        boolean isDistinctSetOp = rel instanceof SetOp && !((SetOp) rel).all;
        if (isDistinctSetOp && rel instanceof Union) {
            return CaseInsensitiveRelRewrites.rewriteUnion((Union) rel, this::normalize);
        }
        if (rel instanceof Intersect || rel instanceof Minus) {
            return isDistinctSetOp ? CaseInsensitiveRelRewrites.rewriteIntersectOrMinus((SetOp) rel, this::normalize)
                    : CaseInsensitiveRelRewrites.rewriteIntersectOrMinusAll((SetOp) rel, this::normalize);
        }
        if (rel instanceof Aggregate) {
            return CaseInsensitiveRelRewrites.rewriteAggregate((Aggregate) rel, this::normalize);
        }
        if (rel instanceof Sort) {
            RelNode rewritten = CaseInsensitiveRelRewrites.rewriteSort((Sort) rel, this::normalize);
            hasRewrittenSort |= rewritten != rel;
            return rewritten;
        }
        return rel;
    }

    /**
     * 是否改写过排序键。改写过时，调用方要去掉根节点的必需排序特征：它按原列二进制排序，
     * 优化器会在顶层再排一次，覆盖改写后的顺序。
     *
     * @return 改写过排序时为 true
     */
    boolean hasRewrittenSort() {
        return hasRewrittenSort;
    }

    /**
     * 归一化表达式：去尾随空格后转小写。
     *
     * @param operand 字符串表达式
     * @return {@code LOWER(TRIM(TRAILING ' ' FROM operand))}
     */
    RexNode normalize(RexNode operand) {
        RexNode trimmed = rexBuilder.makeCall(SqlStdOperatorTable.TRIM,
                rexBuilder.makeFlag(SqlTrimFunction.Flag.TRAILING), rexBuilder.makeLiteral(" "), operand);
        return lower(trimmed);
    }

    private RexNode lower(RexNode operand) {
        return rexBuilder.makeCall(SqlStdOperatorTable.LOWER, operand);
    }

    static boolean isString(RexNode node) {
        return SqlTypeUtil.inCharFamily(node.getType());
    }

    private RexNode normalizeIfString(RexNode node) {
        return isString(node) ? normalize(node) : node;
    }

    /**
     * 改写比较与 LIKE；SEARCH（IN 列表、BETWEEN 化简后的形态）先展开再改写。
     *
     * @author zhourui
     * @since 2026-10-23
     */
    private final class ComparisonShuttle extends RexShuttle {

        @Override
        public RexNode visitCall(RexCall call) {
            if (call.getKind() == SqlKind.SEARCH && isString(call.getOperands().get(0))) {
                return RexUtil.expandSearch(rexBuilder, null, call).accept(this);
            }
            RexCall visited = (RexCall) super.visitCall(call);
            if (visited.getKind() == SqlKind.LIKE && isString(visited.getOperands().get(0))) {
                return rexBuilder.makeCall(visited.getOperator(), lowerFirstTwo(visited.getOperands()));
            }
            if (isStringComparison(visited)) {
                List<RexNode> operands = new ArrayList<>();
                for (RexNode operand : visited.getOperands()) {
                    operands.add(normalize(operand));
                }
                return rexBuilder.makeCall(visited.getOperator(), operands);
            }
            return visited;
        }

        /**
         * 窗口的 PARTITION BY 与 ORDER BY 键按归一化值分区、排序。
         *
         * @param window 原窗口
         * @return 改写后的窗口；没有字符串键时返回原窗口
         */
        @Override
        public RexWindow visitWindow(RexWindow window) {
            RexWindow visited = super.visitWindow(window);
            boolean hasStringKey = visited.partitionKeys.stream().anyMatch(CaseInsensitiveRewriter::isString)
                    || visited.orderKeys.stream().anyMatch(key -> isString(key.left));
            if (!hasStringKey) {
                return visited;
            }
            List<RexNode> partitionKeys = new ArrayList<>();
            visited.partitionKeys.forEach(key -> partitionKeys.add(normalizeIfString(key)));
            ImmutableList.Builder<RexFieldCollation> orderKeys = ImmutableList.builder();
            visited.orderKeys.forEach(key -> orderKeys.add(new RexFieldCollation(normalizeIfString(key.left),
                    key.right)));
            return rexBuilder.makeWindow(partitionKeys, orderKeys.build(), visited.getLowerBound(),
                    visited.getUpperBound(), visited.isRows(), visited.getExclude());
        }

        /**
         * 窗口内的字符串 MIN / MAX 改为 ARG_MIN / ARG_MAX：按归一化值比较，返回原值。
         *
         * @param over 窗口聚合
         * @return 改写后的窗口聚合
         */
        @Override
        public RexNode visitOver(RexOver over) {
            RexOver visited = (RexOver) super.visitOver(over);
            SqlKind kind = visited.getAggOperator().getKind();
            boolean isStringMinMax = (kind == SqlKind.MIN || kind == SqlKind.MAX)
                    && visited.getOperands().size() == 1 && isString(visited.getOperands().get(0));
            if (!isStringMinMax) {
                return visited;
            }
            RexNode operand = visited.getOperands().get(0);
            RexWindow window = visited.getWindow();
            return rexBuilder.makeOver(visited.getType(),
                    kind == SqlKind.MIN ? SqlStdOperatorTable.ARG_MIN : SqlStdOperatorTable.ARG_MAX,
                    ImmutableList.of(operand, normalize(operand)), window.partitionKeys, window.orderKeys,
                    window.getLowerBound(), window.getUpperBound(), window.getExclude(), window.isRows(), true, false,
                    visited.isDistinct(), visited.ignoreNulls());
        }

        private List<RexNode> lowerFirstTwo(List<RexNode> operands) {
            List<RexNode> lowered = new ArrayList<>(operands);
            lowered.set(0, lower(operands.get(0)));
            lowered.set(1, lower(operands.get(1)));
            return lowered;
        }

        private boolean isStringComparison(RexCall call) {
            boolean isComparison = SqlKind.COMPARISON.contains(call.getKind())
                    || call.getKind() == SqlKind.IS_DISTINCT_FROM
                    || call.getKind() == SqlKind.IS_NOT_DISTINCT_FROM;
            return isComparison && call.getOperands().size() == 2
                    && isString(call.getOperands().get(0)) && isString(call.getOperands().get(1));
        }
    }
}
