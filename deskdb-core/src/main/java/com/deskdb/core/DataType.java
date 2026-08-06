package com.deskdb.core;

/**
 * Tipos de datos soportados por DeskDB.
 */
public enum DataType {
    STRING,      // UTF-8, longitud variable
    INT,         // 4 bytes
    LONG,        // 8 bytes
    DOUBLE,      // 8 bytes
    DECIMAL,     // BigDecimal, precisión arbitraria
    BOOLEAN,     // 1 byte
    DATE,        // 8 bytes (epoch millis)
    TIMESTAMP,   // 8 bytes (epoch millis) + nanos
    BLOB,        // Binario, longitud variable
    JSON;        // Texto JSON, longitud variable
    
    /**
     * Obtiene el tamaño máximo en bytes para este tipo.
     * Para tipos de longitud variable, devuelve Integer.MAX_VALUE.
     */
    public int getMaxSize() {
        switch (this) {
            case BOOLEAN:
                return 1;
            case INT:
                return 4;
            case LONG:
            case DOUBLE:
            case DECIMAL:
            case DATE:
            case TIMESTAMP:
                return 8;
            case STRING:
            case BLOB:
            case JSON:
                return Integer.MAX_VALUE; // Variable length
            default:
                throw new IllegalArgumentException("Unknown type: " + this);
        }
    }
    
    /**
     * Obtiene el tamaño fijo en bytes, o -1 si es variable.
     */
    public int getFixedSize() {
        switch (this) {
            case BOOLEAN:
                return 1;
            case INT:
                return 4;
            case LONG:
            case DOUBLE:
            case DECIMAL:
            case DATE:
            case TIMESTAMP:
                return 8;
            case STRING:
            case BLOB:
            case JSON:
                return -1; // Variable length
            default:
                throw new IllegalArgumentException("Unknown type: " + this);
        }
    }
    
    /**
     * Indica si este tipo tiene longitud variable.
     */
    public boolean isVariableLength() {
        return getFixedSize() == -1;
    }
    
    /**
     * Verifica si una clase Java es compatible con este tipo de dato.
     * @param clazz la clase a verificar
     * @return true si la clase es compatible con este DataType
     */
    public boolean isCompatible(Class<?> clazz) {
        if (clazz == null) {
            return true; // null es compatible con cualquier tipo
        }
        
        switch (this) {
            case STRING:
            case JSON:
                return String.class.isAssignableFrom(clazz);
            case INT:
                return Integer.class.isAssignableFrom(clazz) || int.class == clazz;
            case LONG:
                return Long.class.isAssignableFrom(clazz) || long.class == clazz;
            case DOUBLE:
                return Double.class.isAssignableFrom(clazz) || double.class == clazz;
            case DECIMAL:
                return java.math.BigDecimal.class.isAssignableFrom(clazz);
            case BOOLEAN:
                return Boolean.class.isAssignableFrom(clazz) || boolean.class == clazz;
            case DATE:
                return java.util.Date.class.isAssignableFrom(clazz);
            case TIMESTAMP:
                return java.sql.Timestamp.class.isAssignableFrom(clazz) ||
                       java.util.Date.class.isAssignableFrom(clazz);
            case BLOB:
                return byte[].class == clazz;
            default:
                return false;
        }
    }
}
