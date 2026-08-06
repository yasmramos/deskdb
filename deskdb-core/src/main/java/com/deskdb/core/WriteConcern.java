package com.deskdb.core;

/**
 * Write concern levels for controlling durability vs performance trade-offs.
 */
public enum WriteConcern {
    /**
     * Async writes - data is written to memory only.
     * Fastest but may lose data on crash.
     */
    ASYNC,
    
    /**
     * Normal writes - data is written to WAL but fsync is batched.
     * Good balance between performance and durability.
     */
    NORMAL,
    
    /**
     * Safe writes - data is written and fsync'd immediately.
     * Slowest but guarantees durability on each write.
     */
    SAFE
}
