package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.TableDefinition;
import cn.vv.test.mysqlmock.storage.CollationNormalizer;
import cn.vv.test.mysqlmock.storage.GeneratedColumnEvaluator;
import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.parser.SqlParser;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * STORED 生成列求值：表达式经 Calcite SqlParser 解析（Lex.MYSQL），树解释执行。
 * 白名单：IF、=、&lt;&gt;、比较、IS [NOT] NULL、AND、OR、NOT、一元负号、四则、字面量与列引用（D-13）。
 */
public final class CalciteGeneratedColumnEvaluator implements GeneratedColumnEvaluator {

    @Override
    public void validate(TableDefinition table, ColumnDefinition column) {
        if (!column.generated() || column.generatedExpr() == null) {
            return;
        }
        SqlNode node = parse(column.generatedExpr(), table, column);
        checkWhitelist(node, table, column);
    }

    @Override
    public Object evaluate(TableDefinition table, ColumnDefinition column, Object[] currentValues) {
        SqlNode node = parse(column.generatedExpr(), table, column);
        return eval(node, table, currentValues);
    }

    private SqlNode parse(String expr, TableDefinition table, ColumnDefinition column) {
        try {
            SqlParser parser = SqlParser.create(expr,
                    SqlParser.config().withLex(Lex.MYSQL).withCaseSensitive(false));
            return parser.parseExpression();
        } catch (Exception e) {
            throw MysqlMockException.parseError("生成列表达式无法解析（表 " + table.name()
                    + " 列 " + column.name() + "）: " + expr + "（" + e.getMessage() + "）");
        }
    }

