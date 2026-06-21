package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Filter;
import com.deskdb.core.Row;
import java.util.Collections;
import java.util.List;

public class DeleteBuilder {
    private final Table table;
    private Filter filter;

    public DeleteBuilder(Table table) {
        this.table = table;
    }

    public WhereCondition where(String column) {
        return new WhereCondition(column, this);
    }

    public int execute() throws Exception {
        if (filter == null) {
            throw new IllegalStateException("WHERE clause required for delete");
        }
        List<Row> rows = table.select(Collections.singletonList(filter));
        for (Row row : rows) {
            table.delete(row.getRowId());
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
    }
}
