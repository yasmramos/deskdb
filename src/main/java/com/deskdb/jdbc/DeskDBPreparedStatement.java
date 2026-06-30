package com.deskdb.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementación de PreparedStatement JDBC para DeskDB.
 */
public class DeskDBPreparedStatement extends DeskDBStatement implements PreparedStatement {

    private final String sql;
    private final List<Object> parameters = new ArrayList<>();
    private boolean closed = false;

    public DeskDBPreparedStatement(DeskDBConnection connection, String sql) {
        super(connection);
        this.sql = sql;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkClosed();
        
        // Reemplazar parámetros en el SQL
        String finalSql = applyParameters(sql);
        return super.executeQuery(finalSql);
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkClosed();
        
        // Reemplazar parámetros en el SQL
        String finalSql = applyParameters(sql);
        return super.executeUpdate(finalSql);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }

    @Override
    public void clearParameters() throws SQLException {
        checkClosed();
        parameters.clear();
    }

    @Override
    public boolean execute() throws SQLException {
        checkClosed();
        
        String upperSql = sql.trim().toUpperCase();
        if (upperSql.startsWith("SELECT")) {
            executeQuery();
            return true;
        } else {
            executeUpdate();
            return false;
        }
    }

    @Override
    public void addBatch() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Batch operations not supported");
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        super.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader, int length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("CharacterStream not supported");
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("REF not supported");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("BLOB not supported");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("CLOB not supported");
    }

    @Override
    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Array not supported");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("ResultSetMetaData not available for PreparedStatement");
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Date not supported");
    }

    @Override
    public void setTime(int parameterIndex, java.sql.Time x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Time not supported");
    }

    @Override
    public void setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Timestamp not supported");
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Streams not supported");
    }

    @Override
    public void setUnicodeStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("UnicodeStream not supported");
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("BinaryStream not supported");
    }

    @Override
    public void clearBatch() throws SQLException {
        checkClosed();
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("RowId not supported");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NString not supported");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, java.io.Reader value, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NCharacterStream not supported");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NClob not supported");
    }

    @Override
    public void setClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Clob Reader not supported");
    }

    @Override
    public void setBlob(int parameterIndex, java.io.InputStream inputStream, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Blob InputStream not supported");
    }

    @Override
    public void setNClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NClob Reader not supported");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("SQLXML not supported");
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Date with Calendar not supported");
    }

    @Override
    public void setTime(int parameterIndex, java.sql.Time x, java.util.Calendar cal) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Time with Calendar not supported");
    }

    @Override
    public void setTimestamp(int parameterIndex, java.sql.Timestamp x, java.util.Calendar cal) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Timestamp with Calendar not supported");
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex, sqlType);
    }

    @Override
    public void setURL(int parameterIndex, java.net.URL x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("URL not supported");
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("AsciiStream not supported");
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("BinaryStream not supported");
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("CharacterStream not supported");
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("AsciiStream not supported");
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("BinaryStream not supported");
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("CharacterStream not supported");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, java.io.Reader value) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NCharacterStream not supported");
    }

    @Override
    public void setClob(int parameterIndex, java.io.Reader reader) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Clob Reader not supported");
    }

    @Override
    public void setBlob(int parameterIndex, java.io.InputStream inputStream) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Blob InputStream not supported");
    }

    @Override
    public void setNClob(int parameterIndex, java.io.Reader reader) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NClob Reader not supported");
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

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("PreparedStatement is closed");
        }
    }

    private void setParameter(int parameterIndex, Object value) throws SQLException {
        if (parameterIndex < 1) {
            throw new SQLException("Parameter index must be >= 1");
        }
        
        // Asegurar que la lista tenga suficiente tamaño
        while (parameters.size() < parameterIndex) {
            parameters.add(null);
        }
        
        parameters.set(parameterIndex - 1, value);
    }

    private String applyParameters(String sql) {
        String result = sql;
        
        for (int i = 0; i < parameters.size(); i++) {
            Object param = parameters.get(i);
            String placeholder = "?";
            
            // Reemplazar solo el siguiente ? encontrado
            int placeholderIndex = result.indexOf(placeholder);
            if (placeholderIndex != -1) {
                String replacement;
                if (param == null) {
                    replacement = "NULL";
                } else if (param instanceof String) {
                    replacement = "'" + ((String) param).replace("'", "''") + "'";
                } else if (param instanceof Number || param instanceof Boolean) {
                    replacement = param.toString();
                } else {
                    replacement = "'" + param.toString().replace("'", "''") + "'";
                }
                
                result = result.substring(0, placeholderIndex) + 
                         replacement + 
                         result.substring(placeholderIndex + 1);
            }
        }
        
        return result;
    }

    @Override
    public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
        // Retornar implementación simple
        return new DeskDBParameterMetaData(parameters.size());
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("BLOB/Binary types not supported yet");
    }

    @Override
    public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) throws SQLException {
        checkClosed();
        setParameter(parameterIndex, x);
    }
}
