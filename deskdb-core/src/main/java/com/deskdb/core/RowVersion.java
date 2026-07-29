package com.deskdb.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a historical version of a row for time-travel queries.
 */
public class RowVersion {
    private final long rowId;
    private final Map<String, Object> values;
    private final LocalDateTime timestamp;
    private final String operation; // INSERT, UPDATE, DELETE
    private final Long userId;
    private final boolean isDeleted;
    private final LocalDateTime deletedAt;
    
    public RowVersion(long rowId, Map<String, Object> values, LocalDateTime timestamp, 
                      String operation, Long userId) {
        this.rowId = rowId;
        this.values = new HashMap<>(values);
        this.timestamp = timestamp;
        this.operation = operation;
        this.userId = userId;
        this.isDeleted = "DELETE".equals(operation);
        this.deletedAt = isDeleted ? timestamp : null;
    }
    
    /**
     * Constructor for soft delete support.
     */
    public RowVersion(long rowId, Map<String, Object> values, boolean isDeleted, LocalDateTime deletedAt) {
        this.rowId = rowId;
        this.values = new HashMap<>(values);
        this.timestamp = LocalDateTime.now();
        this.operation = isDeleted ? "DELETE" : "UPDATE";
        this.userId = null;
        this.isDeleted = isDeleted;
        this.deletedAt = deletedAt;
    }
    
    public long getRowId() {
        return rowId;
    }
    
    public Map<String, Object> getValues() {
        return values;
    }
    
    public Object get(String column) {
        return values.get(column);
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public Long getUserId() {
        return userId;
    }
    
    public boolean isDeleted() {
        return isDeleted;
    }
    
    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
