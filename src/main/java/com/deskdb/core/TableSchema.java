package com.deskdb.core;

/**
 * Esquema de una tabla que define sus columnas y tipos.
 */
public class TableSchema {
    private final String name;
    private final java.util.Map<String, ColumnType> columns;

    public enum ColumnType {
        STRING, INT, LONG, DOUBLE, BOOLEAN, DATE, TIMESTAMP, BLOB, JSON
    }

    public TableSchema(String name) {
        this.name = name;
        this.columns = new java.util.LinkedHashMap<>();
    }

    public TableSchema addColumn(String name, ColumnType type) {
        columns.put(name, type);
        return this;
    }

    public String getName() {
        return name;
    }

    public java.util.Map<String, ColumnType> getColumns() {
        return new java.util.LinkedHashMap<>(columns);
    }

    public boolean hasColumn(String columnName) {
        return columns.containsKey(columnName);
    }

    public ColumnType getColumnType(String columnName) {
        return columns.get(columnName);
    }
}
