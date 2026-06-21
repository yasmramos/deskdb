package com.deskdb.core;

import java.util.Map;

public class Filter {
    private String column;
    private Operator operator;
    private Object value;

    public enum Operator { EQ, GT, LT, GTE, LTE, NEQ, ALL }

    public Filter(String column, Operator operator, Object value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
    }

    public String getColumn() { return column; }
    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    @SuppressWarnings("unchecked")
    public boolean matches(Map<String, Object> row) {
        if (column == null && operator == Operator.ALL) return true;
        if (!row.containsKey(column)) return false;
        Object rowValue = row.get(column);
        switch (operator) {
            case EQ: return safeEquals(rowValue, value);
            case NEQ: return !safeEquals(rowValue, value);
            case GT: return safeCompare(rowValue, value) > 0;
            case LT: return safeCompare(rowValue, value) < 0;
            case GTE: return safeCompare(rowValue, value) >= 0;
            case LTE: return safeCompare(rowValue, value) <= 0;
            case ALL: return true;
            default: return false;
        }
    }

    public boolean apply(Row row) { return matches(row.getValues()); }

    private boolean safeEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @SuppressWarnings("rawtypes")
    private int safeCompare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo((Comparable) b);
        }
        return a.toString().compareTo(b.toString());
    }
}
