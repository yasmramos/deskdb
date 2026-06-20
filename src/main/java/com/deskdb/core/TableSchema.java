package com.deskdb.core;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Esquema de una tabla que define sus columnas y tipos.
 */
public class TableSchema {
    private final String name;
    private final Map<String, Column> columns;

    public TableSchema(String name) {
        this.name = name;
        this.columns = new LinkedHashMap<>();
    }

    public TableSchema(String name, List<Column> columns) {
        this.name = name;
        this.columns = new LinkedHashMap<>();
        for (Column col : columns) {
            this.columns.put(col.getName(), col);
        }
    }

    public TableSchema addColumn(String name, DataType type) {
        columns.put(name, new Column(name, type));
        return this;
    }

    public String getName() {
        return name;
    }

    public Map<String, Column> getColumns() {
        return new LinkedHashMap<>(columns);
    }

    public boolean hasColumn(String columnName) {
        return columns.containsKey(columnName);
    }

    public Column getColumn(String columnName) {
        return columns.get(columnName);
    }
}
