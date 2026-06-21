package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Row;
import com.deskdb.core.Filter;
import java.util.ArrayList;
import java.util.List;

public class SelectBuilder {
    private final Table table;
    private final List<Filter> filters = new ArrayList<>();
    private List<String> columns;
    private int limit = -1;
    private int offset = 0;
    private String orderByColumn;
    private boolean orderByAsc = true;

    public SelectBuilder(Table table) {
        this.table = table;
    }

    public SelectBuilder columns(String... cols) {
        this.columns = new ArrayList<>();
        for (String col : cols) {
            this.columns.add(col);
        }
        return this;
    }

    public FilterBuilder where(String column) {
        return new FilterBuilder(this, column);
    }

    public SelectBuilder addFilter(Filter filter) {
        this.filters.add(filter);
        return this;
    }

    public SelectBuilder limit(int limit) {
        this.limit = limit;
        return this;
    }

    public SelectBuilder offset(int offset) {
        this.offset = offset;
        return this;
    }

    public SelectBuilder orderBy(String column) {
        this.orderByColumn = column;
        this.orderByAsc = true;
        return this;
    }

    public SelectBuilder orderByDesc(String column) {
        this.orderByColumn = column;
        this.orderByAsc = false;
        return this;
    }

    public List<Row> execute() throws Exception {
        // Usar el método select con lista de filtros directamente
        return table.select(filters);
    }

    // Clase interna para construir filtros
    public class FilterBuilder {
        private final SelectBuilder parent;
        private final String column;

        public FilterBuilder(SelectBuilder parent, String column) {
            this.parent = parent;
            this.column = column;
        }

        public SelectBuilder is(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.EQ, value));
            return parent;
        }

        public SelectBuilder greaterThan(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.GT, value));
            return parent;
        }

        public SelectBuilder lessThan(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.LT, value));
            return parent;
        }

        public SelectBuilder greaterThanOrEqual(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.GTE, value));
            return parent;
        }

        public SelectBuilder lessThanOrEqual(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.LTE, value));
            return parent;
        }

        public SelectBuilder between(Object from, Object to) {
            parent.addFilter(new Filter(column, Filter.Operator.ALL, new Object[]{from, to}));
            return parent;
        }
    }
    
    // Clase WhereCondition para compatibilidad con TableOperations
    public class WhereCondition {
        private final SelectBuilder builder;
        private final String column;
        
        public WhereCondition(String column, SelectBuilder builder) {
            this.column = column;
            this.builder = builder;
        }

        public SelectBuilder is(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.EQ, value));
            return builder;
        }

        public SelectBuilder greaterThan(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.GT, value));
            return builder;
        }

        public SelectBuilder lessThan(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.LT, value));
            return builder;
        }

        public SelectBuilder greaterThanOrEqual(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.GTE, value));
            return builder;
        }

        public SelectBuilder lessThanOrEqual(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.LTE, value));
            return builder;
        }

        public SelectBuilder between(Object from, Object to) {
            builder.addFilter(new Filter(column, Filter.Operator.ALL, new Object[]{from, to}));
            return builder;
        }
    }
}
