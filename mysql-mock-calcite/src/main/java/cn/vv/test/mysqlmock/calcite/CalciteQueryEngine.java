package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.schema.SchemaCatalog;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.ScalarFunction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calcite 查询引擎：连接 lex=MYSQL、fun=all；双 Schema——db（用户查询，无行 ID）
 * 与 internal（DML 定位用，含 __rowid）。SELECT 执行与表达式求值全部经 Calcite（D-03）。
 */
final class CalciteQueryEngine {

    private final CalciteConnection connection;

    CalciteQueryEngine(SchemaCatalog catalog, Map<String, MemoryTable> tables) {
        try {
            Connection raw = DriverManager.getConnection("jdbc:calcite:lex=MYSQL;fun=all");
            connection = raw.unwrap(CalciteConnection.class);
            SchemaPlus root = connection.getRootSchema();
            root.add("db", new MockCalciteSchema(catalog, tables, false));
            root.add("internal", new MockCalciteSchema(catalog, tables, true));
            connection.setSchema("db");
            registerUdfs(root);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 Calcite 连接失败", e);
        }
    }

    private void registerUdfs(SchemaPlus root) {
        root.add("NOW", ScalarFunction.create(MysqlUdf.class, "now"));
        root.add("CURDATE", ScalarFunction.create(MysqlUdf.class, "curdate"));
        root.add("DATE_FORMAT", ScalarFunction.create(MysqlUdf.class, "date_format"));
        root.add("IF", ScalarFunction.create(MysqlUdf.class, "if_"));
    }

    /** 在 internal schema 上执行（DML 定位查询：SELECT __rowid, ...）。 */
    QueryResult queryInternal(String sql, Object... params) {
        return executeSelect(sql, params);
    }

    QueryResult executeSelect(String sql, Object... params) {
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
        return value;
    }

    private void bindParams(PreparedStatement statement, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    /** SQL 失败 → strict 异常（R9：不静默）。 */
    MysqlMockException translate(SQLException e, String sql) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("No match found for function") || message.contains("Cannot apply")
                || message.contains("not supported") || message.contains("Unknown identifier")) {
            return MysqlMockException.unsupported(message + "（SQL: " + sql + "）");
        }
        return MysqlMockException.parseError(message + "（SQL: " + sql + "）");
    }

    void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // 关闭失败忽略
        }
    }
}
