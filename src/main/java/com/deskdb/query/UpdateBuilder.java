package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Filter;
import com.deskdb.core.Row;
import com.deskdb.core.Transaction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private final Map<String, Object> setValues = new HashMap<>();
    private Filter filter;

    public UpdateBuilder(Table table) {
        this.table = table;
        this.transaction = null;
        this.tableName = null;
    }
    
    public UpdateBuilder(Transaction transaction, String tableName) {
        this.table = null;
        this.transaction = transaction;
        this.tableName = tableName;
    }

    public UpdateBuilder set(String column, Object value) {
        setValues.put(column, value);
        return this;
    }
    
    public UpdateBuilder set(Map<String, Object> values) {
        setValues.putAll(values);
        return this;
    }

    public WhereCondition where(String column) {
        return new WhereCondition(column, this);
    }

    public int execute() throws Exception {
        if (filter == null) {
            throw new IllegalStateException("WHERE clause required for update");
        }
        
        List<Row> rows;
        if (transaction != null) {
            // Leer desde el snapshot + cambios pendientes
            rows = transaction.select(tableName, java.util.Collections.singletonList(filter));
        } else {
            rows = table.select(java.util.Collections.singletonList(filter));
        }
        
        for (Row row : rows) {
            if (transaction != null) {
                Map<String, Object> newValues = new HashMap<>(row.getValues());
                newValues.putAll(setValues);
                Row newRow = new Row(row.getRowId(), newValues);
                transaction.applyChange(tableName, row.getRowId(), newRow);
            } else {
                table.update(row.getRowId(), setValues);
            }
        }
        return rows.size();
    }

    public static class WhereCondition {
        private final String column;
        private final UpdateBuilder parent;

        public WhereCondition(String column, UpdateBuilder parent) {
            this.column = column;
            this.parent = parent;
        }

        public UpdateBuilder is(Object value) {
            parent.filter = new Filter(column, Filter.Operator.EQ, value);
            return parent;
        }
        
        public UpdateBuilder eq(Object value) {
            parent.filter = new Filter(column, Filter.Operator.EQ, value);
            return parent;
        }

        public UpdateBuilder greaterThan(Object value) {
            parent.filter = new Filter(column, Filter.Operator.GT, value);
            return parent;
        }

        public UpdateBuilder lessThan(Object value) {
            parent.filter = new Filter(column, Filter.Operator.LT, value);
            return parent;
        }

        public UpdateBuilder greaterThanOrEqual(Object value) {
            parent.filter = new Filter(column, Filter.Operator.GTE, value);
            return parent;
        }

        public UpdateBuilder lessThanOrEqual(Object value) {
            parent.filter = new Filter(column, Filter.Operator.LTE, value);
            return parent;
        }
    }
}
