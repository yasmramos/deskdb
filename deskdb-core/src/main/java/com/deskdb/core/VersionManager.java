package com.deskdb.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * @return Optional containing the current RowVersion or empty if not found
     */
    public Optional<RowVersion> getCurrentVersion(long rowId) {
        List<RowVersion> versions = versionHistory.get(rowId);
        return (versions != null && !versions.isEmpty()) ? Optional.of(versions.get(0)) : Optional.empty();
    }

    /**
     * Gets the state of a row as of a specific point in time.
     * 
     * @param rowId the row ID
     * @param timestamp the point in time to query
     * @return Optional containing the RowVersion at that time or empty if not found
     */
    public Optional<RowVersion> getVersionAsOf(long rowId, LocalDateTime timestamp) {
        List<RowVersion> versions = versionHistory.get(rowId);
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        
        return versions.stream()
            .filter(v -> v.getTimestamp().isBefore(timestamp) || v.getTimestamp().isEqual(timestamp))
            .findFirst();
    }

    /**
     * Gets all historical versions of a row.
     * 
     * @param rowId the row ID
     * @return unmodifiable list of all versions sorted by timestamp (newest first)
     */
    public List<RowVersion> getAllVersions(long rowId) {
        List<RowVersion> versions = versionHistory.get(rowId);
        return versions != null ? Collections.unmodifiableList(new ArrayList<>(versions)) : Collections.emptyList();
    }

    /**
     * Deletes all versions of a row (for cleanup).
     * 
     * @param rowId the row ID to purge
     */
    public void purgeVersions(long rowId) {
        versionHistory.remove(rowId);
    }

    /**
     * Clears all version history.
     */
    public void clear() {
        versionHistory.clear();
    }

    /**
     * Gets the total number of versioned rows.
     * 
     * @return count of unique row IDs with version history
     */
    public int getVersionedRowCount() {
        return versionHistory.size();
    }

    /**
     * Gets the total number of versions across all rows.
     * 
     * @return total version count
     */
    public long getTotalVersionCount() {
        return versionHistory.values().stream()
            .mapToLong(List::size)
            .sum();
    }
}
