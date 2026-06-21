package com.deskdb.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Operaciones fluidas para trabajar con tablas en DeskDB.
 */
public class TableOperations {
    private final DeskDB db;
    private final String tableName;
    private final List<Filter> filters;
    private final Map<String, Object> valuesToInsert;
    private final List<String> columnsToUpdate;
    private final Map<String, Object> valuesToUpdate;

    TableOperations(DeskDB db, String tableName) {
        this.db = db;
        this.tableName = tableName;
        this.filters = new ArrayList<>();
        this.valuesToInsert = new java.util.HashMap<>();
        this.columnsToUpdate = new ArrayList<>();
        this.valuesToUpdate = new java.util.HashMap<>();
    }

    /**
     * Inicia una operación de inserción.
     */
    public InsertBuilder insert() {
        return new InsertBuilder(this);
    }

    /**
     * Inicia una operación de selección.
     */
    public SelectBuilder select() {
        return new SelectBuilder(this);
    }

    /**
     * Inicia una operación de actualización.
     */
    public UpdateBuilder update() {
        return new UpdateBuilder(this);
    }

    /**
     * Inicia una operación de eliminación.
     */
    public DeleteBuilder delete() {
        return new DeleteBuilder(this);
    }

    /**
     * Aplica un filtro WHERE con igualdad.
     */
    public TableOperations where(String column) {
        filters.add(new Filter(column, Filter.Operator.EQ, null));
        return this;
    }

    /**
     * Obtiene el nombre de la tabla.
     */
    String getTableName() {
        return tableName;
    }

    /**
     * Obtiene los filtros aplicados.
     */
    List<Filter> getFilters() {
        return filters;
    }

    /**
     * Obtiene la tabla por nombre.
     */
    Table getTable() {
        return db.getTable(tableName);
    }

    // Builder para INSERT
    public static class InsertBuilder {
        final TableOperations ops;
        private final Map<String, Object> values = new java.util.HashMap<>();

        InsertBuilder(TableOperations ops) {
            this.ops = ops;
        }

        public InsertBuilder value(String column, Object value) {
            values.put(column, value);
            return this;
        }

