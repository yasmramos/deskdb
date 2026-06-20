package com.deskdb.core;

/**
 * Tipos de datos soportados por DeskDB.
 */
public enum DataType {
    STRING,      // UTF-8, longitud variable
    INT,         // 4 bytes
    LONG,        // 8 bytes
    DOUBLE,      // 8 bytes
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
}
