package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.UpdateResult;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * PreparedStatement：? 参数数组 + Batch 快照；值直达引擎（按列类型二次转换）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MysqlMockPreparedStatement extends MysqlMockStatement implements PreparedStatement {

    private final String sql;
    private final boolean returnGeneratedKeys;
    private Object[] parameters = new Object[8];
    private int maxSetIndex;
    private final List<Object[]> batchSnapshots = new ArrayList<>();

    MysqlMockPreparedStatement(MysqlMockConnection connection, String sql, boolean returnGeneratedKeys) {
        super(connection);
        this.sql = sql;
        this.returnGeneratedKeys = returnGeneratedKeys;
    }

    @Override
    public ResultSet executeQuery() throws java.sql.SQLException {
        ensureOpen();
        QueryResult result = runQuery(sql, boundParameters());
        return new MysqlMockResultSet(result);
    }

    @Override
    public int executeUpdate() throws java.sql.SQLException {
        ensureOpen();
        return executeUpdateInternal();
    }

    @Override
    public boolean execute() throws java.sql.SQLException {
        ensureOpen();
        if (isQuery(sql)) {
            QueryResult result = runQuery(sql, boundParameters());
            currentResultSet = new MysqlMockResultSet(result);
            updateCount = null;
            return true;
        }
        int affected = executeUpdateInternal();
        currentResultSet = null;
        updateCount = affected;
        return false;
    }

    @Override
    public void addBatch() throws java.sql.SQLException {
        ensureOpen();
        batchSnapshots.add(boundParameters().clone());
    }

    @Override
    public void clearBatch() throws java.sql.SQLException {
        batchSnapshots.clear();
    }

    @Override
    public int[] executeBatch() throws java.sql.SQLException {
        ensureOpen();
        int[] results = new int[batchSnapshots.size()];
        for (int i = 0; i < results.length; i++) {
            Object[] snapshot = batchSnapshots.get(i);
            UpdateResult result;
            try {
                result = connection.engine().execute(sql, snapshot);
            } catch (RuntimeException e) {
                throw SqlExceptions.wrap(e);
            }
            recordGeneratedKeys(result);
            results[i] = result.affectedRows();
        }
        batchSnapshots.clear();
        return results;
    }

    @Override
    public void clearParameters() throws java.sql.SQLException {
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = null;
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws java.sql.SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws java.sql.SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws java.sql.SQLException {
        set(parameterIndex, (long) x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws java.sql.SQLException {
        set(parameterIndex, (long) x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws java.sql.SQLException {
        set(parameterIndex, (long) x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws java.sql.SQLException {
        set(parameterIndex, (double) x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws java.sql.SQLException {
        set(parameterIndex, x == null ? null : java.util.Base64.getEncoder().encodeToString(x));
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws java.sql.SQLException {
        set(parameterIndex, x == null ? null : new Timestamp(x.getTime()));
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws java.sql.SQLException {
        setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws java.sql.SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持流式参数，请用 setString/setBytes");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws java.sql.SQLException {
        setAsciiStream(parameterIndex, x, (int) length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws java.sql.SQLException {
        setAsciiStream(parameterIndex, x, -1);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws java.sql.SQLException {
        setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持流式参数，请用 setBytes");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws java.sql.SQLException {
        setBinaryStream(parameterIndex, x, (int) length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws java.sql.SQLException {
        setBinaryStream(parameterIndex, x, -1);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持流式参数，请用 setString");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, reader, (int) length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, reader, -1);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, value, (int) length);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, value, -1);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 Ref");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 Blob，请用 setBytes");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持流式参数，请用 setBytes");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持流式参数，请用 setBytes");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 Clob，请用 setString");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, reader, (int) length);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, reader, -1);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 NClob，请用 setString");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, reader, (int) length);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws java.sql.SQLException {
        setCharacterStream(parameterIndex, reader, -1);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 Array");
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws java.sql.SQLException {
        set(parameterIndex, x == null ? null : x.toString());
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 SQLXML");
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws java.sql.SQLException {
        throw SqlExceptions.of("mysql-mock 不支持 RowId");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws java.sql.SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws java.sql.SQLException {
        return null;
    }

    @Override
    public ResultSetMetaData getMetaData() throws java.sql.SQLException {
        return null;
    }

    private int executeUpdateInternal() throws java.sql.SQLException {
        UpdateResult result;
        try {
            result = connection.engine().execute(sql, boundParameters());
        } catch (RuntimeException e) {
            throw SqlExceptions.wrap(e);
        }
        if (returnGeneratedKeys || !result.generatedKeys().isEmpty()) {
            recordGeneratedKeys(result);
        }
        return result.affectedRows();
    }

    private void recordGeneratedKeys(UpdateResult result) throws java.sql.SQLException {
        if (result.generatedKeys().isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        names.add("GENERATED_KEY");
        List<Integer> types = new ArrayList<>();
        types.add(java.sql.Types.BIGINT);
        List<Object[]> rows = new ArrayList<>();
        for (Object key : result.generatedKeys()) {
            rows.add(new Object[]{key});
        }
        generatedKeys = new MysqlMockResultSet(new QueryResult(names, types, rows));
    }

    private void set(int parameterIndex, Object value) throws java.sql.SQLException {
        ensureOpen();
        int index = parameterIndex - 1;
        if (index < 0) {
            throw SqlExceptions.of("参数下标从 1 开始: " + parameterIndex);
        }
        maxSetIndex = Math.max(maxSetIndex, index + 1);
        if (index >= parameters.length) {
            Object[] grown = new Object[Math.max(index + 1, parameters.length * 2)];
            System.arraycopy(parameters, 0, grown, 0, parameters.length);
            parameters = grown;
        }
        parameters[index] = value;
    }

    /** 截断到最高已设置下标（setNull 也计数；未设视为缺参，由引擎 strict 拒绝）。 */
    private Object[] boundParameters() {
        Object[] bound = new Object[maxSetIndex];
        System.arraycopy(parameters, 0, bound, 0, maxSetIndex);
        return bound;
    }
}
