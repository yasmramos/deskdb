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

    // Private constructor for full deserialization to ensure atomicity and immutability
    private Column(String name, DataType type, boolean primaryKey, boolean notNull, 
                   boolean unique, Object defaultValue) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Column type cannot be null");
        }
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.notNull = notNull;
        this.unique = unique;
        this.defaultValue = defaultValue;
    }

    public Column(String name, DataType type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Column type cannot be null");
        }
        this.name = name;
        this.type = type;
    }

    // Package-private method for deserialization - creates immutable instance with full state
    static Column deserialize(String name, DataType type, boolean primaryKey, boolean notNull, 
                              boolean unique, Object defaultValue) {
        return new Column(name, type, primaryKey, notNull, unique, defaultValue);
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
    
    // Removed mutable setters to ensure thread-safety and immutability after construction
    // Deserialization now uses the static deserialize() method for atomic creation
}
