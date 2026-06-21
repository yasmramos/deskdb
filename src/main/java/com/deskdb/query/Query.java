package com.deskdb.query;

import com.deskdb.core.Filter;
import java.util.List;

public class Query {
    private final String tableName;
    private final List<Filter> filters;
    private final List<String> columns;
    private final int limit;
    private final int offset;
    private final String orderByColumn;
    private final boolean orderByAsc;

    public Query(String tableName, List<Filter> filters, List<String> columns, 
                 int limit, int offset, String orderByColumn, boolean orderByAsc) {
        this.tableName = tableName;
        this.filters = filters;
        this.columns = columns;
        this.limit = limit;
        this.offset = offset;
        this.orderByColumn = orderByColumn;
        this.orderByAsc = orderByAsc;
    }

    public String getTableName() { return tableName; }
    public List<Filter> getFilters() { return filters; }
    public List<String> getColumns() { return columns; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }
    public String getOrderByColumn() { return orderByColumn; }
    public boolean isOrderByAsc() { return orderByAsc; }
}
