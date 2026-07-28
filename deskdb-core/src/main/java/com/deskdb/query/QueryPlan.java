package com.deskdb.query;

import com.deskdb.core.Filter;
import com.deskdb.index.BTree;
import java.util.ArrayList;
import java.util.List;

public class QueryPlan {
    private BTree index;
    private boolean useFullScan;
    private List<Filter> filters = new ArrayList<>();
    private int estimatedCost;
    private Filter primaryFilter;

    public QueryPlan useIndex(BTree index) {
        this.index = index;
        this.useFullScan = false;
        return this;
    }

    public QueryPlan useFullScan() {
        this.useFullScan = true;
        this.index = null;
        return this;
    }

    public QueryPlan addFilter(Filter filter) {
        this.filters.add(filter);
        // El primer filtro es el primario (el que usa el índice)
        if (this.primaryFilter == null) {
            this.primaryFilter = filter;
        }
        return this;
    }

    public QueryPlan setEstimatedCost(int cost) {
        this.estimatedCost = cost;
        return this;
    }

    public BTree getIndex() { return index; }
    public boolean isUseFullScan() { return useFullScan; }
    public List<Filter> getFilters() { return filters; }
    public int getEstimatedCost() { return estimatedCost; }
    
    /**
     * Retorna el filtro primario que se usa para la búsqueda con índice.
     */
    public Filter getPrimaryFilter() {
        return primaryFilter != null ? primaryFilter : 
               (filters.isEmpty() ? null : filters.get(0));
    }
    
    /**
     * Verifica si el plan usa un índice para la ejecución.
     */
    public boolean useIndex() {
        return index != null && !useFullScan;
    }
}
