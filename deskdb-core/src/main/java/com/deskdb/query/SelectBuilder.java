package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Row;
import com.deskdb.core.Filter;
import com.deskdb.core.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class SelectBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private final List<Filter> filters = new ArrayList<>();
    private List<String> columns;
    private int limit = -1;
    private int offset = 0;
    private String orderByColumn;
    private boolean orderByAsc = true;

    public SelectBuilder(Table table) {
        this.table = table;
        this.transaction = null;
        this.tableName = null;
    }
    
    public SelectBuilder(Transaction transaction, String tableName) {
        this.table = null;
        this.transaction = transaction;
        this.tableName = tableName;
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

    public FilterBuilder and(String column) {
        return new FilterBuilder(this, column);
    }
    
    public WhereCondition whereCond(String column) {
        return new WhereCondition(column, this);
    }
    
    public WhereCondition andCond(String column) {
        return new WhereCondition(column, this);
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
        List<Row> results;
        if (transaction != null) {
            results = transaction.select(tableName, filters);
        } else {
            results = table.select(filters);
        }
        
        // Si se especificaron columnas, filtrar los resultados
        if (columns != null && !columns.isEmpty()) {
            List<Row> filteredResults = new ArrayList<>();
            for (Row row : results) {
                Map<String, Object> filteredValues = new HashMap<>();
                for (String col : columns) {
                    if (row.getValues().containsKey(col)) {
                        filteredValues.put(col, row.getValues().get(col));
                    }
                }
                filteredResults.add(new Row(row.getRowId(), filteredValues));
            }
            results = filteredResults;
        }
        
        // Aplicar ORDER BY si se especificó
        if (orderByColumn != null && !orderByColumn.isEmpty()) {
            results.sort((r1, r2) -> {
                Object v1 = r1.get(orderByColumn);
                Object v2 = r2.get(orderByColumn);
                if (v1 == null && v2 == null) return 0;
                if (v1 == null) return -1;
                if (v2 == null) return 1;
                
                int cmp;
                if (v1 instanceof Comparable && v2 instanceof Comparable) {
                    cmp = ((Comparable) v1).compareTo((Comparable) v2);
                } else {
                    cmp = v1.toString().compareTo(v2.toString());
                }
                return orderByAsc ? cmp : -cmp;
            });
        }
        
        // Aplicar OFFSET y LIMIT
        int start = Math.max(0, offset);
        int end = limit < 0 ? results.size() : Math.min(results.size(), start + limit);
        
        if (start > results.size()) {
            return new ArrayList<>();
        }
        
        return results.subList(start, end);
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
            parent.addFilter(new Filter(column, Filter.Operator.BETWEEN, from, to));
            return parent;
        }

        public SelectBuilder eq(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.EQ, value));
            return parent;
        }

        public SelectBuilder ne(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.NE, value));
            return parent;
        }

        public SelectBuilder gt(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.GT, value));
            return parent;
        }

        public SelectBuilder gte(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.GTE, value));
            return parent;
        }

        public SelectBuilder lt(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.LT, value));
            return parent;
        }

        public SelectBuilder lte(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.LTE, value));
            return parent;
        }

        public SelectBuilder isEqualTo(Object value) {
            parent.addFilter(new Filter(column, Filter.Operator.EQ, value));
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
            builder.addFilter(new Filter(column, Filter.Operator.BETWEEN, from, to));
            return builder;
        }

        public SelectBuilder eq(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.EQ, value));
            return builder;
        }
        
        public WhereCondition eqCond(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.EQ, value));
            return this;
        }
        
        public WhereCondition gtCond(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.GT, value));
            return this;
        }
        
        public WhereCondition gteCond(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.GTE, value));
            return this;
        }
        
        public WhereCondition ltCond(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.LT, value));
            return this;
        }
        
        public WhereCondition lteCond(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.LTE, value));
            return this;
        }
        
        public WhereCondition neCond(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.NE, value));
            return this;
        }
        
        public SelectBuilder ne(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.NE, value));
            return builder;
        }
        
        public SelectBuilder gt(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.GT, value));
            return builder;
        }
        
        public SelectBuilder gte(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.GTE, value));
            return builder;
        }
        
        public SelectBuilder lt(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.LT, value));
            return builder;
        }
        
        public SelectBuilder lte(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.LTE, value));
            return builder;
        }
        
        public SelectBuilder isEqualTo(Object value) {
            builder.addFilter(new Filter(column, Filter.Operator.EQ, value));
            return builder;
        }
        
        public WhereCondition and(String column) {
            return new WhereCondition(column, builder);
        }
        
        public WhereCondition andCond(String column) {
            return new WhereCondition(column, builder);
        }
        
        public WhereCondition andWhere(String column) {
            return new WhereCondition(column, builder);
        }
        
        public SelectBuilder limit(int limit) {
            return builder.limit(limit);
        }
        
        public SelectBuilder offset(int offset) {
            return builder.offset(offset);
        }
        
        public SelectBuilder orderBy(String column) {
            return builder.orderBy(column);
        }
        
        public SelectBuilder orderByDesc(String column) {
            return builder.orderByDesc(column);
        }
    }
}
