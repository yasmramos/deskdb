package com.deskdb.index;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages secondary indexes for a table.
 * 
 * This class encapsulates all index-related operations, providing:
 * - Thread-safe index management
 * - Efficient index creation and deletion
 * - Optimized index updates during CRUD operations
 * - Support for multiple index types via BTree
 * 
 * Benefits:
 * - Decouples index logic from Table class
 * - Improves testability
 * - Enables future index type extensions (Hash, Bitmap, etc.)
 */
public class SecondaryIndexManager {
    
    private final Map<String, BTree> indexes = new ConcurrentHashMap<>();
    private final Map<String, String> columnToIndex = new ConcurrentHashMap<>();
    
    /**
     * Creates a new secondary index on the specified column.
     * 
     * @param indexName Name of the index
     * @param columnName Column to index
     * @param order Order of the B-Tree (branching factor)
     * @return true if index created, false if already exists
     */
    public boolean createIndex(String indexName, String columnName, int order) {
        if (columnToIndex.containsKey(columnName)) {
            return false; // Index already exists for this column
        }
        
        BTree btree = new BTree(indexName, order);
        indexes.put(indexName, btree);
        columnToIndex.put(columnName, indexName);
        return true;
    }
    
    /**
     * Removes an index by column name.
     * 
     * @param columnName Column associated with the index
     * @return true if index was removed, false if not found
     */
    public boolean removeIndex(String columnName) {
        String indexName = columnToIndex.remove(columnName);
        if (indexName != null) {
            indexes.remove(indexName);
            return true;
        }
        return false;
    }
    
    /**
     * Gets the BTree index for a specific column.
     * 
     * @param columnName Column name
     * @return BTree index or null if not found
     */
    public BTree getIndex(String columnName) {
        String indexName = columnToIndex.get(columnName);
        return (indexName != null) ? indexes.get(indexName) : null;
    }
    
    /**
     * Checks if an index exists for a column.
     * 
     * @param columnName Column name
     * @return true if index exists, false otherwise
     */
    public boolean hasIndex(String columnName) {
        return columnToIndex.containsKey(columnName);
    }
    
    /**
     * Inserts a value into all applicable indexes.
     * 
     * @param rowId Row identifier
     * @param values Map of column names to values
     */
    public void insert(long rowId, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String colName = entry.getKey();
            Object val = entry.getValue();
            
            if (val != null && hasIndex(colName)) {
                BTree index = getIndex(colName);
                if (index != null) {
                    try {
                        index.insert((Comparable<?>) val, rowId);
                    } catch (Exception e) {
                        // Log warning but don't fail the operation
                        System.err.println("Warning: Failed to insert into index " + 
                                         colName + ": " + e.getMessage());
                    }
                }
            }
        }
    }
    
    /**
     * Updates index entries for a modified row.
     * Optimized to avoid unnecessary delete+insert when value hasn't changed.
     * 
     * @param rowId Row identifier
     * @param oldValues Previous column values
     * @param newValues New column values
     */
    public void update(long rowId, Map<String, Object> oldValues, Map<String, Object> newValues) {
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String colName = entry.getKey();
            Object newVal = entry.getValue();
            
            if (!hasIndex(colName)) {
                continue;
            }
            
            Object oldVal = oldValues != null ? oldValues.get(colName) : null;
            
            // Skip if value hasn't changed
            if (Objects.equals(oldVal, newVal)) {
                continue;
            }
            
            BTree index = getIndex(colName);
            if (index == null) {
                continue;
            }
            
            try {
                // Remove old value if it existed
                if (oldVal != null) {
                    index.delete((Comparable<?>) oldVal, rowId);
                }
                
                // Insert new value if not null
                if (newVal != null) {
                    index.insert((Comparable<?>) newVal, rowId);
                }
            } catch (Exception e) {
                System.err.println("Warning: Failed to update index " + colName + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Removes a row from all indexes.
     * 
     * @param rowId Row identifier
     * @param values Current column values
     */
    public void delete(long rowId, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String colName = entry.getKey();
            Object val = entry.getValue();
            
            if (val != null && hasIndex(colName)) {
                BTree index = getIndex(colName);
                if (index != null) {
                    try {
                        index.delete((Comparable<?>) val, rowId);
                    } catch (Exception e) {
                        System.err.println("Warning: Failed to delete from index " + 
                                         colName + ": " + e.getMessage());
                    }
                }
            }
        }
    }
    
    /**
     * Returns all indexed column names.
     * 
     * @return Set of column names that have indexes
     */
    public Set<String> getIndexedColumns() {
        return Collections.unmodifiableSet(columnToIndex.keySet());
    }
    
    /**
     * Returns all index names.
     * 
     * @return Collection of index names
     */
    public Collection<String> getAllIndexNames() {
        return Collections.unmodifiableCollection(indexes.values())
                          .stream()
                          .map(BTree::getName)
                          .toList();
    }
    
    /**
     * Clears all indexes (used during table truncation).
     */
    public void clear() {
        indexes.clear();
        columnToIndex.clear();
    }
    
    /**
     * Returns the number of indexes managed.
     * 
     * @return Number of indexes
     */
    public int getIndexCount() {
        return indexes.size();
    }
}
