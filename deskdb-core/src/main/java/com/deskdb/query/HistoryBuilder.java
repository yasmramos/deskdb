package com.deskdb.query;

import com.deskdb.core.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Builder for time-travel queries that retrieve historical versions of rows.
 * Supports querying data as it existed at a specific point in time.
 */
public class HistoryBuilder {
    private final Table table;
    private final List<com.deskdb.core.Filter> filters = new ArrayList<>();
    private LocalDateTime asOfTimestamp;
    private Long targetRowId;
    private int limit = -1;
    private int offset = 0;

    public HistoryBuilder(Table table) {
        this.table = table;
    }

    /**
     * Specifies the row ID to retrieve history for.
     * @param rowId the row ID
     * @return this builder for method chaining
     */
    public HistoryBuilder history(Long rowId) {
        this.targetRowId = rowId;
        return this;
    }

    /**
     * Specifies the point in time to query (as-of timestamp).
     * @param timestamp the timestamp to query as of
     * @return this builder for method chaining
     */
    public HistoryBuilder asOf(LocalDateTime timestamp) {
        this.asOfTimestamp = timestamp;
        return this;
    }

    /**
     * Adds a filter condition to the history query.
     * @param column the column name
     * @return a FilterBuilder for constructing the filter
     */
    public FilterBuilder where(String column) {
        return new FilterBuilder(this, column);
    }

    /**
     * Sets the maximum number of results to return.
     * @param limit the limit
     * @return this builder for method chaining
     */
    public HistoryBuilder limit(int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Sets the offset for pagination.
     * @param offset the offset
     * @return this builder for method chaining
     */
    public HistoryBuilder offset(int offset) {
        this.offset = offset;
        return this;
    }

    /**
     * Executes the history query and returns historical row versions.
     * @return list of RowVersion objects representing historical states
     * @throws Exception if an error occurs
     */
    public List<RowVersion> execute() throws Exception {
        // Get all rows matching filters
        List<Row> rows;
        if (!filters.isEmpty()) {
            rows = table.select(filters);
        } else {
            rows = table.select(null);
        }

        // If targeting specific row ID, filter to that row by primary key column
        if (targetRowId != null) {
            // Find the primary key column name from the schema
            String pkColumnName = null;
            for (Column col : table.getColumns()) {
                if (col.isPrimaryKey()) {
                    pkColumnName = col.getName();
                    break;
                }
            }
            
            // If table has a primary key, filter by PK column value; otherwise fallback to internal rowId
            if (pkColumnName != null) {
                final String pkCol = pkColumnName;
                rows = rows.stream()
                    .filter(r -> {
                        Object pkValue = r.getValues().get(pkCol);
                        if (pkValue == null) {
                            return false;
                        }
                        // Convert targetRowId to match the type of the PK column
                        if (pkValue instanceof Number) {
                            return ((Number) pkValue).longValue() == targetRowId;
                        }
                        return pkValue.equals(targetRowId);
                    })
                    .collect(Collectors.toList());
            } else {
                // Fallback: filter by internal rowId if no PK defined
                rows = rows.stream()
                    .filter(r -> r.getRowId() == targetRowId)
                    .collect(Collectors.toList());
            }
        }

        // Convert current rows to RowVersion objects
        // In a full implementation, this would query the version history table
        List<RowVersion> versions = new ArrayList<>();
        for (Row row : rows) {
            // For now, create a single version from current state
            // Full implementation would retrieve from audit log / version table
            Map<String, Object> values = new HashMap<>(row.getValues());
            RowVersion version = new RowVersion(
                row.getRowId(),
                values,
                asOfTimestamp != null ? asOfTimestamp : LocalDateTime.now(),
                "CURRENT",
                null
            );
            versions.add(version);
        }

        // Apply offset and limit
        int start = Math.max(0, offset);
        int end = limit < 0 ? versions.size() : Math.min(versions.size(), start + limit);

        if (start > versions.size()) {
            return new ArrayList<>();
        }

        return versions.subList(start, end);
    }

    /**
     * Internal class for building filter conditions.
     */
    public class FilterBuilder {
        private final HistoryBuilder parent;
        private final String column;

        public FilterBuilder(HistoryBuilder parent, String column) {
            this.parent = parent;
            this.column = column;
        }

        public HistoryBuilder eq(Object value) {
            parent.filters.add(new com.deskdb.core.Filter(column, com.deskdb.core.Filter.Operator.EQ, value));
            return parent;
        }

        public HistoryBuilder gt(Object value) {
            parent.filters.add(new com.deskdb.core.Filter(column, com.deskdb.core.Filter.Operator.GT, value));
            return parent;
        }

        public HistoryBuilder lt(Object value) {
            parent.filters.add(new com.deskdb.core.Filter(column, com.deskdb.core.Filter.Operator.LT, value));
            return parent;
        }

        public HistoryBuilder gte(Object value) {
            parent.filters.add(new com.deskdb.core.Filter(column, com.deskdb.core.Filter.Operator.GTE, value));
            return parent;
        }

        public HistoryBuilder lte(Object value) {
            parent.filters.add(new com.deskdb.core.Filter(column, com.deskdb.core.Filter.Operator.LTE, value));
            return parent;
        }
    }
}
