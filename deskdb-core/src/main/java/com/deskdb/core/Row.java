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
    public LambdaFieldCondition field(String fieldName) {
        // Create a FieldCondition that evaluates directly against this Row's values
        return new LambdaFieldCondition(this, fieldName);
    }

    /**
     * Special FieldCondition implementation that evaluates filters directly against a Row.
     * Used for lambda predicate evaluation in where() clauses.
     * Accumulates all conditions and evaluates them together.
     */
    public class LambdaFieldCondition {
        protected final Row row;
        protected final String fieldName;
        private boolean hasFailed = false;
        
        public LambdaFieldCondition(Row row, String fieldName) {
            this.row = row;
            this.fieldName = fieldName;
        }
        
        public String getFieldName() {
            return fieldName;
        }
        
        public LambdaFieldCondition eq(Object value) {
            Object fieldValue = row.get(fieldName);
            if (value == null ? fieldValue != null : !value.equals(fieldValue)) {
                hasFailed = true;
            }
            return this;
        }
        
        public LambdaFieldCondition ne(Object value) {
            Object fieldValue = row.get(fieldName);
            if (value == null ? fieldValue == null : value.equals(fieldValue)) {
                hasFailed = true;
            }
            return this;
        }
        
        public LambdaFieldCondition gt(Object value) {
            Object fieldValue = row.get(fieldName);
            if (fieldValue == null || !isGreaterThan(fieldValue, value)) {
                hasFailed = true;
            }
            return this;
        }
        
        public LambdaFieldCondition gte(Object value) {
            Object fieldValue = row.get(fieldName);
            if (fieldValue == null || !isGreaterThanOrEqual(fieldValue, value)) {
                hasFailed = true;
            }
            return this;
        }
        
        public LambdaFieldCondition lt(Object value) {
            Object fieldValue = row.get(fieldName);
            if (fieldValue == null || !isLessThan(fieldValue, value)) {
                hasFailed = true;
            }
            return this;
        }
        
        public LambdaFieldCondition lte(Object value) {
            Object fieldValue = row.get(fieldName);
            if (fieldValue == null || !isLessThanOrEqual(fieldValue, value)) {
                hasFailed = true;
            }
            return this;
        }
        
        /**
         * Returns the boolean result of the accumulated conditions.
         * This allows LambdaFieldCondition to be used as a Predicate<Row>.
         * @return true if no conditions have failed, false otherwise
         */
        public boolean getValue() {
            return !hasFailed;
        }
        
        public LambdaFieldCondition between(Object from, Object to) {
            Object fieldValue = row.get(fieldName);
            if (fieldValue == null || !isGreaterThanOrEqual(fieldValue, from) || !isLessThanOrEqual(fieldValue, to)) {
                hasFailed = true;
            }
            return this;
        }
        
        @SuppressWarnings("unchecked")
        protected boolean isGreaterThan(Object a, Object b) {
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
        protected boolean isGreaterThanOrEqual(Object a, Object b) {
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
        protected boolean isLessThan(Object a, Object b) {
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
        protected boolean isLessThanOrEqual(Object a, Object b) {
            if (a instanceof Comparable && b instanceof Comparable) {
                try {
                    return ((Comparable<Object>) a).compareTo(b) <= 0;
                } catch (ClassCastException e) {
                    return false;
                }
            }
            return false;
        }
        
        public LambdaFieldCondition and(String fieldName) {
            // Return a new condition for the new field, but preserve failure state
            LambdaFieldCondition newCond = new LambdaFieldCondition(row, fieldName);
            newCond.hasFailed = this.hasFailed;
            return newCond;
        }
        
        /**
         * Converts this condition to a boolean predicate for use in lambda expressions.
         * @return true if no conditions have failed, false otherwise
         */
        public boolean test() {
            return !hasFailed;
        }

        /**
         * Internal method to check if any condition has failed.
         * Used by Filter.matches() to determine if the lambda predicate passed.
         */
        public boolean hasFailed() {
            return hasFailed;
        }
    }

    /**
     * Wrapper around Row that tracks if any LambdaFieldCondition has failed.
     * Used by Filter.evaluateLambdaPredicate() to determine if a lambda filter passed.
     */
    public static class TrackingRow extends Row {
        private boolean hasFailed = false;
        
        public TrackingRow(Row wrapped) {
            super(wrapped.getRowId(), wrapped.getValues());
        }
        
        @Override
        public LambdaFieldCondition field(String fieldName) {
            LambdaFieldCondition condition = new LambdaFieldCondition(this, fieldName);
            // Override the condition to track failures in this TrackingRow
            return new TrackingLambdaFieldCondition(this, fieldName, condition);
        }
        
        public boolean hasFailed() {
            return hasFailed;
        }
        
        public void setFailed() {
            this.hasFailed = true;
        }
        
        /**
         * Wrapper around LambdaFieldCondition that reports failures to the TrackingRow.
         */
        private class TrackingLambdaFieldCondition extends LambdaFieldCondition {
            public TrackingLambdaFieldCondition(TrackingRow row, String fieldName, LambdaFieldCondition wrapped) {
                super(row, fieldName);
            }
            
            @Override
            public TrackingLambdaFieldCondition eq(Object value) {
                Object fieldValue = getFieldValue(fieldName);
                if (value == null ? fieldValue != null : !value.equals(fieldValue)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition ne(Object value) {
                Object fieldValue = getFieldValue(fieldName);
                if (value == null ? fieldValue == null : value.equals(fieldValue)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition gt(Object value) {
                Object fieldValue = getFieldValue(fieldName);
                if (fieldValue == null || !isGreaterThan(fieldValue, value)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition gte(Object value) {
                Object fieldValue = getFieldValue(fieldName);
                if (fieldValue == null || !isGreaterThanOrEqual(fieldValue, value)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition lt(Object value) {
                Object fieldValue = getFieldValue(fieldName);
                if (fieldValue == null || !isLessThan(fieldValue, value)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition lte(Object value) {
                Object fieldValue = getFieldValue(fieldName);
                if (fieldValue == null || !isLessThanOrEqual(fieldValue, value)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition between(Object from, Object to) {
                Object fieldValue = getFieldValue(fieldName);
                if (fieldValue == null || !isGreaterThanOrEqual(fieldValue, from) || !isLessThanOrEqual(fieldValue, to)) {
                    TrackingRow.this.setFailed();
                }
                return this;
            }
            
            @Override
            public TrackingLambdaFieldCondition and(String fieldName) {
                TrackingLambdaFieldCondition newCond = new TrackingLambdaFieldCondition(TrackingRow.this, fieldName, null);
                // Preserve failure state from parent condition
                if (TrackingRow.this.hasFailed()) {
                    TrackingRow.this.setFailed();
                }
                return newCond;
            }
            
            private Object getFieldValue(String fieldName) {
                return TrackingRow.this.get(fieldName);
            }
        }
    }
    
    @Override
    public String toString() {
        return "Row{id=" + rowId + ", values=" + values + "}";
    }
}
