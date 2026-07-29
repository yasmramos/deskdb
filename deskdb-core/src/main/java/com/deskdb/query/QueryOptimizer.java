package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Filter;
import com.deskdb.index.BTree;
import java.util.List;
import java.util.ArrayList;

public class QueryOptimizer {
    
    public QueryPlan optimize(Query query, Table table) {
        List<Filter> filters = (query != null) ? query.getFilters() : null;
        if (filters == null || filters.isEmpty()) {
            return new QueryPlan().useFullScan().setEstimatedCost(100);
        }
        
        // Primero intentar encontrar un filtro simple indexable
        for (Filter filter : filters) {
            QueryPlan plan = findIndexableFilter(filter, table);
            if (plan != null && plan.useIndex()) {
                return plan;
            }
        }
        
        return new QueryPlan()
            .useFullScan()
            .setEstimatedCost(100);
    }
    
    /**
     * Busca recursivamente un filtro indexable dentro de un filtro (puede ser compuesto).
     * @return QueryPlan con índice si encuentra uno, null si no
     */
    private QueryPlan findIndexableFilter(Filter filter, Table table) {
        // Si es un filtro compuesto, buscar recursivamente en los hijos
        if (filter.getLogicalOp() != Filter.LogicalOperator.NONE && !filter.getChildren().isEmpty()) {
            for (Filter child : filter.getChildren()) {
                QueryPlan plan = findIndexableFilter(child, table);
                if (plan != null && plan.useIndex()) {
                    return plan;
                }
            }
            return null;
        }
        
        // Filtro simple - verificar si es indexable
        String column = filter.getColumn();
        if (column != null && table.hasIndex(column) && isIndexable(filter.getOperator())) {
            BTree index = table.getIndex(column);
            return new QueryPlan()
                .useIndex(index)
                .addFilter(filter)
                .setEstimatedCost(1);
        }
        
        return null;
    }
    
    private boolean isIndexable(Filter.Operator op) {
        return op == Filter.Operator.EQ || 
               op == Filter.Operator.GT || 
               op == Filter.Operator.LT || 
               op == Filter.Operator.GTE || 
               op == Filter.Operator.LTE ||
               op == Filter.Operator.BETWEEN;
    }
}