        public int execute() {
            try {
                Table table = ops.getTable();
                Row row = new Row(System.nanoTime(), values);
                table.insert(row);
                return 1;
            } catch (Exception e) {
                throw new RuntimeException("Error al insertar", e);
            }
        }
        }
    }

    // Builder para SELECT
    public static class SelectBuilder {
        final TableOperations ops;
        private List<String> columns = new ArrayList<>();

        SelectBuilder(TableOperations ops) {
            this.ops = ops;
        }

        public SelectBuilder columns(String... columns) {
            this.columns = List.of(columns);
            return this;
        }

        public SelectBuilder where(String column) {
            ops.where(column);
            return this;
        }

        public WhereCondition greaterThan(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter lastFilter = ops.getFilters().get(ops.getFilters().size() - 1);
                lastFilter.setOperator(Filter.Operator.GT);
                lastFilter.setValue(value);
            }
            return new WhereCondition(this);
        }

        public WhereCondition lessThan(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter lastFilter = ops.getFilters().get(ops.getFilters().size() - 1);
                lastFilter.setOperator(Filter.Operator.LT);
                lastFilter.setValue(value);
            }
            return new WhereCondition(this);
        }

        public WhereCondition equalTo(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter lastFilter = ops.getFilters().get(ops.getFilters().size() - 1);
                lastFilter.setOperator(Filter.Operator.EQ);
                lastFilter.setValue(value);
            }
            return new WhereCondition(this);
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> execute() {
            try {
                Table table = ops.getTable();
                List<Row> rows = table.readAll();
                List<Map<String, Object>> results = new ArrayList<>();
                
                for (Row row : rows) {
                    Map<String, Object> values = row.getValues();
                    if (matchesFilters(values)) {
                        results.add(values);
                    }
                }
                
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Error al seleccionar", e);
            }
        }
        
        private boolean matchesFilters(Map<String, Object> row) {
            for (Filter filter : ops.filters) {
                if (!filter.matches(row)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public List<Map<String, Object>> execute() {
            List<Map<String, Object>> results = new java.util.ArrayList<>();
            for (Map<String, Object> row : ops.table.readAll()) {
                if (matchesFilters(row)) {
                    if (columns.isEmpty()) {
                        results.add(new java.util.HashMap<>(row));
                    } else {
                        Map<String, Object> filteredRow = new java.util.HashMap<>();
                        for (String col : columns) {
                            if (row.containsKey(col)) {
                                filteredRow.put(col, row.get(col));
                            }
                        }
                        results.add(filteredRow);
                    }
                }
            }

            return results;
        }
    }

    // Builder para UPDATE
    public static class UpdateBuilder {
        final TableOperations ops;
        private final Map<String, Object> values = new java.util.HashMap<>();

        UpdateBuilder(TableOperations ops) {
            this.ops = ops;
        }

        public UpdateBuilder set(String column, Object value) {
            values.put(column, value);
            return this;
        }

        public UpdateBuilder where(String column) {
            ops.where(column);
            return this;
        }

        public WhereCondition equalTo(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter lastFilter = ops.getFilters().get(ops.getFilters().size() - 1);
                lastFilter.setOperator(Filter.Operator.EQ);
                lastFilter.setValue(value);
            }
            return new WhereCondition(this);
        }

        @SuppressWarnings("unchecked")
        public int execute() {
            Object tableObj = ops.getData().get(ops.getTableName());
            if (tableObj == null) {
                return 0;
            }

            Map<String, Object> table = (Map<String, Object>) tableObj;
            int count = 0;

            for (Map.Entry<String, Object> entry : table.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) entry.getValue();

                if (matchesFilters(row)) {
                    row.putAll(values);
                    count++;
                }
            }

            return count;
        }

        private boolean matchesFilters(Map<String, Object> row) {
            for (Filter filter : ops.getFilters()) {
                if (!filter.matches(row)) {
                    return false;
                }
            }
            return true;
        }
    }

    // Builder para DELETE
    public static class DeleteBuilder {
        final TableOperations ops;

        DeleteBuilder(TableOperations ops) {
            this.ops = ops;
        }

        public DeleteBuilder where(String column) {
            ops.where(column);
            return this;
        }

        public WhereCondition equalTo(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter lastFilter = ops.getFilters().get(ops.getFilters().size() - 1);
                lastFilter.setOperator(Filter.Operator.EQ);
                lastFilter.setValue(value);
            }
            return new WhereCondition(this);
        }

        @SuppressWarnings("unchecked")
        public int execute() {
            Object tableObj = ops.getData().get(ops.getTableName());
            if (tableObj == null) {
                return 0;
            }

            Map<String, Object> table = (Map<String, Object>) tableObj;
            List<String> keysToRemove = new ArrayList<>();

            for (Map.Entry<String, Object> entry : table.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) entry.getValue();

                if (matchesFilters(row)) {
                    keysToRemove.add(entry.getKey());
                }
            }

            keysToRemove.forEach(table::remove);
            return keysToRemove.size();
        }

        private boolean matchesFilters(Map<String, Object> row) {
            for (Filter filter : ops.getFilters()) {
                if (!filter.matches(row)) {
                    return false;
                }
            }
            return true;
        }
    }

    // Condición WHERE encadenable
    public static class WhereCondition {
        private final Object builder;

        WhereCondition(Object builder) {
            this.builder = builder;
        }

        public Object and(String column) {
            if (builder instanceof SelectBuilder) {
                ((SelectBuilder) builder).ops.where(column);
            } else if (builder instanceof UpdateBuilder) {
                ((UpdateBuilder) builder).ops.where(column);
            } else if (builder instanceof DeleteBuilder) {
                ((DeleteBuilder) builder).ops.where(column);
            }
            return this;
        }

        public WhereCondition greaterThan(Object value) {
            return applyCondition(Filter.Operator.GT, value);
        }

        public WhereCondition lessThan(Object value) {
            return applyCondition(Filter.Operator.LT, value);
        }

        public WhereCondition equalTo(Object value) {
            return applyCondition(Filter.Operator.EQ, value);
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> execute() {
            if (builder instanceof SelectBuilder) {
                return ((SelectBuilder) builder).execute();
            } else if (builder instanceof UpdateBuilder) {
                int count = ((UpdateBuilder) builder).execute();
                return List.of(Map.of("_updated", count));
            } else if (builder instanceof DeleteBuilder) {
                int count = ((DeleteBuilder) builder).execute();
                return List.of(Map.of("_deleted", count));
            }
            return List.of();
        }

        private WhereCondition applyCondition(Filter.Operator op, Object value) {
            if (builder instanceof SelectBuilder) {
                SelectBuilder sb = (SelectBuilder) builder;
                if (!sb.ops.getFilters().isEmpty()) {
                    Filter lastFilter = sb.ops.getFilters().get(sb.ops.getFilters().size() - 1);
                    lastFilter.setOperator(op);
                    lastFilter.setValue(value);
                }
            } else if (builder instanceof UpdateBuilder) {
                UpdateBuilder ub = (UpdateBuilder) builder;
                if (!ub.ops.getFilters().isEmpty()) {
                    Filter lastFilter = ub.ops.getFilters().get(ub.ops.getFilters().size() - 1);
                    lastFilter.setOperator(op);
                    lastFilter.setValue(value);
                }
            } else if (builder instanceof DeleteBuilder) {
                DeleteBuilder db = (DeleteBuilder) builder;
                if (!db.ops.getFilters().isEmpty()) {
                    Filter lastFilter = db.ops.getFilters().get(db.ops.getFilters().size() - 1);
                    lastFilter.setOperator(op);
                    lastFilter.setValue(value);
                }
            }
            return this;
        }
    }
