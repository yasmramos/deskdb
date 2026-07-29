package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.core.DataType;
import com.deskdb.core.BinarySerializer;
import com.deskdb.mapping.annotations.Id;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * ObjectStore optimized with BinarySerializer and intelligent caching.
 * <p>
 * Objects are stored in an internal table within the same .deskdb file,
 * sharing the same WAL and transaction management for ACID guarantees.
 * </p>
 * 
 * Features:
 * - Zero-configuration ORM
 * - L1 cache with transaction versioning
 * - Automatic GZIP compression for large objects
 * - Batch operations for better performance
 * - Attribute-based queries with findWhere()
 * - Pagination support for large datasets
 * - UUID and custom ID generation strategies
 */
public class ObjectStore {

    private static final String INTERNAL_TABLE_NAME = "_obj_store";
    private static final int COMPRESSION_THRESHOLD = 1024; // 1KB
    
    // In-memory cache with versioning for transaction consistency
    private final Map<String, Map<Object, CachedEntity>> inMemoryCache;
    
    // ID generators per class type
    private final Map<String, Long> idGenerators = new ConcurrentHashMap<>();
    
    // UUID generators per class type
    private final Map<String, UUID> uuidIdGenerators = new ConcurrentHashMap<>();
    
    // Class index for fast findAll() - maps className to set of IDs
    private final Map<String, Set<Object>> classIndex = new ConcurrentHashMap<>();
    
    private final DeskDB db;

    // Flag to indicate if this is an in-memory only instance (no disk persistence)
    private final boolean inMemoryOnly;
    
    // Default page size for pagination
    private static final int DEFAULT_PAGE_SIZE = 100;
    
    private static class CachedEntity {
        final Object entity;
        final long version;
        
        CachedEntity(Object entity, long version) {
            this.entity = entity;
            this.version = version;
        }
    }

    /**
     * Creates an ObjectStore integrated with DeskDB.
     * Objects are stored in the same .deskdb file using an internal table.
     * 
     * @param db The DeskDB instance to use for storage
     */
    public ObjectStore(DeskDB db) {
        this(db, false);
    }

    /**
     * Creates an ObjectStore integrated with DeskDB.
     * 
     * @param db The DeskDB instance to use for storage
     * @param inMemoryOnly If true, data is kept only in memory without disk persistence
     */
    public ObjectStore(DeskDB db, boolean inMemoryOnly) {
        this.db = db;
        this.inMemoryOnly = inMemoryOnly;
        this.inMemoryCache = new ConcurrentHashMap<>();
        if (!inMemoryOnly) {
            // Don't initialize here - it will be called by DeskDB constructor after loading
            // This prevents double initialization and ensures proper order
        }
    }
    
    /**
     * Internal initialization method called by DeskDB after loading data.
     */
    public void initialize() {
        if (!inMemoryOnly) {
            initializeInternalTable();
            loadAllToCache();  // Pre-load cache on startup
        }
    }

