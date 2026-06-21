package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Filter;
import com.deskdb.core.Row;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateBuilder {
    private final Table table;
    private final Map<String, Object> setValues = new HashMap<>();
    private Filter filter;

    public UpdateBuilder(Table table) {
        this.table = table;
    }

    public UpdateBuilder set(String column, Object value) {
        setValues.put(column, value);
        return this;
    }

    public WhereCondition where(String column) {
        return new WhereCondition(column, this);
    }

    public int execute() throws Exception {
        if (filter == null) {
            throw new IllegalStateException("WHERE clause required for update");
        }
        
        List<Row> rows = table.select(java.util.Collections.singletonList(filter));
        for (Row row : rows) {
            table.update(row.getRowId(), setValues);
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
