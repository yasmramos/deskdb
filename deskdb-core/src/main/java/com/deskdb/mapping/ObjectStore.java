package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.core.DataType;
import com.deskdb.mapping.annotations.Id;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ObjectStore provides object persistence for Java objects integrated into DeskDB.
 * <p>
 * Objects are stored in an internal table within the same .deskdb file,
 * sharing the same WAL and transaction management for ACID guarantees.
 * </p>
 */
public class ObjectStore {

    private static final String INTERNAL_TABLE_NAME = "_obj_store";
    
    // In-memory cache for fast access
    private final Map<String, Map<Object, Object>> inMemoryCache;
    
    // ID generators per class type
    private final Map<String, Long> idGenerators = new ConcurrentHashMap<>();
    
    private final DeskDB db;

    /**
     * Creates an ObjectStore integrated with DeskDB.
     * Objects are stored in the same .deskdb file using an internal table.
     * 
     * @param db The DeskDB instance to use for storage
     */
    public ObjectStore(DeskDB db) {
        this.db = db;
        this.inMemoryCache = new ConcurrentHashMap<>();
        initializeInternalTable();
    }

    /**
     * Initializes the internal object store table if it doesn't exist.
     */
    private void initializeInternalTable() {
        try {
            // Check if table exists by trying to get it
            try {
                db.getTable(INTERNAL_TABLE_NAME);
                // Table already exists, nothing to do
            } catch (IllegalStateException e) {
                // Table doesn't exist, create it
                // Use BLOB for binary data storage
                db.createTable(INTERNAL_TABLE_NAME,
                    new com.deskdb.core.Column("id", DataType.LONG).primaryKey(),
                    new com.deskdb.core.Column("class_name", DataType.STRING),
                    new com.deskdb.core.Column("data", DataType.BLOB)
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize internal object store table", e);
        }
    }

    /**
     * Persists an object with an auto-generated ID.
     * @param entity The object to persist
     * @return The generated ID
     */
    public <T> Object persist(T entity) {
        String typeName = entity.getClass().getName();
        Object id = generateId(typeName);
        
        // Set the ID on the entity if it has an @Id field
        setIdOnEntity(entity, id);
        
        store(typeName, id, entity);
        return id;
    }

    /**
     * Persists an object with a specific ID.
     * @param entity The object to persist
     * @param id The ID to use
     */
    public <T> void persist(T entity, Object id) {
        String typeName = entity.getClass().getName();
        store(typeName, id, entity);
    }

    /**
     * Finds an entity by its ID.
     * @param clazz The entity class
     * @param id The ID to search for
     * @return The entity or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T find(Class<T> clazz, Object id) {
        String typeName = clazz.getName();
        
        // Check cache first
        Map<Object, Object> typeCache = inMemoryCache.get(typeName);
        if (typeCache != null && typeCache.containsKey(id)) {
            return (T) typeCache.get(id);
        }
        
        // Query from database
        try {
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("id").eq(id)
                .and("class_name").eq(typeName)
                .execute();
            
            if (!results.isEmpty()) {
                var row = results.get(0);
                byte[] data = (byte[]) row.get("data");
                T entity = deserialize(data, clazz);
                if (entity != null) {
                    setIdOnEntity(entity, id);
                    // Cache it
                    inMemoryCache.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>()).put(id, entity);
                    return entity;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entity", e);
        }
        
        return null;
    }

    /**
     * Finds all entities of a given type.
     * @param clazz The entity class
     * @return List of all entities of that type
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(Class<T> clazz) {
        String typeName = clazz.getName();
        List<T> entities = new ArrayList<>();
        
        try {
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .execute();
            
            for (var row : results) {
                Object id = row.get("id");
                byte[] data = (byte[]) row.get("data");
                T entity = deserialize(data, clazz);
                if (entity != null) {
                    setIdOnEntity(entity, id);
                    entities.add(entity);
                    // Cache it
                    inMemoryCache.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>()).put(id, entity);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find all entities", e);
        }
        
        return entities;
    }

    /**
     * Deletes an entity by its ID.
     * @param clazz The entity class
     * @param id The ID to delete
     * @return true if the entity was deleted
     */
    public <T> boolean remove(Class<T> clazz, Object id) {
        String typeName = clazz.getName();
        
        try {
            // First, find the row that matches both conditions
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("id").eq(id instanceof Integer ? ((Integer) id).longValue() : id)
                .and("class_name").eq(typeName)
                .execute();
            
            if (results.isEmpty()) {
                return false;
            }
            
            // Delete by row ID
            Object rowId = results.get(0).getRowId();
            int affected = db.table(INTERNAL_TABLE_NAME)
                .delete()
                .where("id").eq(rowId)
                .execute();
            
            if (affected > 0) {
                // Remove from cache
                Map<Object, Object> typeCache = inMemoryCache.get(typeName);
                if (typeCache != null) {
                    typeCache.remove(id);
                }
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove entity", e);
        }
        
        return false;
    }

    /**
     * Deletes an entity.
     * @param entity The entity to delete
     * @return true if the entity was deleted
     */
    public <T> boolean remove(T entity) {
        Class<?> clazz = entity.getClass();
        Object id = getIdFromEntity(entity);
        
        if (id == null) {
            return false;
        }
        
        return remove(clazz, id);
    }

    /**
     * Updates an existing entity.
     * @param entity The entity to update
     */
    public <T> void update(T entity) {
        Object id = getIdFromEntity(entity);
        if (id == null) {
            throw new IllegalArgumentException("Entity must have an ID to update");
        }
        
        String typeName = entity.getClass().getName();
        
        try {
            byte[] data = serialize(entity);
            
            // First find the row by both id and class_name
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("id").eq(id instanceof Integer ? ((Integer) id).longValue() : id)
                .and("class_name").eq(typeName)
                .execute();
            
            if (results.isEmpty()) {
                throw new RuntimeException("Entity not found for update");
            }
            
            // Update by row ID
            Object rowId = results.get(0).getRowId();
            db.table(INTERNAL_TABLE_NAME)
                .update()
                .set("data", data)
                .where("id").eq(rowId)
                .execute();
            
            // Update cache
            inMemoryCache.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>()).put(id, entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update entity", e);
        }
    }

    /**
     * Clears all stored data.
     */
    public void clear() {
        try {
            // Select all rows first
            var allRows = db.table(INTERNAL_TABLE_NAME)
                .select()
                .execute();
            
            // Delete each row by ID
            for (var row : allRows) {
                Object rowId = row.getRowId();
                db.table(INTERNAL_TABLE_NAME)
                    .delete()
                    .where("id").eq(rowId)
                    .execute();
            }
            
            inMemoryCache.clear();
            idGenerators.clear();
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear object store", e);
        }
    }

    /**
     * Clears all data for a specific type.
     * @param clazz The entity class
     */
    public <T> void clear(Class<T> clazz) {
        String typeName = clazz.getName();
        try {
            db.table(INTERNAL_TABLE_NAME)
                .delete()
                .where("class_name").eq(typeName)
                .execute();
            
            inMemoryCache.remove(typeName);
            idGenerators.remove(typeName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear object store for type", e);
        }
    }
    
    /**
     * Closes the store and clears the cache.
     */
    public void close() {
        inMemoryCache.clear();
        idGenerators.clear();
    }

    // Private helper methods

    private <T> void store(String typeName, Object id, T entity) {
        try {
            byte[] data = serialize(entity);
            
            // Check if entity with this ID already exists
            var existing = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("id").eq(id instanceof Integer ? ((Integer) id).longValue() : id)
                .and("class_name").eq(typeName)
                .execute();
            
            if (!existing.isEmpty()) {
                // Update existing - get row ID first
                Object rowId = existing.get(0).getRowId();
                db.table(INTERNAL_TABLE_NAME)
                    .update()
                    .set("data", data)
                    .where("id").eq(rowId)
                    .execute();
            } else {
                // Insert new
                db.table(INTERNAL_TABLE_NAME)
                    .insert()
                    .value("id", id instanceof Integer ? ((Integer) id).longValue() : id)
                    .value("class_name", typeName)
                    .value("data", data)
                    .execute();
            }
            
            // Update cache
            inMemoryCache.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>()).put(id, entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store entity", e);
        }
    }

    private byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize object", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(byte[] data, Class<T> clazz) {
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
             java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais)) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize object", e);
        }
    }

    private Object generateId(String typeName) {
        return idGenerators.compute(typeName, (k, v) -> v == null ? 1L : v + 1);
    }

    private <T> void setIdOnEntity(T entity, Object id) {
        try {
            Field idField = getIdField(entity.getClass());
            if (idField != null) {
                idField.setAccessible(true);
                
                // Convert Long to Integer if needed
                if (id instanceof Long && (idField.getType() == Integer.class || idField.getType() == int.class)) {
                    id = ((Long) id).intValue();
                }
                
                idField.set(entity, id);
            }
        } catch (IllegalAccessException e) {
            // Ignore if we can't set the ID
        }
    }

    private Object getIdFromEntity(Object entity) {
        try {
            Field idField = getIdField(entity.getClass());
            if (idField != null) {
                idField.setAccessible(true);
                return idField.get(entity);
            }
        } catch (IllegalAccessException e) {
            // Ignore
        }
        return null;
    }

    private Field getIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
        }
        // If no @Id annotation, look for a field named "id"
        try {
            return clazz.getDeclaredField("id");
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
