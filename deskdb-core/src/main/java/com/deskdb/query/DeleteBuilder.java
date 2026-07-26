package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Filter;
import com.deskdb.core.Row;
import com.deskdb.core.Transaction;
import java.util.Collections;
import java.util.List;

public class DeleteBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private Filter filter;

    public DeleteBuilder(Table table) {
        this.table = table;
        this.transaction = null;
        this.tableName = null;
    }
    
    public DeleteBuilder(Transaction transaction, String tableName) {
        this.table = null;
        this.transaction = transaction;
        this.tableName = tableName;
    }

    public WhereCondition where(String column) {
        return new WhereCondition(column, this);
    }

    public int execute() throws Exception {
        if (filter == null) {
            throw new IllegalStateException("WHERE clause required for delete");
        }
        
        List<Row> rows;
        String actualTableName = tableName != null ? tableName : table.getName();
        
        if (transaction != null) {
            rows = transaction.select(actualTableName, java.util.Collections.singletonList(filter));
        } else {
            rows = table.select(java.util.Collections.singletonList(filter));
        }

        if (rows.isEmpty()) {
            return 0;
        }
        
        if (transaction != null) {
            // Use provided transaction - don't commit automatically
            for (Row row : rows) {
                transaction.applyChange(actualTableName, row.getRowId(), null);
            }
        } else {
            // Auto-commit: create implicit transaction for the entire operation
            try (Transaction autoTx = table.getDb().beginTransaction()) {
                for (Row row : rows) {
                    autoTx.applyChange(table.getName(), row.getRowId(), null);
                }
                autoTx.commit();
            }
        }
        return rows.size();
    }

    public static class WhereCondition {
        private final String column;
        private final DeleteBuilder parent;

        public WhereCondition(String column, DeleteBuilder parent) {
            this.column = column;
            this.parent = parent;
        }

        public DeleteBuilder is(Object value) {
            parent.filter = new Filter(column, Filter.Operator.EQ, value);
            return parent;
        }

        public DeleteBuilder greaterThan(Object value) {
            parent.filter = new Filter(column, Filter.Operator.GT, value);
            return parent;
        }

        public DeleteBuilder lessThan(Object value) {
            parent.filter = new Filter(column, Filter.Operator.LT, value);
            return parent;
        }

        public DeleteBuilder greaterThanOrEqual(Object value) {
            parent.filter = new Filter(column, Filter.Operator.GTE, value);
            return parent;
        }

        public DeleteBuilder lessThanOrEqual(Object value) {
            parent.filter = new Filter(column, Filter.Operator.LTE, value);
            return parent;
        }

        public DeleteBuilder eq(Object value) {
            parent.filter = new Filter(column, Filter.Operator.EQ, value);
            return parent;
        }

        public DeleteBuilder isEqualTo(Object value) {
            parent.filter = new Filter(column, Filter.Operator.EQ, value);
            return parent;
        }
    }
}
