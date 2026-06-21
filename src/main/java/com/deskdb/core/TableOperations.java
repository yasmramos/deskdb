package com.deskdb.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TableOperations {
    private final DeskDB db;
    private final String tableName;
    private final List<Filter> filters;

    TableOperations(DeskDB db, String tableName) {
        this.db = db;
        this.tableName = tableName;
        this.filters = new ArrayList<>();
    }

    public InsertBuilder insert() { return new InsertBuilder(this); }
    public SelectBuilder select() { return new SelectBuilder(this); }
    public UpdateBuilder update() { return new UpdateBuilder(this); }
    public DeleteBuilder delete() { return new DeleteBuilder(this); }

    public TableOperations where(String column) {
        filters.add(new Filter(column, Filter.Operator.EQ, null));
        return this;
    }

    String getTableName() { return tableName; }
    List<Filter> getFilters() { return filters; }
    Table getTable() { return db.getTable(tableName); }

    public static class InsertBuilder {
        private final TableOperations ops;
        private final Map<String, Object> values = new HashMap<>();

        InsertBuilder(TableOperations ops) { this.ops = ops; }

        public InsertBuilder value(String column, Object value) {
            values.put(column, value);
            return this;
        }

        public int execute() {
            try {
                Table table = ops.getTable();
                table.insert(values);
                return 1;
            } catch (Exception e) {
                throw new RuntimeException("Error al insertar", e);
            }
        }
    }

    public static class SelectBuilder {
        private final TableOperations ops;

        SelectBuilder(TableOperations ops) { this.ops = ops; }

        public SelectBuilder where(String column) {
            ops.where(column);
            return this;
        }

        public WhereCondition greaterThan(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter f = ops.getFilters().get(ops.getFilters().size() - 1);
                f.setOperator(Filter.Operator.GT);
                f.setValue(value);
            }
            return new WhereCondition(this);
        }

        public WhereCondition lessThan(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter f = ops.getFilters().get(ops.getFilters().size() - 1);
                f.setOperator(Filter.Operator.LT);
                f.setValue(value);
            }
            return new WhereCondition(this);
        }

        public WhereCondition equalTo(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter f = ops.getFilters().get(ops.getFilters().size() - 1);
                f.setOperator(Filter.Operator.EQ);
                f.setValue(value);
            }
            return new WhereCondition(this);
        }

        public List<Map<String, Object>> execute() {
            try {
                Table table = ops.getTable();
                List<Filter> filters = ops.getFilters();
                List<Row> rows = filters.isEmpty() ? table.select(new ArrayList<>()) : table.select(filters);
                return rows.stream().map(Row::getValues).collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException("Error al seleccionar", e);
            }
        }
    }

    public static class UpdateBuilder {
        private final TableOperations ops;
        private final Map<String, Object> values = new HashMap<>();

        UpdateBuilder(TableOperations ops) { this.ops = ops; }

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
                Filter f = ops.getFilters().get(ops.getFilters().size() - 1);
                f.setOperator(Filter.Operator.EQ);
                f.setValue(value);
            }
            return new WhereCondition(this);
        }

        public int execute() {
            try {
                Table table = ops.getTable();
                List<Filter> filters = ops.getFilters();
                if (filters.isEmpty()) throw new IllegalStateException("UPDATE requiere WHERE");
                Filter combined = filters.get(0);
                for (int i = 1; i < filters.size(); i++) {
                    combined = new CompoundFilter(combined, filters.get(i));
                }
                return table.update(combined, values);
            } catch (Exception e) {
                throw new RuntimeException("Error al actualizar", e);
            }
        }
    }

    public static class DeleteBuilder {
        private final TableOperations ops;

        DeleteBuilder(TableOperations ops) { this.ops = ops; }

        public DeleteBuilder where(String column) {
            ops.where(column);
            return this;
        }

        public WhereCondition equalTo(Object value) {
            if (!ops.getFilters().isEmpty()) {
                Filter f = ops.getFilters().get(ops.getFilters().size() - 1);
                f.setOperator(Filter.Operator.EQ);
                f.setValue(value);
            }
            return new WhereCondition(this);
        }

        public int execute() {
            try {
                Table table = ops.getTable();
                List<Filter> filters = ops.getFilters();
                if (filters.isEmpty()) throw new IllegalStateException("DELETE requiere WHERE");
                Filter combined = filters.get(0);
                for (int i = 1; i < filters.size(); i++) {
                    combined = new CompoundFilter(combined, filters.get(i));
                }
                return table.delete(combined);
            } catch (Exception e) {
                throw new RuntimeException("Error al eliminar", e);
            }
        }
    }

    public static class WhereCondition {
        private final Object builder;
        WhereCondition(Object builder) { this.builder = builder; }

        public Object and(String column) {
            if (builder instanceof SelectBuilder) ((SelectBuilder) builder).ops.where(column);
            else if (builder instanceof UpdateBuilder) ((UpdateBuilder) builder).ops.where(column);
            else if (builder instanceof DeleteBuilder) ((DeleteBuilder) builder).ops.where(column);
            return this;
        }

        public WhereCondition greaterThan(Object value) { return applyCondition(Filter.Operator.GT, value); }
        public WhereCondition lessThan(Object value) { return applyCondition(Filter.Operator.LT, value); }
        public WhereCondition equalTo(Object value) { return applyCondition(Filter.Operator.EQ, value); }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> execute() {
            if (builder instanceof SelectBuilder) return ((SelectBuilder) builder).execute();
            else if (builder instanceof UpdateBuilder) {
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
                    Filter f = sb.ops.getFilters().get(sb.ops.getFilters().size() - 1);
                    f.setOperator(op);
                    f.setValue(value);
                }
            } else if (builder instanceof UpdateBuilder) {
                UpdateBuilder ub = (UpdateBuilder) builder;
                if (!ub.ops.getFilters().isEmpty()) {
                    Filter f = ub.ops.getFilters().get(ub.ops.getFilters().size() - 1);
                    f.setOperator(op);
                    f.setValue(value);
                }
            } else if (builder instanceof DeleteBuilder) {
                DeleteBuilder d = (DeleteBuilder) builder;
                if (!d.ops.getFilters().isEmpty()) {
                    Filter f = d.ops.getFilters().get(d.ops.getFilters().size() - 1);
                    f.setOperator(op);
                    f.setValue(value);
                }
            }
            return this;
        }
    }

    static class CompoundFilter extends Filter {
        private final Filter left, right;
        CompoundFilter(Filter l, Filter r) { super(null, Operator.ALL, null); left = l; right = r; }
        @Override
        public boolean matches(Map<String, Object> row) { return left.matches(row) && right.matches(row); }
    }
}
