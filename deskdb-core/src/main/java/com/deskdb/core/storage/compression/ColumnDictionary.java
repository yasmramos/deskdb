package com.deskdb.core.storage.compression;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dictionary Encoding implementation for columnar compression.
 * Maps repeated values to integer IDs to save space and speed up comparisons.
 * 
 * Format in ByteBuffer:
 * [Dict Size (4)] [Entry1 Len (4)][Entry1 Data][Entry2 Len (4)][Entry2 Data]... [Data Start Offset (4)] [Encoded Data...]
 */
public class ColumnDictionary {
    
    // Forward map: Value String -> ID
    private final Map<String, Integer> valueToId = new ConcurrentHashMap<>();
    // Reverse map: ID -> Value String
    private final List<String> idToValue = new ArrayList<>();
    
    private final String columnName;
    
    // Threshold to enable dictionary encoding (if unique values < total rows * threshold)
    private static final double CARDINALITY_THRESHOLD = 0.1; // 10% unique values triggers dict
    private static final int MIN_ROWS_FOR_DICT = 10;

    public ColumnDictionary(String columnName) {
        this.columnName = columnName;
    }

    /**
     * Adds a value to the dictionary if not present, returns its ID.
     */
    public synchronized int putOrGet(String value) {
        return valueToId.computeIfAbsent(value, k -> {
            int newId = idToValue.size();
            idToValue.add(k);
            return newId;
        });
    }

    /**
     * Gets the original value from an ID.
     */
    public synchronized String get(int id) {
        if (id < 0 || id >= idToValue.size()) {
            throw new IllegalArgumentException("Invalid dictionary ID: " + id + " for column: " + columnName);
        }
        return idToValue.get(id);
    }

    /**
     * Checks if dictionary encoding is beneficial for the current data distribution.
     */
    public boolean shouldUseDictionary(int totalRows) {
        if (totalRows < MIN_ROWS_FOR_DICT) {
            return false;
        }
        double ratio = (double) valueToId.size() / totalRows;
        return ratio <= CARDINALITY_THRESHOLD;
    }

    /**
     * Clears the dictionary (for reset/rebuild).
     */
    public synchronized void clear() {
        valueToId.clear();
        idToValue.clear();
    }

    public int getSize() {
        return valueToId.size();
    }

    /**
     * Writes the dictionary to the ByteBuffer.
     * Format: [Count (4)] [Len1 (4) Data1] [Len2 (4) Data2] ...
     */
    public synchronized void writeToBuffer(ByteBuffer buffer) {
        buffer.putInt(idToValue.size());
        for (String val : idToValue) {
            byte[] bytes = val.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }
    }

    /**
     * Reads the dictionary from the ByteBuffer and populates internal maps.
     * Validates length values to prevent BufferUnderflowException and OutOfMemoryError
     * from corrupted or malicious data.
     */
    public synchronized void readFromBuffer(ByteBuffer buffer) {
        clear();
        
        if (buffer.remaining() < 4) {
            throw new IllegalArgumentException("Corrupted data: insufficient bytes for dictionary count");
        }
        
        int count = buffer.getInt();
        
        if (count < 0) {
            throw new IllegalArgumentException("Corrupted data: negative dictionary count: " + count);
        }
        
        // Sanity check to prevent OOM with extremely large counts
        if (count > 1_000_000) {
            throw new IllegalArgumentException("Corrupted data: unreasonably large dictionary count: " + count);
        }
        
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException("Corrupted data: insufficient bytes for string length at index " + i);
            }
            
            int len = buffer.getInt();
            
            if (len < 0) {
                throw new IllegalArgumentException("Corrupted data: negative string length at index " + i + ": " + len);
            }
            
            if (len > 10_000_000) {
                throw new IllegalArgumentException("Corrupted data: unreasonably large string length at index " + i + ": " + len);
            }
            
            if (buffer.remaining() < len) {
                throw new IllegalArgumentException("Corrupted data: insufficient bytes for string data at index " + i + 
                    ". Expected " + len + " bytes, but only " + buffer.remaining() + " available");
            }
            
            byte[] bytes = new byte[len];
            buffer.get(bytes);
            String val = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            putOrGet(val);
        }
    }
}
