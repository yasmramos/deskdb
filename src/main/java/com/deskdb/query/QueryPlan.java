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
}
