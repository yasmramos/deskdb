package com.deskdb.core;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages automatic versioning for Time Travel queries.
 * Each row modification creates a new version with timestamp metadata.
 */
public class VersionManager {
    
    /**
     * Stores all versions of each row.
     * Key: rowId, Value: List of RowVersion sorted by timestamp (newest first)
     */
    private final Map<Long, List<RowVersion>> versionHistory = new ConcurrentHashMap<>();
    
    /**
     * Creates a new version of a row.
     * 
     * @param rowId the row ID
     * @param values the row values
     * @param isDeleted whether the row is marked as deleted
     * @param deletedAt timestamp when deleted (null if not deleted)
     * @return the created RowVersion
     */
    public RowVersion createVersion(long rowId, Map<String, Object> values, boolean isDeleted, LocalDateTime deletedAt) {
        RowVersion version = new RowVersion(rowId, values, isDeleted, deletedAt);
        
        versionHistory.computeIfAbsent(rowId, k -> new ArrayList<>()).add(0, version);
        
        return version;
    }
    
    /**
     * Gets the current version of a row.
     * 
     * @param rowId the row ID
     * @return the current RowVersion or null if not found
     */
    public RowVersion getCurrentVersion(long rowId) {
        List<RowVersion> versions = versionHistory.get(rowId);
        return (versions != null && !versions.isEmpty()) ? versions.get(0) : null;
    }
    
    /**
     * Gets the state of a row as of a specific point in time.
     * 
     * @param rowId the row ID
     * @param asOf the point in time to query
     * @return the RowVersion as of that time, or null if not found
     */
    public RowVersion getVersionAsOf(long rowId, LocalDateTime asOf) {
        List<RowVersion> versions = versionHistory.get(rowId);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        
        // Find the most recent version before or at the specified time
        for (RowVersion version : versions) {
            if (!version.getTimestamp().isAfter(asOf)) {
                return version;
            }
        }
        
        return null;
    }
    
    /**
     * Gets the full history of a row.
     * 
     * @param rowId the row ID
     * @return list of all versions sorted by timestamp (newest first)
     */
    public List<RowVersion> getHistory(long rowId) {
        List<RowVersion> versions = versionHistory.get(rowId);
        return (versions != null) ? new ArrayList<>(versions) : new ArrayList<>();
    }
    
    /**
     * Gets all rows that existed at a specific point in time.
     * 
     * @param asOf the point in time to query
     * @return map of rowId to RowVersion for all rows that existed at that time
     */
    public Map<Long, RowVersion> getAllVersionsAsOf(LocalDateTime asOf) {
        Map<Long, RowVersion> result = new HashMap<>();
        
        for (Map.Entry<Long, List<RowVersion>> entry : versionHistory.entrySet()) {
            long rowId = entry.getKey();
            RowVersion version = getVersionAsOf(rowId, asOf);
            if (version != null && !version.isDeleted()) {
                result.put(rowId, version);
            }
        }
        
        return result;
    }
    
    /**
     * Clears version history for a specific row.
     * 
     * @param rowId the row ID
     */
    public void clearHistory(long rowId) {
        versionHistory.remove(rowId);
    }
    
    /**
     * Clears all version history.
     */
    public void clearAll() {
        versionHistory.clear();
    }
    
    /**
     * Gets the number of versions for a specific row.
     * 
     * @param rowId the row ID
     * @return the number of versions
     */
    public int getVersionCount(long rowId) {
        List<RowVersion> versions = versionHistory.get(rowId);
        return (versions != null) ? versions.size() : 0;
    }
}
