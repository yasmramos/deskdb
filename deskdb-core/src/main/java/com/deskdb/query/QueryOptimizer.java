package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Filter;
import com.deskdb.index.BTree;
import java.util.List;

public class QueryOptimizer {
    
    public QueryPlan optimize(Query query, Table table) {
        List<Filter> filters = (query != null) ? query.getFilters() : null;
        if (filters == null || filters.isEmpty()) {
            return new QueryPlan().useFullScan().setEstimatedCost(100);
        }
        
        for (Filter filter : filters) {
            String column = filter.getColumn();
            if (table.hasIndex(column)) {
                BTree index = table.getIndex(column);
                
                if (isIndexable(filter.getOperator())) {
                    return new QueryPlan()
                        .useIndex(index)
                        .addFilter(filter)
                        .setEstimatedCost(1);
                }
            }
        }
        
        return new QueryPlan()
            .useFullScan()
            .setEstimatedCost(100);
    }
    
    private boolean isIndexable(Filter.Operator op) {
        return op == Filter.Operator.EQ || 
               op == Filter.Operator.GT || 
               op == Filter.Operator.LT || 
                
                
               op == Filter.Operator.ALL;
    }
}
