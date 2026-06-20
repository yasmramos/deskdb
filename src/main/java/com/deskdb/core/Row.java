package com.deskdb.core;

import java.util.*;

/**
 * Representa una fila de datos en una tabla.
 */
public class Row {
    private final long rowId;
    private final Map<String, Object> values;

    public Row(long rowId) {
        this.rowId = rowId;
        this.values = new LinkedHashMap<>();
    }

    public Row(long rowId, Map<String, Object> values) {
        this.rowId = rowId;
        this.values = new LinkedHashMap<>(values);
    }

    public long getRowId() {
        return rowId;
    }

    public Object get(String column) {
        return values.get(column);
    }

    public void set(String column, Object value) {
        values.put(column, value);
    }

    public Map<String, Object> getValues() {
        return new LinkedHashMap<>(values);
    }

    public Set<String> getColumns() {
        return values.keySet();
    }

    @Override
    public String toString() {
        return "Row{id=" + rowId + ", values=" + values + "}";
    }
}
