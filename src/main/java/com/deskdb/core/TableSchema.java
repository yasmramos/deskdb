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
    
    @SuppressWarnings("unchecked")
    public static TableSchema fromMap(String name, Map<String, Object> data) {
        List<Map<String, Object>> colsData = (List<Map<String, Object>>) data.get("columns");
        java.util.List<Column> cols = new java.util.ArrayList<>();
        for (Map<String, Object> colData : colsData) {
            String colName = (String) colData.get("name");
            DataType type = DataType.valueOf((String) colData.get("type"));
            Column col = new Column(colName, type);
            if (colData.containsKey("primaryKey")) {
                col.setPrimaryKey((Boolean) colData.get("primaryKey"));
            }
            if (colData.containsKey("notNull")) {
                col.setNotNull((Boolean) colData.get("notNull"));
            }
            cols.add(col);
        }
        return new TableSchema(name, cols);
    }
    
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> colsData = new java.util.ArrayList<>();
        for (Column col : columns.values()) {
            Map<String, Object> colData = new LinkedHashMap<>();
            colData.put("name", col.getName());
            colData.put("type", col.getType().name());
            colData.put("primaryKey", col.isPrimaryKey());
            colData.put("notNull", col.isNotNull());
            colsData.add(colData);
        }
        result.put("columns", colsData);
        return result;
    }
    
    // Método auxiliar para obtener lista de columnas
    public java.util.List<Column> getColumnsList() {
        return new java.util.ArrayList<>(columns.values());
    }
}
