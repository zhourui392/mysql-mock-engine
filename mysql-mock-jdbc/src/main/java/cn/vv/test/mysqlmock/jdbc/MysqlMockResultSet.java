package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.QueryResult;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collections;
import java.util.Map;

/**
 * 物化结果集：游标在物化行上滚动；getter 按存储表示做宽松转换（MySQL 隐式转换子集）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MysqlMockResultSet implements ResultSet {

    static final QueryResult EMPTY = new QueryResult(
            Collections.<String>emptyList(), Collections.<Integer>emptyList(),
            Collections.<Object[]>emptyList());

    private final QueryResult result;
    private int cursor = -1;
    private boolean wasNull;
    private boolean closed;

    MysqlMockResultSet(QueryResult result) {
        this.result = result;
    }

    @Override
    public boolean next() throws java.sql.SQLException {
        ensureOpen();
        if (cursor + 1 < result.rowCount()) {
            cursor++;
            return true;
        }
        cursor = result.rowCount();
        return false;
    }

    @Override
    public void close() throws java.sql.SQLException {
        closed = true;
    }

    @Override
    public boolean wasNull() throws java.sql.SQLException {
        return wasNull;
    }

    @Override
    public Object getObject(int columnIndex) throws java.sql.SQLException {
        return value(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws java.sql.SQLException {
        return value(findColumn(columnLabel));
    }

    @Override
    public String getString(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public String getString(String columnLabel) throws java.sql.SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() != 0L;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return text.equals("1") || text.equals("true") || text.equals("t")
                || text.equals("y") || text.equals("yes") || text.equals("on");
    }

    @Override
    public boolean getBoolean(String columnLabel) throws java.sql.SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(int columnIndex) throws java.sql.SQLException {
        return (byte) getLong(columnIndex);
    }

    @Override
    public byte getByte(String columnLabel) throws java.sql.SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(int columnIndex) throws java.sql.SQLException {
        return (short) getLong(columnIndex);
    }

    @Override
    public short getShort(String columnLabel) throws java.sql.SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws java.sql.SQLException {
        return (int) getLong(columnIndex);
    }

    @Override
    public int getInt(String columnLabel) throws java.sql.SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1L : 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLeadingNumber(String.valueOf(value));
    }

    @Override
    public long getLong(String columnLabel) throws java.sql.SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(int columnIndex) throws java.sql.SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public float getFloat(String columnLabel) throws java.sql.SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return parseLeadingNumber(String.valueOf(value));
    }

    @Override
    public double getDouble(String columnLabel) throws java.sql.SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws java.sql.SQLException {
        BigDecimal decimal = getBigDecimal(columnIndex);
        return decimal == null ? null : decimal.setScale(scale, BigDecimal.ROUND_HALF_UP);
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws java.sql.SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws java.sql.SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public Date getDate(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof java.util.Date) {
            return new Date(((java.util.Date) value).getTime());
        }
        if (value instanceof Number) {
            return new Date(((Number) value).longValue());
        }
        return Date.valueOf(String.valueOf(value).trim());
    }

    @Override
    public Date getDate(String columnLabel) throws java.sql.SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws java.sql.SQLException {
        return getDate(columnIndex);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws java.sql.SQLException {
        return getDate(columnLabel);
    }

    @Override
    public Time getTime(int columnIndex) throws java.sql.SQLException {
        Timestamp timestamp = getTimestamp(columnIndex);
        return timestamp == null ? null : new Time(timestamp.getTime());
    }

    @Override
    public Time getTime(String columnLabel) throws java.sql.SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws java.sql.SQLException {
        return getTime(columnIndex);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws java.sql.SQLException {
        return getTime(columnLabel);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        if (value instanceof Number) {
            return new Timestamp(((Number) value).longValue());
        }
        return Timestamp.valueOf(String.valueOf(value).trim());
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws java.sql.SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws java.sql.SQLException {
        return getTimestamp(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws java.sql.SQLException {
        return getTimestamp(columnLabel);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        return String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws java.sql.SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public int findColumn(String columnLabel) throws java.sql.SQLException {
        ensureOpen();
        String wanted = normalizeLabel(columnLabel);
        java.util.List<String> names = result.columnNames();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(wanted)) {
                return i + 1;
            }
        }
        throw SqlExceptions.of("Unknown column '" + columnLabel + "'");
    }

    @Override
    public ResultSetMetaData getMetaData() throws java.sql.SQLException {
        return new MysqlMockResultSetMetaData(result);
    }

    @Override
    public int getRow() throws java.sql.SQLException {
        return cursor + 1;
    }

    @Override
    public boolean isBeforeFirst() throws java.sql.SQLException {
        return cursor == -1 && result.rowCount() > 0;
    }

    @Override
    public boolean isAfterLast() throws java.sql.SQLException {
        return result.rowCount() > 0 && cursor >= result.rowCount();
    }

    @Override
    public boolean isFirst() throws java.sql.SQLException {
        return cursor == 0;
    }

    @Override
    public boolean isLast() throws java.sql.SQLException {
        return cursor == result.rowCount() - 1;
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return closed;
    }

    @Override
    public String getCursorName() throws java.sql.SQLException {
        return null;
    }

    @Override
    public Statement getStatement() throws java.sql.SQLException {
        return null;
    }

    @Override
    public SQLWarning getWarnings() throws java.sql.SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        // 无告警
    }

    @Override
    public int getType() throws java.sql.SQLException {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws java.sql.SQLException {
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        return HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public int getFetchDirection() throws java.sql.SQLException {
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws java.sql.SQLException {
        // 仅向前
    }

    @Override
    public int getFetchSize() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public void setFetchSize(int rows) throws java.sql.SQLException {
        // 全量物化
    }

    @Override
    public Object getObject(int columnIndex, java.util.Map<String, Class<?>> map) throws java.sql.SQLException {
        return getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel, java.util.Map<String, Class<?>> map) throws java.sql.SQLException {
        return getObject(columnLabel);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws java.sql.SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw SqlExceptions.of("无法转换为 " + type.getName() + ": " + value.getClass().getName());
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws java.sql.SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("ResultSet 不实现 " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
        return iface.isInstance(this);
    }

    // ---- 未支持的定位/更新类能力 ----

    @Override
    public boolean previous() throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public boolean absolute(int row) throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public boolean relative(int rows) throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public boolean first() throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public boolean last() throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public void beforeFirst() throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public void afterLast() throws java.sql.SQLException {
        throw SqlExceptions.of("仅支持向前游标");
    }

    @Override
    public boolean rowUpdated() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean rowInserted() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean rowDeleted() throws java.sql.SQLException {
        return false;
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNull(int columnIndex) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void insertRow() throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateRow() throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void deleteRow() throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void refreshRow() throws java.sql.SQLException {
        // 物化结果无需刷新
    }

    @Override
    public void cancelRowUpdates() throws java.sql.SQLException {
        // 只读
    }

    @Override
    public void moveToInsertRow() throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void moveToCurrentRow() throws java.sql.SQLException {
        // 只读
    }

    @Override
    public URL getURL(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public URL getURL(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Array getArray(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Array getArray(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Blob getBlob(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Blob getBlob(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Clob getClob(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Clob getClob(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public NClob getNClob(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public NClob getNClob(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Ref getRef(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Ref getRef(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public RowId getRowId(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public RowId getRowId(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public String getNString(int columnIndex) throws java.sql.SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws java.sql.SQLException {
        return getString(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Deprecated
    @Override
    public InputStream getUnicodeStream(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Deprecated
    @Override
    public InputStream getUnicodeStream(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws java.sql.SQLException {
        return null;
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws java.sql.SQLException {
        return null;
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateInt(int columnIndex, int x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateInt(String columnLabel, int x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateLong(int columnIndex, long x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateLong(String columnLabel, long x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNClob(int columnIndex, NClob clob) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNClob(String columnLabel, NClob clob) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateNull(String columnLabel) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateShort(int columnIndex, short x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateShort(String columnLabel, short x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateArray(int columnIndex, java.sql.Array x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateArray(String columnLabel, java.sql.Array x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateString(int columnIndex, String x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateString(String columnLabel, String x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws java.sql.SQLException {
        throw SqlExceptions.of("只读结果集");
    }


    // ---- 私有 ----

    private Object value(int columnIndex) throws java.sql.SQLException {
        ensureOpen();
        int index = columnIndex - 1;
        if (index < 0 || index >= result.columnNames().size()) {
            throw SqlExceptions.of("列下标越界: " + columnIndex);
        }
        if (cursor < 0 || cursor >= result.rowCount()) {
            throw SqlExceptions.of("游标不在有效行上（请先 next）");
        }
        Object value = result.rows().get(cursor)[index];
        wasNull = value == null;
        return value;
    }

    private static String normalizeLabel(String columnLabel) {
        int dot = columnLabel.lastIndexOf('.');
        return dot >= 0 ? columnLabel.substring(dot + 1) : columnLabel;
    }

    /** MySQL 隐式数值转换：取前导数字（如 '42abc' → 42）。 */
    private static long parseLeadingNumber(String text) {
        String trimmed = text.trim();
        int end = 0;
        boolean seenDigit = false;
        boolean seenDot = false;
        if (end < trimmed.length() && (trimmed.charAt(end) == '-' || trimmed.charAt(end) == '+')) {
            end++;
        }
        while (end < trimmed.length()) {
            char c = trimmed.charAt(end);
            if (c >= '0' && c <= '9') {
                seenDigit = true;
                end++;
            } else if (c == '.' && !seenDot) {
                seenDot = true;
                end++;
            } else {
                break;
            }
        }
        if (!seenDigit) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(trimmed.substring(0, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void ensureOpen() throws java.sql.SQLException {
        if (closed) {
            throw SqlExceptions.of("结果集已关闭");
        }
    }
}
