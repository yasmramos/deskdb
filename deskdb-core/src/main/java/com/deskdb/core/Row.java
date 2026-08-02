package com.deskdb.core;

import java.util.*;

/**
 * Representa una fila de datos en una tabla.
 */
public class Row {
    private final long rowId;
    private final Map<String, Object> values;

    public Row(long rowId) {
        this.rowId = rowId;
        this.values = new LinkedHashMap<>();
    }

    public Row(long rowId, Map<String, Object> values) {
        this.rowId = rowId;
        this.values = new LinkedHashMap<>(values);
    }

    public long getRowId() {
        return rowId;
    }

    public Object get(String column) {
        return values.get(column);
    }

    public void set(String column, Object value) {
        values.put(column, value);
    }

    public Map<String, Object> getValues() {
        return new LinkedHashMap<>(values);
    }

    public Set<String> getColumns() {
        return values.keySet();
    }

    /**
     * Returns a FieldCondition builder for lambda-based predicates.
     * This enables fluent API syntax like: row -> row.field("age").gt(30)
     * 
     * @param fieldName the field name to build condition on
     * @return FieldCondition builder for this field that evaluates against this Row
     */
    public com.deskdb.query.SelectBuilder.FieldCondition field(String fieldName) {
        // Create a FieldCondition that evaluates directly against this Row's values
        return new RowFieldCondition(fieldName, this);
    }

    /**
     * Special FieldCondition implementation that evaluates filters directly against a Row.
     * Used for lambda predicate evaluation in where() clauses.
     */
    private class RowFieldCondition extends com.deskdb.query.SelectBuilder.FieldCondition {
        private final Row row;
        
        public RowFieldCondition(String fieldName, Row row) {
            super(fieldName, null); // Pass null WhereConditionBuilder since we evaluate directly
            this.row = row;
        }
        
        @Override
        public RowFieldCondition eq(Object value) {
            Object fieldValue = row.get(getFieldName());
            if (value == null ? fieldValue != null : !value.equals(fieldValue)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " != " + value);
            }
            return this;
        }
        
        @Override
        public RowFieldCondition ne(Object value) {
            Object fieldValue = row.get(getFieldName());
            if (value == null ? fieldValue == null : value.equals(fieldValue)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " == " + value);
            }
            return this;
        }
        
        @Override
        public RowFieldCondition gt(Object value) {
            Object fieldValue = row.get(getFieldName());
            if (fieldValue == null || !isGreaterThan(fieldValue, value)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " not > " + value);
            }
            return this;
        }
        
        @Override
        public RowFieldCondition gte(Object value) {
            Object fieldValue = row.get(getFieldName());
            if (fieldValue == null || !isGreaterThanOrEqual(fieldValue, value)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " not >= " + value);
            }
            return this;
        }
        
        @Override
        public RowFieldCondition lt(Object value) {
            Object fieldValue = row.get(getFieldName());
            if (fieldValue == null || !isLessThan(fieldValue, value)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " not < " + value);
            }
            return this;
        }
        
        @Override
        public RowFieldCondition lte(Object value) {
            Object fieldValue = row.get(getFieldName());
            if (fieldValue == null || !isLessThanOrEqual(fieldValue, value)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " not <= " + value);
            }
            return this;
        }
        
        @Override
        public RowFieldCondition between(Object from, Object to) {
            Object fieldValue = row.get(getFieldName());
            if (fieldValue == null || !isGreaterThanOrEqual(fieldValue, from) || !isLessThanOrEqual(fieldValue, to)) {
                throw new RuntimeException("Filter evaluation failed: " + getFieldName() + " not between " + from + " and " + to);
            }
            return this;
        }
        
        @SuppressWarnings("unchecked")
        private boolean isGreaterThan(Object a, Object b) {
            if (a instanceof Comparable && b instanceof Comparable) {
                try {
                    return ((Comparable<Object>) a).compareTo(b) > 0;
                } catch (ClassCastException e) {
                    return false;
                }
            }
            return false;
        }
        
        @SuppressWarnings("unchecked")
        private boolean isGreaterThanOrEqual(Object a, Object b) {
            if (a instanceof Comparable && b instanceof Comparable) {
                try {
                    return ((Comparable<Object>) a).compareTo(b) >= 0;
                } catch (ClassCastException e) {
                    return false;
                }
            }
            return false;
        }
        
        @SuppressWarnings("unchecked")
        private boolean isLessThan(Object a, Object b) {
            if (a instanceof Comparable && b instanceof Comparable) {
                try {
                    return ((Comparable<Object>) a).compareTo(b) < 0;
                } catch (ClassCastException e) {
                    return false;
                }
            }
            return false;
        }
        
        @SuppressWarnings("unchecked")
        private boolean isLessThanOrEqual(Object a, Object b) {
            if (a instanceof Comparable && b instanceof Comparable) {
                try {
                    return ((Comparable<Object>) a).compareTo(b) <= 0;
                } catch (ClassCastException e) {
                    return false;
                }
            }
            return false;
        }
        
        @Override
        public RowFieldCondition and(String fieldName) {
            return new RowFieldCondition(fieldName, row);
        }
    }

    @Override
    public String toString() {
        return "Row{id=" + rowId + ", values=" + values + "}";
    }
}
