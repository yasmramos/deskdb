package com.deskdb.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Implementación de ResultSetMetaData para DeskDB.
 */
public class DeskDBResultSetMetaData implements ResultSetMetaData {

    private final String[] columnNames;

    public DeskDBResultSetMetaData(String[] columnNames) {
        this.columnNames = columnNames != null ? columnNames : new String[0];
    }

    @Override
    public int getColumnCount() throws SQLException {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        if (column < 1 || column > columnNames.length) {
            throw new SQLException("Invalid column index: " + column);
        }
        return columnNames[column - 1];
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getColumnName(column);
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return 256; // Tamaño por defecto
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        return 0;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return ResultSetMetaData.columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return Object.class.getName();
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return Types.VARCHAR;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return "VARCHAR";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return "";
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
}