    private void checkWhitelist(SqlNode node, TableDefinition table, ColumnDefinition column) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlLiteral || node instanceof SqlDynamicParam) {
            return;
        }
        if (node instanceof SqlIdentifier) {
            String name = ((SqlIdentifier) node).getSimple().toLowerCase();
            if (table.columnIndex(name) < 0) {
                throw MysqlMockException.unsupported("生成列表达式引用了未知列（表 " + table.name()
                        + " 列 " + column.name() + "）: " + name);
            }
            return;
        }
        if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            SqlOperator operator = call.getOperator();
            if (!isWhitelisted(operator)) {
                throw MysqlMockException.unsupported("生成列表达式含白名单外函数/运算符（表 " + table.name()
                        + " 列 " + column.name() + "，D-13 仅 IF/比较/逻辑/算术）: " + operator.getName());
            }
            for (SqlNode operand : call.getOperandList()) {
                checkWhitelist(operand, table, column);
            }
            return;
        }
        throw MysqlMockException.unsupported("生成列表达式含不支持的节点（表 " + table.name()
                + " 列 " + column.name() + "）: " + node.getClass().getSimpleName());
    }

    private boolean isWhitelisted(SqlOperator operator) {
        SqlKind kind = operator.getKind();
        switch (kind) {
            case OTHER:
                return operator.getName().equalsIgnoreCase("IF");
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
            case AND:
            case OR:
            case IS_NULL:
            case IS_NOT_NULL:
            case NOT:
            case PLUS:
            case MINUS:
            case TIMES:
            case DIVIDE:
            case NEGATE:
                return true;
            default:
                return false;
        }
    }

    private Object eval(SqlNode node, TableDefinition table, Object[] currentValues) {
        if (node == null) {
            return null;
        }
        if (node instanceof SqlLiteral) {
            Object value = ((SqlLiteral) node).getValue();
            return value instanceof java.math.BigInteger || value instanceof Integer
                    ? ((Number) value).longValue()
                    : value;
        }
        if (node instanceof SqlDynamicParam) {
            throw MysqlMockException.unsupported("生成列表达式不支持动态参数");
        }
        if (node instanceof SqlIdentifier) {
            int index = table.columnIndex(((SqlIdentifier) node).getSimple().toLowerCase());
            return currentValues[index];
        }
        if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            String name = call.getOperator().getName().toUpperCase(Locale.ROOT);
            switch (call.getOperator().getKind()) {
                case OTHER:
                    if (name.equals("IF") && call.operandCount() == 3) {
                        Object condition = eval(call.getOperandList().get(0), table, currentValues);
                        return isTruthy(condition)
                                ? eval(call.getOperandList().get(1), table, currentValues)
                                : eval(call.getOperandList().get(2), table, currentValues);
                    }
                    break;
                case EQUALS:
                    return compareNode(call, table, currentValues) == null ? null
                            : compareNode(call, table, currentValues) == 0;
                case NOT_EQUALS:
                    Integer cmp = compareNode(call, table, currentValues);
                    return cmp == null ? null : cmp != 0;
                case LESS_THAN:
                    return sign(call, table, currentValues) < 0;
                case LESS_THAN_OR_EQUAL:
                    return sign(call, table, currentValues) <= 0;
                case GREATER_THAN:
                    return sign(call, table, currentValues) > 0;
                case GREATER_THAN_OR_EQUAL:
                    return sign(call, table, currentValues) >= 0;
                case IS_NULL:
                    return eval(call.getOperandList().get(0), table, currentValues) == null;
                case IS_NOT_NULL:
                    return eval(call.getOperandList().get(0), table, currentValues) != null;
                case AND:
                    return evalLogic(call, table, currentValues, true);
                case OR:
                    return evalLogic(call, table, currentValues, false);
                case NOT:
                    Object operand = eval(call.getOperandList().get(0), table, currentValues);
                    return operand == null ? null : !isTruthy(operand);
                case PLUS:
                case MINUS:
                case TIMES:
                case DIVIDE:
                    return evalArithmetic(call, table, currentValues);
                case NEGATE:
                    Object value = eval(call.getOperandList().get(0), table, currentValues);
                    if (value == null) {
                        return null;
                    }
                    return value instanceof BigDecimal ? ((BigDecimal) value).negate() : -((Number) value).longValue();
                default:
                    break;
            }
        }
        throw MysqlMockException.unsupported("生成列表达式节点不可求值: " + node.getClass().getSimpleName());
    }

    private Integer sign(SqlCall call, TableDefinition table, Object[] values) {
        Integer cmp = compareNode(call, table, values);
        return cmp == null ? Integer.MAX_VALUE : cmp;
    }

    private Integer compareNode(SqlCall call, TableDefinition table, Object[] values) {
        Object left = eval(call.getOperandList().get(0), table, values);
        Object right = eval(call.getOperandList().get(1), table, values);
        if (left == null || right == null) {
            return null;
        }
        return CollationNormalizer.compareNormalized(left, right);
    }

    private Boolean evalLogic(SqlCall call, TableDefinition table, Object[] values, boolean and) {
        Boolean result = and ? Boolean.TRUE : Boolean.FALSE;
        boolean sawNull = false;
        for (SqlNode operand : call.getOperandList()) {
            Object value = eval(operand, table, values);
            if (value == null) {
                sawNull = true;
                continue;
            }
            boolean truthy = isTruthy(value);
            if (and && !truthy) {
                return Boolean.FALSE;
            }
            if (!and && truthy) {
                return Boolean.TRUE;
            }
        }
        if (sawNull) {
            return null;
        }
        return result;
    }

    private Object evalArithmetic(SqlCall call, TableDefinition table, Object[] values) {
        Object left = eval(call.getOperandList().get(0), table, values);
        Object right = eval(call.getOperandList().get(1), table, values);
        if (left == null || right == null) {
            return null;
        }
        boolean decimal = left instanceof BigDecimal || right instanceof BigDecimal
                || left instanceof Double || right instanceof Double;
        if (decimal) {
            BigDecimal l = toBigDecimal(left);
            BigDecimal r = toBigDecimal(right);
            switch (call.getOperator().getKind()) {
                case PLUS:
                    return l.add(r);
                case MINUS:
                    return l.subtract(r);
                case TIMES:
                    return l.multiply(r);
                case DIVIDE:
                    return r.signum() == 0 ? null : l.divide(r, 10, java.math.RoundingMode.HALF_UP);
                default:
                    return null;
            }
        }
        long l = ((Number) left).longValue();
        long r = ((Number) right).longValue();
        switch (call.getOperator().getKind()) {
            case PLUS:
                return l + r;
            case MINUS:
                return l - r;
            case TIMES:
                return l * r;
            case DIVIDE:
                return r == 0 ? null : l / r;
            default:
                return null;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal ? (BigDecimal) value
                : value instanceof Number ? BigDecimal.valueOf(((Number) value).doubleValue())
                : new BigDecimal(String.valueOf(value));
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() != 0;
        }
        return true;
    }
}
