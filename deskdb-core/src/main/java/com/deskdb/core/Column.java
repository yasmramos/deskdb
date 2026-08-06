package com.deskdb.core;

/**
 * Defines a column in a DeskDB table.
 */
public class Column {
    private final String name;
    private final DataType type;
    private boolean primaryKey;
    private boolean notNull;
    private boolean unique;
    private Object defaultValue;

    public Column(String name, DataType type) {
        this.name = name;
        this.type = type;
    }

    public Column primaryKey() {
        this.primaryKey = true;
        return this;
    }

    public Column notNull() {
        this.notNull = true;
        return this;
    }

    public Column unique() {
        this.unique = true;
        return this;
    }

    public Column defaultValue(Object value) {
        if (value != null && !this.type.isCompatible(value.getClass())) {
            throw new IllegalArgumentException("Default value type mismatch for column " + name + 
                ": expected " + this.type + " but got " + value.getClass().getSimpleName());
        }
        this.defaultValue = value;
        return this;
    }

    // Getters
    public String getName() { return name; }
    public DataType getType() { return type; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isNotNull() { return notNull; }
    public boolean isUnique() { return unique; }
    public Object getDefaultValue() { return defaultValue; }
    
    // Setters for deserialization
    void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
    void setNotNull(boolean notNull) { this.notNull = notNull; }
    void setUnique(boolean unique) { this.unique = unique; }
    void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
}
