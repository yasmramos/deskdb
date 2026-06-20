package com.deskdb.core;

/**
 * Tipos de datos soportados por DeskDB.
 */
public enum DataType {
    STRING,      // UTF-8
    INT,         // 4 bytes
    LONG,        // 8 bytes
    DOUBLE,      // 8 bytes
    BOOLEAN,     // 1 byte
    DATE,        // 8 bytes (epoch millis)
    TIMESTAMP,   // 8 bytes (epoch millis) + nanos
    BLOB,        // Binario
    JSON         // Texto JSON
}