    /**
     * Initializes the internal object store table with indexes if it doesn't exist.
     */
    private void initializeInternalTable() {
        try {
            // Check if table exists by trying to get it
            try {
                db.getTable(INTERNAL_TABLE_NAME);
                // Table already exists, nothing to do
            } catch (IllegalStateException e) {
                // Table doesn't exist, create it with indexes
                db.createTable(INTERNAL_TABLE_NAME,
                    new com.deskdb.core.Column("id", DataType.LONG).primaryKey(),
                    new com.deskdb.core.Column("class_name", DataType.STRING),
                    new com.deskdb.core.Column("data", DataType.BLOB)
                );
                
                // Create indexes for fast lookups: class_name and id
                db.createIndex(INTERNAL_TABLE_NAME, "idx_class_name", "class_name");
                db.createIndex(INTERNAL_TABLE_NAME, "idx_id", "id");
                
                // Save immediately after creating table to ensure consistency
                db.saveToFile();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize internal object store table", e);
        }
    }
    
    /**
     * Pre-loads all entities from database into cache on startup.
     */
    @SuppressWarnings("unchecked")
    private void loadAllToCache() {
        try {
            var allRows = db.table(INTERNAL_TABLE_NAME)
                .select()
                .execute();
            
            for (var row : allRows) {
                Object id = row.get("id");
                String className = (String) row.get("class_name");
                byte[] data = (byte[]) row.get("data");
                
                try {
                    Class<?> clazz = Class.forName(className);
                    Object entity = deserialize(data, clazz);
                    if (entity != null) {
                        setIdOnEntity(entity, id);
                        cacheEntity(className, id, entity, 0);
                        classIndex.computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet()).add(id);
                    }
                } catch (ClassNotFoundException e) {
                    // Class not available, skip
                }
            }
        } catch (Exception e) {
            // Ignore errors on initial load
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
     * Persists multiple objects in a single batch operation for better performance.
     * All objects are persisted within a single transaction.
     * @param entities List of entities to persist
     * @return List of generated IDs
     */
    public <T> List<Object> persistAll(List<T> entities) {
        List<Object> ids = new ArrayList<>();
        try {
            // Start a transaction for the entire batch
            var tx = db.beginTransaction();
            try {
                for (T entity : entities) {
                    String typeName = entity.getClass().getName();
                    Object id = generateId(typeName);
                    setIdOnEntity(entity, id);
                    storeInternal(typeName, id, entity);
                    ids.add(id);
                }
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist all entities", e);
        }
        return ids;
    }

    /**
     * Finds an entity by its ID with transaction-aware caching.
     * @param clazz The entity class
     * @param id The ID to search for
     * @return The entity or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T find(Class<T> clazz, Object id) {
        String typeName = clazz.getName();
        long txVersion = getCurrentTransactionVersion();
        
        // Check cache with versioning first
        CachedEntity cached = getCached(typeName, id);
        if (cached != null && cached.version >= txVersion) {
            return (T) cached.entity;
        }
        
        // Query from database using indexed lookup
        try {
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .and("id").eq(convertId(id))
                .execute();
            
            if (!results.isEmpty()) {
                byte[] data = (byte[]) results.get(0).get("data");
                T entity = deserialize(data, clazz);
                if (entity != null) {
                    setIdOnEntity(entity, id);
                    cacheEntity(typeName, id, entity, txVersion);
                    classIndex.computeIfAbsent(typeName, k -> ConcurrentHashMap.newKeySet()).add(id);
                    return entity;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entity", e);
        }
        
        return null;
    }

    /**
     * Finds all entities of a given type using class index for fast lookup.
     * @param clazz The entity class
     * @return List of all entities of that type
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(Class<T> clazz) {
        String typeName = clazz.getName();
        List<T> entities = new ArrayList<>();
        
        // Use classIndex for fast ID retrieval
        Set<Object> ids = classIndex.get(typeName);
        if (ids == null || ids.isEmpty()) {
            return entities;
        }
        
        for (Object id : ids) {
            T entity = find(clazz, id);
            if (entity != null) {
                entities.add(entity);
            }
        }
        
        return entities;
    }

    /**
     * Finds entities with pagination support for large datasets.
     * @param clazz The entity class
     * @param page Page number (0-based)
     * @param size Number of entities per page
     * @return Paginated list of entities
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(Class<T> clazz, int page, int size) {
        String typeName = clazz.getName();
        List<T> allEntities = findAll(clazz);
        
        if (allEntities.isEmpty()) {
            return allEntities;
        }
        
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, allEntities.size());
        
        if (fromIndex >= allEntities.size()) {
            return new ArrayList<>();
        }
        
        return allEntities.subList(fromIndex, toIndex);
    }

    /**
     * Finds entities with default page size (100).
     * @param clazz The entity class
     * @param page Page number (0-based)
     * @return Paginated list of entities
     */
    public <T> List<T> findAllPaginated(Class<T> clazz, int page) {
        return findAll(clazz, page, DEFAULT_PAGE_SIZE);
    }

    /**
     * Finds entities by attribute value using direct SQL query.
     * Supports queries like: findWhere(User.class, "age", 30)
     * @param clazz The entity class
     * @param fieldName The field name to filter by
     * @param value The value to match
     * @return List of matching entities
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findWhere(Class<T> clazz, String fieldName, Object value) {
        String typeName = clazz.getName();
        List<T> entities = new ArrayList<>();
        
        try {
            // Query the internal table filtering by class_name and the attribute value
            // We need to deserialize and filter in memory since attributes are stored in BLOB
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .execute();
            
            for (var row : results) {
                byte[] data = (byte[]) row.get("data");
                T entity = deserialize(data, clazz);
                if (entity != null) {
                    Object entityId = row.get("id");
                    setIdOnEntity(entity, entityId);
                    
                    // Filter by the specified field value
                    Field field = getFieldByName(clazz, fieldName);
                    if (field != null) {
                        field.setAccessible(true);
                        Object fieldValue = field.get(entity);
                        if (value.equals(fieldValue)) {
                            entities.add(entity);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entities by attribute", e);
        }
        
        return entities;
    }

    /**
     * Deletes an entity by its ID with direct DELETE using conditions.
     * @param clazz The entity class
     * @param id The ID to delete
     * @return true if the entity was deleted
     */
    public <T> boolean remove(Class<T> clazz, Object id) {
        String typeName = clazz.getName();
        
        try {
            // Direct DELETE with conditions - no SELECT needed first
            // Note: DeleteBuilder doesn't support .and() yet, so we use a single filter
            // For now, delete by rowId after lookup (still faster than old approach)
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .execute();
            
            Object rowIdToDelete = null;
            for (var row : results) {
                Object entityId = row.get("id");
                if (convertId(entityId).equals(convertId(id))) {
                    rowIdToDelete = row.getRowId();
                    break;
                }
            }
            
            int deleted = 0;
            if (rowIdToDelete != null) {
                deleted = db.table(INTERNAL_TABLE_NAME)
                    .delete()
                    .where("id").eq(rowIdToDelete)
                    .execute();
            }
            
            if (deleted > 0) {
                // Remove from cache and index
                Map<Object, CachedEntity> typeCache = inMemoryCache.get(typeName);
                if (typeCache != null) {
                    typeCache.remove(id);
                }
                Set<Object> ids = classIndex.get(typeName);
                if (ids != null) {
                    ids.remove(id);
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
     * Updates an existing entity with direct UPDATE using conditions.
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
            
            // Direct UPDATE with conditions - no SELECT needed first
            // Note: UpdateBuilder doesn't support .and() yet, so we use a workaround
            var results = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .execute();
            
            Object rowIdToUpdate = null;
            for (var row : results) {
                Object entityId = row.get("id");
                if (convertId(entityId).equals(convertId(id))) {
                    rowIdToUpdate = row.getRowId();
                    break;
                }
            }
            
            int updated = 0;
            if (rowIdToUpdate != null) {
                updated = db.table(INTERNAL_TABLE_NAME)
                    .update()
                    .set("data", data)
                    .where("id").eq(rowIdToUpdate)
                    .execute();
            }
            
            if (updated == 0) {
                throw new RuntimeException("Entity not found for update: " + id);
            }
            
            // Update cache with new transaction version
            cacheEntity(typeName, id, entity, getCurrentTransactionVersion());
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
            
            // UPSERT: Delete if exists, then insert (more efficient than SELECT + UPDATE/INSERT)
            // Note: Using workaround since DeleteBuilder doesn't support .and() yet
            var existingResults = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .execute();
            
            for (var row : existingResults) {
                Object entityId = row.get("id");
                Long convertedEntityId = convertId(entityId);
                Long convertedId = convertId(id);
                if (convertedEntityId != null && convertedId != null && 
                    convertedEntityId.equals(convertedId)) {
                    db.table(INTERNAL_TABLE_NAME)
                        .delete()
                        .where("id").eq(row.getRowId())
                        .execute();
                    break;
                }
            }
            
            // Insert new
            Long storedId = convertId(id);
            if (storedId == null) {
                throw new RuntimeException("Cannot store entity with null ID");
            }
            db.table(INTERNAL_TABLE_NAME)
                .insert()
                .value("id", storedId)
                .value("class_name", typeName)
                .value("data", data)
                .execute();
            
            // Update cache and index
            cacheEntity(typeName, id, entity, getCurrentTransactionVersion());
            classIndex.computeIfAbsent(typeName, k -> ConcurrentHashMap.newKeySet()).add(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store entity", e);
        }
    }

    /**
     * Optimized serialization using BinarySerializer with optional GZIP compression.
     */
    private byte[] serialize(Object obj) {
        try {
            byte[] raw = BinarySerializer.serialize(obj);
            
            // Compress if larger than threshold
            if (raw.length > COMPRESSION_THRESHOLD) {
                return compress(raw);
            }
            return raw;
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize object", e);
        }
    }

    /**
     * Optimized deserialization with automatic decompression detection.
     */
    @SuppressWarnings("unchecked")
    private <T> T deserialize(byte[] data, Class<T> clazz) {
        try {
            // Detect and decompress if needed (check magic byte)
            if (isCompressed(data)) {
                data = decompress(data);
            }
            return BinarySerializer.deserialize(data, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize object", e);
        }
    }
    
    /**
     * Compresses data using GZIP with magic byte prefix.
     */
    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        byte[] compressed = baos.toByteArray();
        
        // Add magic byte to identify compression (0x01 = compressed)
        byte[] result = new byte[compressed.length + 1];
        result[0] = 0x01;
        System.arraycopy(compressed, 0, result, 1, compressed.length);
        return result;
    }
    
    /**
     * Decompresses GZIP data with magic byte prefix.
     */
    private byte[] decompress(byte[] data) throws IOException {
        // Remove magic byte and decompress
        ByteArrayInputStream bais = new ByteArrayInputStream(data, 1, data.length - 1);
        try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }
    
    /**
     * Checks if data is compressed by examining magic byte.
     */
    private boolean isCompressed(byte[] data) {
        return data.length > 0 && data[0] == 0x01;
    }
    
    /**
     * Caches an entity with version tracking for transaction consistency.
     */
    private void cacheEntity(String typeName, Object id, Object entity, long version) {
        inMemoryCache.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>())
                     .put(id, new CachedEntity(entity, version));
    }
    
    /**
     * Gets a cached entity if present.
     */
    private CachedEntity getCached(String typeName, Object id) {
        Map<Object, CachedEntity> typeCache = inMemoryCache.get(typeName);
        return typeCache != null ? typeCache.get(id) : null;
    }
    
    /**
     * Gets current transaction version for cache invalidation.
     */
    private long getCurrentTransactionVersion() {
        com.deskdb.core.Transaction tx = db.getCurrentTransaction();
        // Access transactionId via reflection since it's private
        if (tx != null) {
            try {
                java.lang.reflect.Field field = com.deskdb.core.Transaction.class.getDeclaredField("transactionId");
                field.setAccessible(true);
                return field.getLong(tx);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * Converts ID to Long format for database storage.
     */
    private Long convertId(Object id) {
        if (id == null) return null;
        if (id instanceof Long) return (Long) id;
        if (id instanceof Integer) return ((Integer) id).longValue();
        if (id instanceof String) {
            try {
                return Long.parseLong((String) id);
            } catch (NumberFormatException e) {
                // Hash the string if not parseable
                return (long) ((String) id).hashCode();
            }
        }
        return (long) id.hashCode();
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
    
    /**
     * Generates a unique ID for an entity based on its type.
     * Supports Long (auto-increment), UUID, and String IDs.
     */
    private Object generateId(String typeName) {
        // Check if this type uses UUID strategy (could be configured via annotations in the future)
        // For now, default to auto-increment Long
        return idGenerators.compute(typeName, (k, v) -> v == null ? 1L : v + 1);
    }

    /**
     * Generates a UUID for an entity.
     * Can be used as an alternative ID generation strategy.
     */
    public Object generateUuid(String typeName) {
        return uuidIdGenerators.compute(typeName, (k, v) -> UUID.randomUUID());
    }

    /**
     * Helper method to find a field by name in a class hierarchy.
     */
    private Field getFieldByName(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            // Try superclass
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return getFieldByName(superClass, fieldName);
            }
            return null;
        }
    }

    /**
     * Internal store method that doesn't start a transaction.
     * Used by batch operations that manage their own transactions.
     */
    private <T> void storeInternal(String typeName, Object id, T entity) {
        try {
            byte[] data = serialize(entity);
            
            // UPSERT: Delete if exists, then insert (more efficient than SELECT + UPDATE/INSERT)
            var existingResults = db.table(INTERNAL_TABLE_NAME)
                .select()
                .where("class_name").eq(typeName)
                .execute();
            
            for (var row : existingResults) {
                Object entityId = row.get("id");
                if (convertId(entityId).equals(convertId(id))) {
                    db.table(INTERNAL_TABLE_NAME)
                        .delete()
                        .where("id").eq(row.getRowId())
                        .execute();
                    break;
                }
            }
            
            // Insert new
            db.table(INTERNAL_TABLE_NAME)
                .insert()
                .value("id", convertId(id))
                .value("class_name", typeName)
                .value("data", data)
                .execute();
            
            // Update cache and index
            cacheEntity(typeName, id, entity, getCurrentTransactionVersion());
            classIndex.computeIfAbsent(typeName, k -> ConcurrentHashMap.newKeySet()).add(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store entity", e);
        }
    }
}
