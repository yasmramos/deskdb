package com.deskdb.core;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;

public class Filter {
    private String column;
    private Operator operator;
    private Object value;
    private Object value2; // Para BETWEEN (valor superior)
    private LogicalOperator logicalOp; // Para combinar filtros (AND/OR)
    private List<Filter> children; // Para condiciones anidadas
    private Predicate<Row> lambdaPredicate; // Para filtros basados en lambda
    
    public enum Operator { EQ, GT, LT, GTE, LTE, NEQ, NE, BETWEEN, ALL }
    public enum LogicalOperator { AND, OR, NONE }

    public Filter(String column, Operator operator, Object value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
        this.logicalOp = LogicalOperator.NONE;
        this.children = new ArrayList<>();
    }
    
    // Constructor para BETWEEN
    public Filter(String column, Operator operator, Object value, Object value2) {
        this.column = column;
        this.operator = operator;
        this.value = value;
        this.value2 = value2;
        this.logicalOp = LogicalOperator.NONE;
        this.children = new ArrayList<>();
    }
    
    // Constructor para filtro compuesto (AND/OR)
    public Filter(LogicalOperator logicalOp, Filter... children) {
        this.column = null;
        this.operator = Operator.ALL;
        this.logicalOp = logicalOp;
        this.children = new ArrayList<>();
        for (Filter child : children) {
            this.children.add(child);
        }
    }
    
    // Constructor para filtro lambda
    public Filter(Predicate<Row> predicate) {
        this.column = null;
        this.operator = Operator.ALL;
        this.logicalOp = LogicalOperator.NONE;
        this.children = new ArrayList<>();
        this.lambdaPredicate = predicate;
    }

    public String getColumn() { return column; }
    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public Object getValue2() { return value2; }
    public void setValue2(Object value2) { this.value2 = value2; }
    public LogicalOperator getLogicalOp() { return logicalOp; }
    public List<Filter> getChildren() { return children; }
    public Predicate<Row> getLambdaPredicate() { return lambdaPredicate; }
    
    // Métodos para construir condiciones compuestas
    public Filter and(Filter other) {
        return new Filter(LogicalOperator.AND, this, other);
    }
    
    public Filter or(Filter other) {
        return new Filter(LogicalOperator.OR, this, other);
    }

    @SuppressWarnings("unchecked")
    public boolean matches(Map<String, Object> row) {
        // Si es un filtro lambda, evaluar el predicado directamente
        if (lambdaPredicate != null) {
            Row rowObj = new Row(-1, row);
            return lambdaPredicate.test(rowObj);
        }
        
        // Si es un filtro compuesto (AND/OR), evaluar hijos recursivamente
        if (logicalOp != LogicalOperator.NONE && !children.isEmpty()) {
            switch (logicalOp) {
                case AND:
                    for (Filter child : children) {
                        if (!child.matches(row)) return false;
                    }
                    return true;
                case OR:
                    for (Filter child : children) {
                        if (child.matches(row)) return true;
                    }
                    return false;
                default:
                    break;
            }
        }
        
        // Filtro simple - validar que tenga columna válida
        if (column == null || operator == Operator.ALL) return true;
        if (!row.containsKey(column)) return false;
        Object rowValue = row.get(column);
        switch (operator) {
            case EQ: return safeEquals(rowValue, value);
            case NEQ: 
            case NE: return !safeEquals(rowValue, value);
            case GT: return safeCompare(rowValue, value) > 0;
            case LT: return safeCompare(rowValue, value) < 0;
            case GTE: return safeCompare(rowValue, value) >= 0;
            case LTE: return safeCompare(rowValue, value) <= 0;
            case BETWEEN: 
                return value2 != null && 
                       safeCompare(rowValue, value) >= 0 && 
                       safeCompare(rowValue, value2) <= 0;
            case ALL: return true;
            default: return false;
        }
    }

    public boolean apply(Row row) { 
        // Si es filtro lambda, evaluar directamente con Predicate.test()
        if (lambdaPredicate != null) {
            return lambdaPredicate.test(row);
        }
        return matches(row.getValues()); 
    }
    
    private boolean safeEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int safeCompare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        
        // Si ambos son del mismo tipo y Comparable, comparar directamente
        if (a instanceof Comparable && b instanceof Comparable) {
            if (a.getClass() == b.getClass()) {
                return ((Comparable) a).compareTo(b);
            }
            // Intentar coerción numérica si ambos son Number
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            // Fallback: comparación lexicográfica como String
            return a.toString().compareTo(b.toString());
        }
        // Si no son Comparable, usar comparación lexicográfica
        return a.toString().compareTo(b.toString());
    }
}
