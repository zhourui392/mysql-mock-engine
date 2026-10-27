package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.MysqlType;
import cn.vv.test.mysqlmock.schema.SchemaCatalog;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.util.Holder;
import org.apache.calcite.schema.SchemaPlus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calcite 查询引擎：连接 lex=MYSQL、fun=all、conformance=MYSQL_5、defaultNullCollation=low
 * （升序 NULL 在前，同 MySQL）；双 Schema——db（用户查询）
 * 与 internal（DML 定位用，含 __rowid）。SELECT 执行与表达式求值全部经 Calcite（D-03）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class CalciteQueryEngine {

    /**
     * 先展开子查询（IN 子查询变成 JOIN 条件后才能被改写），再做 _ci 改写，最后走 Calcite 标准优化。
     */
    private static final Program SUB_QUERY_PROGRAM = Programs.subQuery(DefaultRelMetadataProvider.INSTANCE);

    private static final Program STANDARD_PROGRAM = Programs.standard();

    private static final Program CASE_INSENSITIVE_PROGRAM =
            (planner, rel, requiredOutputTraits, materializations, lattices) -> {
                RelNode expanded = SUB_QUERY_PROGRAM.run(planner, rel, requiredOutputTraits, materializations,
                        lattices);
                CaseInsensitiveRewriter rewriter = new CaseInsensitiveRewriter(rel.getCluster().getRexBuilder());
                RelNode rewritten = expanded.accept(rewriter);
                RelTraitSet traits = rewriter.hasRewrittenSort()
                        ? requiredOutputTraits.replace(RelCollations.EMPTY) : requiredOutputTraits;
                return STANDARD_PROGRAM.run(planner, rewritten, traits, materializations, lattices);
            };

    private final CalciteConnection connection;

    private final SchemaCatalog catalog;

    CalciteQueryEngine(SchemaCatalog catalog, Map<String, MemoryTable> tables) {
        this.catalog = catalog;
        try {
            Connection raw = DriverManager.getConnection(
                    "jdbc:calcite:lex=MYSQL;fun=all;conformance=MYSQL_5;charset=UTF-8;defaultNullCollation=low");
            connection = raw.unwrap(CalciteConnection.class);
            SchemaPlus root = connection.getRootSchema();
            root.add("db", new MockCalciteSchema(catalog, tables, false));
            root.add("internal", new MockCalciteSchema(catalog, tables, true));
            connection.setSchema("db");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 Calcite 连接失败", e);
        }
    }

    /** 在 internal schema 上执行（DML 定位查询：SELECT __rowid, ...）。 */
    QueryResult queryInternal(String sql, Object... params) {
        return executeSelect(sql, params);
    }

    QueryResult executeSelect(String sql, Object... params) {
        // D-05：内存库无行锁；去掉末尾 FOR UPDATE 后按普通 SELECT 执行
        String executable = stripTrailingForUpdate(sql);
        synchronized (this) {
            try (Hook.Closeable ignored = Hook.PROGRAM.addThread(
                    (java.util.function.Consumer<Holder<Program>>) holder -> holder.set(CASE_INSENSITIVE_PROGRAM));
                 PreparedStatement statement = connection.prepareStatement(
                    SqlCharsetRewriter.introduceUtf8(executable))) {
                bindParams(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    return toQueryResult(rs);
                }
            } catch (SQLException e) {
                throw translate(e, sql);
            }
        }
    }

    /**
     * 去掉语句末尾的 {@code FOR UPDATE}（大小写不敏感，允许尾部空白与分号）。
     * 仅在词边界匹配，避免误伤列名/别名中的同名片段。
     */
    static String stripTrailingForUpdate(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        int end = sql.length();
        while (end > 0) {
            char c = sql.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ';') {
                end--;
            } else {
                break;
            }
        }
        final String lock = "FOR UPDATE";
        if (end >= lock.length()
                && sql.regionMatches(true, end - lock.length(), lock, 0, lock.length())) {
            int start = end - lock.length();
            if (start == 0 || Character.isWhitespace(sql.charAt(start - 1))) {
                return sql.substring(0, start).trim();
            }
        }
        return sql;
    }

    private QueryResult toQueryResult(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> names = new ArrayList<>(columnCount);
        List<Integer> types = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            names.add(meta.getColumnLabel(i).toLowerCase());
            types.add(columnType(meta, i));
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                row[i - 1] = normalize(rs.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResult(names, types, rows);
    }

    /**
     * 结果列的 JDBC 类型。直接引用 CHAR 列时报 CHAR：Calcite 侧按 VARCHAR(n) 建模（见 {@link MysqlTypeMapper}），
     * 元数据按原列类型回报。
     *
     * @param meta   结果集元数据
     * @param column 列序号（从 1 开始）
     * @return JDBC 类型
     * @throws SQLException 读取元数据失败
     */
    private int columnType(ResultSetMetaData meta, int column) throws SQLException {
        int type = meta.getColumnType(column);
        String tableName = meta.getTableName(column);
        if (type != Types.VARCHAR || tableName == null || !catalog.exists(tableName)) {
            return type;
        }
        ColumnDefinition definition = catalog.get(tableName).column(meta.getColumnName(column));
        return definition != null && definition.type() == MysqlType.CHAR ? Types.CHAR : type;
    }

    /** avatica 类型 → 存储表示归一（整型统一 Long、DECIMAL 保留 BigDecimal、DATE / TIME 保留原类型）。 */
    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof java.math.BigInteger) {
            return ((java.math.BigInteger) value).longValue();
        }
        if (value instanceof java.sql.Date || value instanceof java.sql.Time) {
            return value;
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) value).getTime());
        }
        return value;
    }

    private void bindParams(PreparedStatement statement, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        java.sql.ParameterMetaData meta = null;
        try {
            meta = statement.getParameterMetaData();
        } catch (SQLException ignore) {
            // 部分语句无参数元数据，退化为直绑
        }
        for (int i = 0; i < params.length; i++) {
            Object param = toStorageParam(params[i]);
            if (param == null) {
                statement.setNull(i + 1, Types.NULL);
                continue;
            }
            int type = meta != null && i < meta.getParameterCount() ? meta.getParameterType(i + 1) : Types.OTHER;
            bindByTargetType(statement, i + 1, param, type);
        }
    }

    /**
     * 按参数位置的目标类型绑定，避开 Calcite enumerable 侧的类型不匹配。
     *
     * @param statement 语句
     * @param index     参数下标（1 起）
     * @param param     已归一的参数
     * @param type      目标 JDBC 类型；未知时为 {@link Types#OTHER}
     * @throws SQLException 绑定失败
     */
    private static void bindByTargetType(PreparedStatement statement, int index, Object param, int type)
            throws SQLException {
        boolean isIntContext = type == Types.TINYINT || type == Types.SMALLINT || type == Types.INTEGER;
        if (param instanceof Long && isIntContext) {
            // LIMIT ?,? 等整型上下文：Long 直绑会在 enumerable 侧 CCE
            statement.setInt(index, (int) (long) (Long) param);
        } else if (param instanceof Integer && type == Types.BIGINT) {
            statement.setLong(index, ((Integer) param).longValue());
        } else if (!(param instanceof String) && type == Types.VARCHAR) {
            // CONCAT(?, ...) 等改写为 CAST(? AS VARCHAR)，数值参数按 MySQL 隐式转换成文本
            statement.setString(index, String.valueOf(param));
        } else {
            statement.setObject(index, param);
        }
    }

    /**
     * 参数与存储表示对齐：TINYINT(1) 存 Long，Boolean 直绑会在比较时 CCE；java.time 转 java.sql 类型，
     * Calcite 只认后者。MySQL 也把 TRUE / FALSE 当 1 / 0。
     *
     * @param param 调用方传入的参数
     * @return 可直接绑定的参数
     */
    private static Object toStorageParam(Object param) {
        if (param instanceof Boolean) {
            return (Boolean) param ? 1L : 0L;
        }
        if (param instanceof java.time.LocalDateTime) {
            return java.sql.Timestamp.valueOf((java.time.LocalDateTime) param);
        }
        if (param instanceof java.time.LocalDate) {
            return java.sql.Date.valueOf((java.time.LocalDate) param);
        }
        if (param instanceof java.time.LocalTime) {
            return java.sql.Time.valueOf((java.time.LocalTime) param);
        }
        return param;
    }

    private static boolean containsKeyword(String text, String keyword) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuotedText(text, i);
                continue;
            }
            if (text.regionMatches(true, i, keyword, 0, keyword.length())
                    && (i == 0 || !isWordChar(text.charAt(i - 1)))
                    && (i + keyword.length() >= n || !isWordChar(text.charAt(i + keyword.length())))) {
                return true;
            }
            i++;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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

    private static int matchingParen(String text, int openIndex) {
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

    /** SQL 失败 → strict 异常（R9：不静默）；遍历 cause 链取校验器/解析器的具体错误。 */
    MysqlMockException translate(SQLException e, String sql) {
        String detail = e.getMessage() == null ? "" : e.getMessage();
        Throwable cause = e.getCause();
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && (cause instanceof org.apache.calcite.sql.validate.SqlValidatorException
                    || cause instanceof org.apache.calcite.runtime.CalciteContextException
                    || cause instanceof org.apache.calcite.sql.parser.SqlParseException
                    || detail.isEmpty())) {
                detail = message;
            }
            cause = cause.getCause();
        }
        // Object 'x' not found → 表不存在（1146/42S02）
        java.util.regex.Matcher object = java.util.regex.Pattern
                .compile("Object '([^']+)' not found").matcher(detail);
        if (object.find()) {
            return MysqlMockException.tableNotExists(qualifierTail(object.group(1)));
        }
        if (detail.contains("No match found for function")
                || detail.contains("Cannot apply")
                || detail.contains("not supported")
                || detail.contains("Unknown identifier")
                || detail.contains("cannot be resolved")
                || detail.contains("No match found for")) {
            return MysqlMockException.unsupported(detail + "（SQL: " + sql + "）");
        }
        return MysqlMockException.parseError(detail + "（SQL: " + sql + "）", e);
    }

    /** 'db.no_such_table' → 'no_such_table'：报表面名取限定名末段。 */
    private String qualifierTail(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // 关闭失败忽略
        }
    }
}
