package com.deskdb.core;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.deskdb.index.BTree;

/**
 * Manages database metadata including schemas, tables, and indexes.
 * Extracted from DeskDB to reduce coupling and improve testability.
 */
public class CatalogManager {
    
    private final Map<String, TableSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, Table> tables = new ConcurrentHashMap<>();
    private final Map<String, Map<String, BTree<?, ?>>> indexes = new ConcurrentHashMap<>(); // tableName -> indexName -> BTree
    
    private final ReadWriteLock catalogLock = new ReentrantReadWriteLock();
    
    /**
     * Registers a table schema.
     * @param schema the table schema to register
     * @return true if registered successfully, false if already exists
     */
    public boolean registerSchema(TableSchema schema) {
        catalogLock.writeLock().lock();
        try {
            return schemas.putIfAbsent(schema.getName(), schema) == null;
        } finally {
            catalogLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets a table schema by name.
     * @param tableName the table name
     * @return the table schema or null if not found
     */
    public TableSchema getSchema(String tableName) {
        return schemas.get(tableName);
    }
    
    /**
     * Checks if a schema exists.
     * @param tableName the table name
     * @return true if schema exists
     */
    public boolean hasSchema(String tableName) {
        return schemas.containsKey(tableName);
    }
    
    /**
     * Registers a table instance.
     * @param table the table to register
     */
    public void registerTable(Table table) {
        catalogLock.writeLock().lock();
        try {
            tables.put(table.getName(), table);
        } finally {
            catalogLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets a table by name.
     * @param tableName the table name
     * @return the table or null if not found
     */
    public Table getTable(String tableName) {
        return tables.get(tableName);
    }
    
    /**
     * Checks if a table exists.
     * @param tableName the table name
     * @return true if table exists
     */
    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName);
    }
    
    /**
     * Registers an index map for a table.
     * @param tableName the table name
     * @param indexMap the index map
     */
    public void registerIndex(String tableName, Map<String, BTree<?, ?>> indexMap) {
        catalogLock.writeLock().lock();
        try {
            indexes.put(tableName, indexMap);
        } finally {
            catalogLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets an index map by table name.
     * @param tableName the table name
     * @return the index map or null if not found
     */
    public Map<String, BTree<?, ?>> getIndex(String tableName) {
        return indexes.get(tableName);
    }
    
    /**
     * Gets all registered schemas.
     * @return unmodifiable collection of schemas
     */
    public Collection<TableSchema> getAllSchemas() {
        return Collections.unmodifiableCollection(schemas.values());
    }
    
    /**
     * Gets all registered tables.
     * @return unmodifiable collection of tables
     */
    public Collection<Table> getAllTables() {
        return Collections.unmodifiableCollection(tables.values());
    }
    
    /**
     * Removes a table and its associated schema and indexes.
     * @param tableName the table name to remove
     */
    public void removeTable(String tableName) {
        catalogLock.writeLock().lock();
        try {
            schemas.remove(tableName);
            tables.remove(tableName);
            indexes.remove(tableName);
        } finally {
            catalogLock.writeLock().unlock();
        }
    }
}
