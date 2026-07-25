package com.deskdb.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Simple ParameterMetaData implementation for DeskDB.
 */
public class DeskDBParameterMetaData implements ParameterMetaData {
    
    private final int parameterCount;
    
    public DeskDBParameterMetaData(int parameterCount) {
        this.parameterCount = parameterCount;
    }
    
    @Override
    public int getParameterCount() throws SQLException {
        return parameterCount;
    }
    
    @Override
    public int getParameterType(int param) throws SQLException {
        return Types.VARCHAR; // Tipo genérico por defecto
    }
    
    @Override
    public String getParameterTypeName(int param) throws SQLException {
        return "VARCHAR";
    }
    
    @Override
    public int getParameterMode(int param) throws SQLException {
        return parameterModeIn;
    }
    
    @Override
    public int getPrecision(int param) throws SQLException {
        return 0;
    }
    
    @Override
    public int getScale(int param) throws SQLException {
        return 0;
    }
    
    @Override
    public int isNullable(int param) throws SQLException {
        return ParameterMetaData.parameterNullable;
    }
    
    @Override
    public boolean isSigned(int param) throws SQLException {
        return false;
    }
    
    @Override
    public String getParameterClassName(int param) throws SQLException {
        return "java.lang.String";
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
