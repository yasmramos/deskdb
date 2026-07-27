package com.deskdb.mapping;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ObjectStore provides a simple in-memory object persistence mechanism using Java serialization.
 * It allows storing and retrieving Java objects without SQL functionality.
 * All data is kept in memory for maximum performance (no disk persistence).
 */
public class ObjectStore {

    private final Map<String, Map<Object, byte[]>> inMemoryStore;
    
    // ID generators per class type
    private final Map<String, Long> idGenerators = new ConcurrentHashMap<>();

    public ObjectStore() {
        this.inMemoryStore = new ConcurrentHashMap<>();
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
        return (T) retrieve(typeName, id);
    }

    /**
     * Finds all entities of a given type.
     * @param clazz The entity class
     * @return List of all entities of that type
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(Class<T> clazz) {
        String typeName = clazz.getName();
        Map<Object, byte[]> typeStore = inMemoryStore.get(typeName);
        
        if (typeStore == null || typeStore.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<T> entities = new ArrayList<>();
        for (Map.Entry<Object, byte[]> entry : typeStore.entrySet()) {
            T entity = deserialize(entry.getValue());
            if (entity != null) {
                // Set the ID on the entity
                setIdOnEntity(entity, entry.getKey());
                entities.add(entity);
            }
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
        Map<Object, byte[]> typeStore = inMemoryStore.get(typeName);
        
        if (typeStore == null) {
            return false;
        }
        
        return typeStore.remove(id) != null;
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
        persist(entity, id);
    }

    /**
     * Clears all stored data.
     */
    public void clear() {
        inMemoryStore.clear();
        idGenerators.clear();
    }

    /**
     * Clears all data for a specific type.
     * @param clazz The entity class
     */
    public <T> void clear(Class<T> clazz) {
        String typeName = clazz.getName();
        inMemoryStore.remove(typeName);
        idGenerators.remove(typeName);
    }

    // Private helper methods

    private <T> void store(String typeName, Object id, T entity) {
        byte[] data = serialize(entity);
        inMemoryStore.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>()).put(id, data);
    }

    private Object retrieve(String typeName, Object id) {
        Map<Object, byte[]> typeStore = inMemoryStore.get(typeName);
        if (typeStore == null) {
            return null;
        }
        byte[] data = typeStore.get(id);
        return data != null ? deserialize(data) : null;
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
    private <T> T deserialize(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
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
            java.lang.reflect.Field idField = getIdField(entity.getClass());
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
            java.lang.reflect.Field idField = getIdField(entity.getClass());
            if (idField != null) {
                idField.setAccessible(true);
                return idField.get(entity);
            }
        } catch (IllegalAccessException e) {
            // Ignore
        }
        return null;
    }

    private java.lang.reflect.Field getIdField(Class<?> clazz) {
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(com.deskdb.mapping.annotations.Id.class)) {
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
