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
        // IMPORTANT: never pre-filter through table.select(...) when no explicit
        // where() filters were given. The table's scan path may consult a shared
        // L1/L2 row cache that other tests in the same JVM can pollute with stale
        // entries (same table name, different database instance), which made
        // TimeTravelTest pass in isolation but fail inside the full suite. Reading
        // table.getData() directly bypasses any cache and is always consistent.
        List<Row> rows;
        if (!filters.isEmpty()) {
            rows = table.select(filters);
        } else {
            rows = new ArrayList<>(table.getData().values());
        }

        // history(rowId) semantics: resolve the requested id against BOTH the
        // internal row identifier (the id used by VersionManager chains) and the
        // primary key column value, then union the matches. This keeps backward
        // compatibility with callers that pass the PK value while still allowing
        // direct access to the internal-rowId version chain.
        // When filters are present we intersect: only rows returned by the filtered
        // select that match the requested id are considered.
        final String pkColumnName = findPrimaryKeyColumn();

        // Use an ordered set so results are deterministic regardless of the
        // iteration order of the underlying row source.
        java.util.SortedSet<Long> matchedRowIds = new java.util.TreeSet<>();
        if (targetRowId != null) {
            for (Row r : rows) {
                boolean matchesInternal = r.getRowId() == targetRowId;
                boolean matchesPk = false;
                if (pkColumnName != null) {
                    Object pkValue = r.getValues().get(pkColumnName);
                    if (pkValue instanceof Number) {
                        matchesPk = ((Number) pkValue).longValue() == targetRowId;
                    } else if (pkValue != null) {
                        matchesPk = pkValue.equals(targetRowId);
                    }
                }
                if (matchesInternal || matchesPk) {
                    matchedRowIds.add(r.getRowId());
                }
            }
        } else {
            for (Row r : rows) {
                matchedRowIds.add(r.getRowId());
            }
        }

        // Retrieve historical versions from the version chain (MVCC store).
        // Falls back to the current state only when no version was recorded
        // (e.g. legacy data loaded without history).
        // TreeSet already guarantees uniqueness and ordering, no extra dedup needed.
        List<RowVersion> versions = new ArrayList<>();
        for (Long rid : matchedRowIds) {
            collectVersions(rid, versions);
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
     * Finds the primary key column name of the table, or null if none.
     */
    private String findPrimaryKeyColumn() {
        for (Column col : table.getColumns()) {
            if (col.isPrimaryKey()) {
                return col.getName();
            }
        }
        return null;
    }

    /**
     * Collects the version chain of a single row into {@code out}.
     * If an as-of timestamp was specified, only the most recent version at or
     * before that instant is returned (time travel). Deleted versions are
     * excluded unless they are the current state requested without as-of.
     * Falls back to synthesizing a CURRENT version from live table data when
     * no history exists for the row (e.g. rows loaded from legacy files).
     */
    private void collectVersions(long rowId, List<RowVersion> out) {
        VersionManager vm = table.getVersionManager();
        if (asOfTimestamp != null) {
            RowVersion v = vm.getVersionAsOf(rowId, asOfTimestamp);
            if (v == null || v.isDeleted()) {
                // No version recorded at/before the requested instant (e.g. rows
                // loaded from disk have no MVCC history). If the row still exists
                // live, treat its current state as best-known state as of that time.
                Row live = table.getRowById(rowId);
                if (live != null) {
                    v = new RowVersion(rowId, new HashMap<>(live.getValues()),
                        asOfTimestamp, "CURRENT", null);
                } else {
                    return;
                }
            }
            out.add(v);
            return;
        }
        List<RowVersion> history = vm.getHistory(rowId);
        if (!history.isEmpty()) {
            // Newest first; skip tombstones so callers see live states.
            for (RowVersion v : history) {
                if (!v.isDeleted()) {
                    out.add(v);
                }
            }
            if (!out.isEmpty() || history.get(0).isDeleted()) {
                // Either we found live versions, or the row's latest state is a
                // deletion — in both cases the MVCC store answered definitively.
                return;
            }
        }
        // Fallback: no recorded history (legacy load). Synthesize current state.
        Row row = table.getRowById(rowId);
        if (row != null) {
            Map<String, Object> values = new HashMap<>(row.getValues());
            out.add(new RowVersion(rowId, values, LocalDateTime.now(), "CURRENT", null));
        }
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
