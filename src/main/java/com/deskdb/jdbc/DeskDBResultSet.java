package com.deskdb.jdbc;

import com.deskdb.core.Row;
import java.sql.*;
import java.util.List;
import java.util.Map;

/**
 * Implementación de ResultSet JDBC para DeskDB.
 */
public class DeskDBResultSet implements ResultSet {

    private final List<Row> rows;
    private final String[] columnNames;
    private int currentRow = -1;
    private boolean closed = false;
    private boolean wasNull = false;

    public DeskDBResultSet(List<Row> rows, String[] columnNames) {
        this.rows = rows;
        this.columnNames = columnNames != null ? columnNames : new String[0];
    }

    @Override
    public boolean next() throws SQLException {
        checkClosed();
        currentRow++;
        return currentRow < rows.size();
    }

    @Override
    public void close() throws SQLException {
        closed = true;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkClosed();
        return wasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return null;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return null;
        }
        
        wasNull = false;
        return value.toString();
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return false;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return false;
        }
        
        wasNull = false;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return 0;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return 0;
        }
        
        wasNull = false;
        if (value instanceof Number) {
            return ((Number) value).byteValue();
        }
        return Byte.parseByte(value.toString());
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return 0;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return 0;
        }
        
        wasNull = false;
        if (value instanceof Number) {
            return ((Number) value).shortValue();
        }
        return Short.parseShort(value.toString());
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return 0;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return 0;
        }
        
        wasNull = false;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return 0;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return 0;
        }
        
        wasNull = false;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return 0;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return 0;
        }
        
        wasNull = false;
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return Float.parseFloat(value.toString());
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return 0;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return 0;
        }
        
        wasNull = false;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkClosed();
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Column not found: " + columnLabel);
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkClosed();
        return null; // No tenemos referencia al statement
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return null;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        wasNull = (value == null);
        return value;
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public int getType() throws SQLException {
        checkClosed();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkClosed();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkClosed();
        return currentRow == -1 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkClosed();
        return currentRow >= rows.size();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkClosed();
        return currentRow == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkClosed();
        return currentRow == rows.size() - 1 && !rows.isEmpty();
    }

    @Override
    public void beforeFirst() throws SQLException {
        checkClosed();
        currentRow = -1;
    }

    @Override
    public void afterLast() throws SQLException {
        checkClosed();
        currentRow = rows.size();
    }

    @Override
    public boolean first() throws SQLException {
        checkClosed();
        if (rows.isEmpty()) {
            return false;
        }
        currentRow = 0;
        return true;
    }

    @Override
    public boolean last() throws SQLException {
        checkClosed();
        if (rows.isEmpty()) {
            return false;
        }
        currentRow = rows.size() - 1;
        return true;
    }

    @Override
    public int getRow() throws SQLException {
        checkClosed();
        if (currentRow < 0 || currentRow >= rows.size()) {
            return 0;
        }
        return currentRow + 1;
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        checkClosed();
        if (row == 0) {
            return false;
        }
        
        if (row > 0) {
            if (row > rows.size()) {
                currentRow = rows.size();
                return false;
            }
            currentRow = row - 1;
        } else {
            int absRow = rows.size() + row + 1;
            if (absRow <= 0) {
                currentRow = -1;
                return false;
            }
            currentRow = absRow - 1;
        }
        return true;
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        checkClosed();
        int newRow = currentRow + rows;
        
        if (newRow < 0) {
            currentRow = -1;
            return false;
        } else if (newRow >= this.rows.size()) {
            currentRow = this.rows.size();
            return false;
        }
        
        currentRow = newRow;
        return true;
    }

    @Override
    public boolean previous() throws SQLException {
        checkClosed();
        currentRow--;
        return currentRow >= 0;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkClosed();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD supported");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkClosed();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkClosed();
        // Ignorado
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkClosed();
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (isWrapperFor(iface)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
        }
        
        if (type.isInstance(obj)) {
            return type.cast(obj);
        }
        
        throw new SQLException("Cannot convert to " + type.getName());
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    // Métodos de actualización no soportados (ResultSet read-only)
    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBigDecimal(int columnIndex, java.math.BigDecimal x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateDate(int columnIndex, java.sql.Date x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateTime(int columnIndex, java.sql.Time x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateTimestamp(int columnIndex, java.sql.Timestamp x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateAsciiStream(int columnIndex, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBinaryStream(int columnIndex, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateCharacterStream(int columnIndex, java.io.Reader x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBigDecimal(String columnLabel, java.math.BigDecimal x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateDate(String columnLabel, java.sql.Date x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateTime(String columnLabel, java.sql.Time x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateTimestamp(String columnLabel, java.sql.Timestamp x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateAsciiStream(String columnLabel, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateAsciiStream(String columnLabel, java.io.InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBinaryStream(String columnLabel, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBinaryStream(String columnLabel, java.io.InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateCharacterStream(String columnLabel, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateCharacterStream(String columnLabel, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void insertRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void deleteRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void refreshRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateArray(int columnIndex, java.sql.Array x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateArray(String columnLabel, java.sql.Array x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNClob(int columnIndex, NClob nclob) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNClob(String columnLabel, NClob nclob) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNClob(int columnIndex, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNClob(String columnLabel, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNClob(int columnIndex, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNClob(String columnLabel, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNCharacterStream(int columnIndex, java.io.Reader x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNCharacterStream(String columnLabel, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateNCharacterStream(int columnIndex, java.io.Reader x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    @Override
    public void updateNCharacterStream(String columnLabel, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, java.io.Reader x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateAsciiStream(int columnIndex, java.io.InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateAsciiStream(int columnIndex, java.io.InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBinaryStream(int columnIndex, java.io.InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBinaryStream(int columnIndex, java.io.InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateCharacterStream(int columnIndex, java.io.Reader x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateCharacterStream(int columnIndex, java.io.Reader x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateAsciiStream(String columnLabel, java.io.InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBinaryStream(String columnLabel, java.io.InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateCharacterStream(String columnLabel, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateBlob(int columnIndex, java.io.InputStream inputStream, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateBlob(String columnLabel, java.io.InputStream inputStream, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateBlob(int columnIndex, java.io.InputStream inputStream) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateBlob(String columnLabel, java.io.InputStream inputStream) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateBlob(int columnIndex, java.io.OutputStream outputStream) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateBlob(String columnLabel, java.io.OutputStream outputStream) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBlob(int columnIndex, java.io.InputStream inputStream, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateBlob(String columnLabel, java.io.InputStream inputStream, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateClob(int columnIndex, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateClob(String columnLabel, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateClob(int columnIndex, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateClob(String columnLabel, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateClob(int columnIndex, java.io.Writer writer) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateClob(String columnLabel, java.io.Writer writer) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateArray(int columnIndex, java.sql.Array x, int offset, int len) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    
    public void updateArray(String columnLabel, java.sql.Array x, int offset, int len) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed");
        }
    }

    private void validateColumnIndex(int columnIndex) throws SQLException {
        if (columnIndex < 1 || columnIndex > columnNames.length) {
            throw new SQLException("Invalid column index: " + columnIndex);
        }
    }

    private Row getCurrentRow() {
        if (currentRow < 0 || currentRow >= rows.size()) {
            return null;
        }
        return rows.get(currentRow);
    }

    // Métodos faltantes requeridos por la interfaz ResultSet
    @Override
    public void updateClob(int columnIndex, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateClob(String columnLabel, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNClob(int columnIndex, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public void updateNClob(String columnLabel, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("ResultSet is read-only");
    }

    @Override
    public java.io.Reader getNCharacterStream(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.io.Reader getNCharacterStream(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        Row row = getCurrentRow();
        if (row == null || currentRow < 0 || currentRow >= rows.size()) {
            wasNull = true;
            return null;
        }
        Object value = row.get(columnNames[columnIndex - 1]);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        return value.toString();
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        checkClosed();
        int columnIndex = findColumn(columnLabel);
        return getNString(columnIndex);
    }

    @Override
    public java.sql.SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.NClob getNClob(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.NClob getNClob(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public int getHoldability() throws SQLException {
        checkClosed();
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public java.sql.RowId getRowId(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.RowId getRowId(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.net.URL getURL(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.net.URL getURL(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Timestamp getTimestamp(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof java.sql.Timestamp) {
            return (java.sql.Timestamp) value;
        }
        return java.sql.Timestamp.valueOf(value.toString());
    }

    @Override
    public java.sql.Timestamp getTimestamp(int columnIndex, java.util.Calendar cal) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof java.sql.Timestamp) {
            return (java.sql.Timestamp) value;
        }
        return java.sql.Timestamp.valueOf(value.toString());
    }

    @Override
    public java.sql.Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public java.sql.Timestamp getTimestamp(String columnLabel, java.util.Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public java.sql.Time getTime(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof java.sql.Time) {
            return (java.sql.Time) value;
        }
        return java.sql.Time.valueOf(value.toString());
    }

    @Override
    public java.sql.Time getTime(int columnIndex, java.util.Calendar cal) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof java.sql.Time) {
            return (java.sql.Time) value;
        }
        return java.sql.Time.valueOf(value.toString());
    }

    @Override
    public java.sql.Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public java.sql.Time getTime(String columnLabel, java.util.Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public java.sql.Date getDate(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof java.sql.Date) {
            return (java.sql.Date) value;
        }
        return java.sql.Date.valueOf(value.toString());
    }

    @Override
    public java.sql.Date getDate(int columnIndex, java.util.Calendar cal) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        String columnName = columnNames[columnIndex - 1];
        Object value = getCurrentRow().get(columnName);
        if (value == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        if (value instanceof java.sql.Date) {
            return (java.sql.Date) value;
        }
        return java.sql.Date.valueOf(value.toString());
    }

    @Override
    public java.sql.Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public java.sql.Date getDate(String columnLabel, java.util.Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public java.sql.Array getArray(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Array getArray(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Clob getClob(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Clob getClob(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Blob getBlob(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Blob getBlob(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Ref getRef(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public java.sql.Ref getRef(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Not supported");
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return getObject(columnIndex);
    }

    // Métodos de información de filas (siempre false para ResultSet read-only)
    @Override
    public boolean rowDeleted() throws SQLException {
        checkClosed();
        return false;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkClosed();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkClosed();
        return false;
    }

    @Override
    public java.math.BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        checkClosed();
        int columnIndex = findColumn(columnLabel);
        return getBigDecimal(columnIndex);
    }

    @Override
    public java.math.BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return null;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return null;
        }
        
        wasNull = false;
        if (value instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) value;
        }
        if (value instanceof Number) {
            return new java.math.BigDecimal(value.toString());
        }
        try {
            return new java.math.BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public java.math.BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        java.math.BigDecimal result = getBigDecimal(columnLabel);
        if (result == null) {
            return null;
        }
        return result.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public java.math.BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        java.math.BigDecimal result = getBigDecimal(columnIndex);
        if (result == null) {
            return null;
        }
        return result.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public java.io.Reader getCharacterStream(String columnLabel) throws SQLException {
        checkClosed();
        int columnIndex = findColumn(columnLabel);
        return getCharacterStream(columnIndex);
    }

    @Override
    public java.io.Reader getCharacterStream(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return null;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return null;
        }
        
        wasNull = false;
        return new java.io.StringReader(value.toString());
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkClosed();
        return new DeskDBResultSetMetaData(columnNames);
    }

    @Override
    public String getCursorName() throws SQLException {
        checkClosed();
        return null; // No soportamos cursores con nombre
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkClosed();
        return null; // Sin warnings
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkClosed();
        // No hay warnings que limpiar
    }

    @Override
    public java.io.InputStream getAsciiStream(String columnLabel) throws SQLException {
        checkClosed();
        int columnIndex = findColumn(columnLabel);
        return getAsciiStream(columnIndex);
    }

    @Override
    public java.io.InputStream getAsciiStream(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return null;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return null;
        }
        
        wasNull = false;
        return new java.io.ByteArrayInputStream(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public java.io.InputStream getBinaryStream(String columnLabel) throws SQLException {
        checkClosed();
        int columnIndex = findColumn(columnLabel);
        return getBinaryStream(columnIndex);
    }

    @Override
    public java.io.InputStream getBinaryStream(int columnIndex) throws SQLException {
        checkClosed();
        validateColumnIndex(columnIndex);
        
        Row row = getCurrentRow();
        if (row == null) {
            wasNull = true;
            return null;
        }
        
        String columnName = columnNames[columnIndex - 1];
        Object value = row.get(columnName);
        
        if (value == null) {
            wasNull = true;
            return null;
        }
        
        wasNull = false;
        if (value instanceof byte[]) {
            return new java.io.ByteArrayInputStream((byte[]) value);
        }
        return new java.io.ByteArrayInputStream(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public java.io.InputStream getUnicodeStream(String columnLabel) throws SQLException {
        checkClosed();
        int columnIndex = findColumn(columnLabel);
        return getUnicodeStream(columnIndex);
    }

    @Override
    public java.io.InputStream getUnicodeStream(int columnIndex) throws SQLException {
        // Método obsoleto en JDBC, usamos getCharacterStream en su lugar
        return getAsciiStream(columnIndex);
    }
}
