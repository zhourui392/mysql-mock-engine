package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.schema.SchemaCatalog;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import org.apache.calcite.jdbc.CalciteConnection;
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
 * Calcite 查询引擎：连接 lex=MYSQL、fun=all、conformance=MYSQL_5；双 Schema——db（用户查询）
 * 与 internal（DML 定位用，含 __rowid）。SELECT 执行与表达式求值全部经 Calcite（D-03）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class CalciteQueryEngine {

    private final CalciteConnection connection;

    CalciteQueryEngine(SchemaCatalog catalog, Map<String, MemoryTable> tables) {
        try {
            Connection raw = DriverManager.getConnection(
                    "jdbc:calcite:lex=MYSQL;fun=all;conformance=MYSQL_5;charset=UTF-8");
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
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlCharsetRewriter.introduceUtf8(sql))) {
                bindParams(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    return toQueryResult(rs);
                }
            } catch (SQLException e) {
                throw translate(e, sql);
            }
        }
    }

    private QueryResult toQueryResult(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> names = new ArrayList<>(columnCount);
        List<Integer> types = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            names.add(meta.getColumnLabel(i).toLowerCase());
            types.add(meta.getColumnType(i));
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

    /** avatica 类型 → 存储表示归一（整型统一 Long、DECIMAL 保留 BigDecimal）。 */
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
        if (value instanceof java.sql.Date) {
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
            if (params[i] == null) {
                statement.setNull(i + 1, Types.NULL);
                continue;
            }
            if (meta != null && i < meta.getParameterCount()) {
                int type = meta.getParameterType(i + 1);
                if (params[i] instanceof Long
                        && (type == Types.TINYINT || type == Types.SMALLINT || type == Types.INTEGER)) {
                    // LIMIT ?,? 等整型上下文：Long 直绑会在 enumerable 侧 CCE
                    statement.setInt(i + 1, (int) (long) (Long) params[i]);
                    continue;
                }
                if (params[i] instanceof Integer && type == Types.BIGINT) {
                    statement.setLong(i + 1, ((Integer) params[i]).longValue());
                    continue;
                }
            }
            statement.setObject(i + 1, params[i]);
        }
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
