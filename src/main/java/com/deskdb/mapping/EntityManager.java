package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.core.Row;
import com.deskdb.mapping.annotations.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;

/**
 * EntityManager provides ORM-like functionality for mapping Java objects to database rows.
 * Supports JPA-style annotations including relationships, lifecycle callbacks, and queries.
 */
public class EntityManager {

    private final DeskDB db;

    public EntityManager(DeskDB db) {
        this.db = db;
    }

    /**
     * Persists an entity to the database.
     */
    public <T> void persist(T entity) {
        Class<?> clazz = entity.getClass();
        validateEntity(clazz);

        // Execute @PrePersist callbacks
        executeLifecycleCallback(entity, PrePersist.class);

        String tableName = getTableName(clazz);
        Field idField = getIdField(clazz);
        idField.setAccessible(true);

        try {
            // Auto-generate ID if null
            Object idValue = idField.get(entity);
            if (idValue == null) {
                idValue = generateNextId(tableName, idField);
                idField.set(entity, idValue);
            }

            // Handle relationships before persisting
            handleRelationshipsBeforePersist(entity);

            Map<String, Object> values = extractFieldValues(entity, false);

            db.table(tableName).insert().insert(values).execute();

            // Handle join table inserts for @ManyToMany after main entity is persisted
            handleJoinTableInsert(entity);

            // Create indexes if defined on the entity
            createIndexesIfDefined(clazz);

            // Execute @PostPersist callbacks
            executeLifecycleCallback(entity, PostPersist.class);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access ID field", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist entity", e);
        }
    }

    /**
     * Merges an entity state into the database (update or insert).
     */
    public <T> T merge(T entity) {
        Class<?> clazz = entity.getClass();
        validateEntity(clazz);

        Field idField = getIdField(clazz);
        idField.setAccessible(true);

        try {
            Object idValue = idField.get(entity);
            if (idValue == null) {
                persist(entity);
                return entity;
            }

            // Check if entity exists
            T existing = find(clazz, idValue);
            if (existing == null) {
                persist(entity);
                return entity;
            }

            // Execute @PreUpdate callbacks
            executeLifecycleCallback(entity, PreUpdate.class);

            // Update existing entity
            String tableName = getTableName(clazz);
            Map<String, Object> values = extractFieldValues(entity, true);

            String idColumnName = getColumnName(idField);
            db.table(tableName)
                    .update()
                    .set(values)
                    .where(idColumnName)
                    .eq(idValue)
                    .execute();

            // Execute @PostUpdate callbacks
            executeLifecycleCallback(entity, PostUpdate.class);

            return entity;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access ID field", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to merge entity", e);
        }
    }

    /**
     * Finds an entity by its ID.
     */
    public <T> T find(Class<T> clazz, Object id) {
        validateEntity(clazz);

        String tableName = getTableName(clazz);
        Field idField = getIdField(clazz);
        String idColumnName = getColumnName(idField);

        List<Row> rows;
        try {
            rows = db.table(tableName)
                    .select()
                    .where(idColumnName)
                    .eq(id)
                    .execute();
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entity", e);
        }

        if (rows.isEmpty()) {
            return null;
        }

        T entity = mapToEntity(clazz, rows.get(0));
        
        // Load relationships
        loadRelationships(entity, clazz);
        
        return entity;
    }

    /**
     * Finds all entities of a given type.
     */
    public <T> List<T> findAll(Class<T> clazz) {
        validateEntity(clazz);

        String tableName = getTableName(clazz);
        List<Row> rows;
        try {
            rows = db.table(tableName).select().execute();
        } catch (Exception e) {
            throw new RuntimeException("Failed to find all entities", e);
        }

        List<T> entities = new ArrayList<>();
        for (Row row : rows) {
            T entity = mapToEntity(clazz, row);
            loadRelationships(entity, clazz);
            entities.add(entity);
        }

        return entities;
    }

    /**
     * Executes a custom query.
     */
    public <T> List<T> createQuery(String sql, Class<T> clazz, Object... params) {
        try {
            List<Row> rows = db.executeQuery(sql, params);
            List<T> entities = new ArrayList<>();
            for (Row row : rows) {
                entities.add(mapToEntity(clazz, row));
            }
            return entities;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute query", e);
        }
    }

    /**
     * Deletes an entity from the database.
     */
    public <T> void remove(T entity) {
        Class<?> clazz = entity.getClass();
        validateEntity(clazz);

        // Execute @PreRemove callbacks
        executeLifecycleCallback(entity, PreRemove.class);

        String tableName = getTableName(clazz);
        Field idField = getIdField(clazz);
        String idColumnName = getColumnName(idField);

        try {
            idField.setAccessible(true);
            Object idValue = idField.get(entity);

            db.table(tableName)
                    .delete()
                    .where(idColumnName)
                    .eq(idValue)
                    .execute();

            // Execute @PostRemove callbacks
            executeLifecycleCallback(entity, PostRemove.class);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access ID field", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove entity", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T mapToEntity(Class<T> clazz, Row row) {
        try {
            T entity = clazz.getDeclaredConstructor().newInstance();

            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Transient.class)) {
                    continue;
                }

                if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Column.class)) {
                    field.setAccessible(true);
                    String columnName = getColumnName(field);
                    Object value = row.get(columnName);

                    if (value != null) {
                        // Handle temporal types
                        if (field.isAnnotationPresent(Temporal.class)) {
                            value = convertTemporal(value, field);
                        }
                        // Handle enumerated types
                        if (field.isAnnotationPresent(Enumerated.class)) {
                            value = convertEnum(value, field);
                        }
                        field.set(entity, value);
                    }
                }
            }

            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row to entity", e);
        }
    }

    private void loadRelationships(Object entity, Class<?> clazz) {
        try {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);

                // Handle @ManyToOne and @OneToOne
                if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                    String fkColumnName = joinColumn != null && !joinColumn.name().isEmpty() 
                            ? joinColumn.name() 
                            : field.getName() + "_id";

                    Object fkValue = getFieldValue(entity, field.getName());
                    if (fkValue != null) {
                        Class<?> relatedClass = field.getType();
                        Object relatedEntity = find(relatedClass, fkValue);
                        field.set(entity, relatedEntity);
                    }
                }

                // Handle @OneToMany
                if (field.isAnnotationPresent(OneToMany.class)) {
                    ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
                    if (manyToMany != null) {
                        // Handled separately
                        continue;
                    }
                    
                    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                    String fkColumnName = joinColumn != null && !joinColumn.name().isEmpty()
                            ? joinColumn.name()
                            : clazz.getSimpleName().toLowerCase() + "_id";

                    Class<?> relatedClass = getGenericClass(field);
                    String relatedTableName = getTableName(relatedClass);
                    
                    List<Row> rows = db.table(relatedTableName)
                            .select()
                            .where(fkColumnName)
                            .eq(getIdValue(entity))
                            .execute();
                    
                    List<Object> relatedEntities = new ArrayList<>();
                    for (Row row : rows) {
                        relatedEntities.add(mapToEntity(relatedClass, row));
                    }
                    
                    field.set(entity, relatedEntities);
                }

                // Handle @ManyToMany
                if (field.isAnnotationPresent(ManyToMany.class)) {
                    JoinTable joinTable = field.getAnnotation(JoinTable.class);
                    if (joinTable == null) {
                        continue;
                    }

                    String joinTableName = joinTable.name();
                    String joinColumnName = joinTable.joinColumnName();
                    String inverseJoinColumnName = joinTable.inverseJoinColumnName();

                    Object idValue = getIdValue(entity);
                    
                    // Query join table
                    List<Row> joinRows = db.table(joinTableName)
                            .select()
                            .where(joinColumnName)
                            .eq(idValue)
                            .execute();

                    Class<?> relatedClass = getGenericClass(field);
                    List<Object> relatedEntities = new ArrayList<>();
                    
                    for (Row joinRow : joinRows) {
                        Object relatedId = joinRow.get(inverseJoinColumnName);
                        if (relatedId != null) {
                            Object relatedEntity = find(relatedClass, relatedId);
                            if (relatedEntity != null) {
                                relatedEntities.add(relatedEntity);
                            }
                        }
                    }
                    
                    field.set(entity, relatedEntities);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load relationships", e);
        }
    }

    private void handleRelationshipsBeforePersist(Object entity) throws Exception {
        Class<?> clazz = entity.getClass();
        
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Object relatedEntity = field.get(entity);
            
            if (relatedEntity == null) {
                continue;
            }

            // Handle @ManyToOne and @OneToOne with cascade
            if ((field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class))) {
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                
                boolean cascade = (manyToOne != null && manyToOne.cascade()) || 
                                 (oneToOne != null && oneToOne.cascade());
                
                if (cascade && isNewEntity(relatedEntity)) {
                    persist(relatedEntity);
                    
                    // Update foreign key
                    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                    String fkColumnName = joinColumn != null && !joinColumn.name().isEmpty()
                            ? joinColumn.name()
                            : field.getName() + "_id";
                    
                    Field idField = getIdField(relatedEntity.getClass());
                    idField.setAccessible(true);
                    Object relatedId = idField.get(relatedEntity);
                    
                    // Set FK in parent entity
                    String setterName = "set" + capitalize(field.getName());
                    try {
                        Method setter = clazz.getMethod(setterName, field.getType());
                        setter.invoke(entity, relatedId);
                    } catch (NoSuchMethodException e) {
                        // Setter not found, will be handled by field extraction
                    }
                }
            }

            // Handle @ManyToMany with cascade
            if (field.isAnnotationPresent(ManyToMany.class)) {
                ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
                if (!manyToMany.cascade()) {
                    continue;
                }

                JoinTable joinTable = field.getAnnotation(JoinTable.class);
                if (joinTable == null) {
                    continue;
                }

                Class<?> relatedClass = field.getType().getTypeParameters().length > 0 
                        ? getGenericClass(field) 
                        : (Class<?>) ((java.lang.reflect.ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];

                if (relatedEntity instanceof Collection) {
                    Collection<?> relatedCollection = (Collection<?>) relatedEntity;
                    for (Object item : relatedCollection) {
                        if (isNewEntity(item)) {
                            persist(item);
                        }
                    }

                    // Insert into join table after persisting main entity
                    // This will be handled after main entity is persisted
                }
            }
        }
    }

    private void handleJoinTableInsert(Object entity) throws Exception {
        Class<?> clazz = entity.getClass();
        Object entityId = getIdValue(entity);

        for (Field field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(ManyToMany.class)) {
                continue;
            }

            field.setAccessible(true);
            ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
            JoinTable joinTable = field.getAnnotation(JoinTable.class);

            if (joinTable == null || !manyToMany.cascade()) {
                continue;
            }

            Object relatedEntity = field.get(entity);
            if (!(relatedEntity instanceof Collection)) {
                continue;
            }

            Collection<?> relatedCollection = (Collection<?>) relatedEntity;
            String joinTableName = joinTable.name();
            String joinColumnName = joinTable.joinColumnName();
            String inverseJoinColumnName = joinTable.inverseJoinColumnName();

            // Create join table if not exists
            createJoinTableIfNotExists(joinTableName, joinColumnName, inverseJoinColumnName);

            for (Object item : relatedCollection) {
                Object relatedId = getIdValue(item);
                Map<String, Object> joinValues = new HashMap<>();
                joinValues.put(joinColumnName, entityId);
                joinValues.put(inverseJoinColumnName, relatedId);

                try {
                    db.table(joinTableName).insert().insert(joinValues).execute();
                } catch (Exception e) {
                    // Ignore duplicate key errors
                }
            }
        }
    }

    private void createJoinTableIfNotExists(String joinTableName, String joinColumnName, String inverseJoinColumnName) {
        try {
            db.table(joinTableName);
        } catch (Exception e) {
            // Table doesn't exist, create it
            try {
                db.table(joinTableName)
                        .create()
                        .column(joinColumnName, com.deskdb.core.DataType.INT)
                        .column(inverseJoinColumnName, com.deskdb.core.DataType.INT)
                        .execute();
            } catch (Exception ex) {
                // Ignore if table already exists
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Class<?> getGenericClass(Field field) {
        java.lang.reflect.Type genericType = field.getGenericType();
        if (genericType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType parameterizedType = (java.lang.reflect.ParameterizedType) genericType;
            java.lang.reflect.Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments.length > 0) {
                return (Class<?>) actualTypeArguments[0];
            }
        }
        return field.getType();
    }

    private Object getIdValue(Object entity) throws IllegalAccessException {
        Field idField = getIdField(entity.getClass());
        idField.setAccessible(true);
        return idField.get(entity);
    }

    private Object getFieldValue(Object entity, String fieldName) throws IllegalAccessException {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(entity);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private boolean isNewEntity(Object entity) {
        try {
            Field idField = getIdField(entity.getClass());
            idField.setAccessible(true);
            return idField.get(entity) == null;
        } catch (Exception e) {
            return true;
        }
    }

    private Object generateNextId(String tableName, Field idField) {
        try {
            List<Row> rows = db.table(tableName).select().execute();
            int maxId = 0;
            String idColumnName = getColumnName(idField);
            
            for (Row row : rows) {
                Object idValue = row.get(idColumnName);
                if (idValue instanceof Number) {
                    int intValue = ((Number) idValue).intValue();
                    if (intValue > maxId) {
                        maxId = intValue;
                    }
                }
            }
            return maxId + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private void createIndexesIfDefined(Class<?> clazz) throws Exception {
        Indexes indexesAnnotation = clazz.getAnnotation(Indexes.class);
        if (indexesAnnotation != null) {
            for (Index index : indexesAnnotation.value()) {
                createIndex(clazz, index);
            }
        }
        
        // Also support single @Index annotation directly on class (if used without @Indexes wrapper)
        Index singleIndex = clazz.getAnnotation(Index.class);
        if (singleIndex != null) {
            createIndex(clazz, singleIndex);
        }
    }

    private void createIndex(Class<?> clazz, Index index) throws Exception {
        String tableName = getTableName(clazz);
        String indexName = index.name();
        if (indexName == null || indexName.isEmpty()) {
            indexName = "idx_" + tableName + "_" + index.columnList().replace(",", "_").replace(" ", "");
        }
        
        String columnList = index.columnList();
        boolean unique = index.unique();
        
        ((com.deskdb.core.DeskDB) db).createIndexInternal(tableName, indexName, columnList, unique);
    }

    private Map<String, Object> extractFieldValues(Object entity, boolean includeId) throws IllegalAccessException {
        Map<String, Object> values = new HashMap<>();
        Class<?> clazz = entity.getClass();

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }

            if (!includeId && field.isAnnotationPresent(Id.class)) {
                continue;
            }

            if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Column.class) ||
                field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                
                field.setAccessible(true);
                Object value = field.get(entity);
                
                // Skip relationship fields, only store FK
                if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    if (value != null) {
                        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                        String fkColumnName = joinColumn != null && !joinColumn.name().isEmpty()
                                ? joinColumn.name()
                                : field.getName() + "_id";
                        
                        Field relatedIdField = getIdField(value.getClass());
                        relatedIdField.setAccessible(true);
                        Object relatedId = relatedIdField.get(value);
                        values.put(fkColumnName, relatedId);
                    }
                } else {
                    String columnName = getColumnName(field);
                    values.put(columnName, value);
                }
            }
        }

        return values;
    }

    private Object convertTemporal(Object value, Field field) {
        // Handle temporal conversion based on annotation
        Temporal temporal = field.getAnnotation(Temporal.class);
        if (temporal == null) {
            return value;
        }
        
        switch (temporal.value()) {
            case DATE:
                if (value instanceof java.sql.Timestamp) {
                    return new java.sql.Date(((java.sql.Timestamp) value).getTime());
                }
                break;
            case TIME:
                if (value instanceof java.sql.Timestamp) {
                    return new java.sql.Time(((java.sql.Timestamp) value).getTime());
                }
                break;
            case TIMESTAMP:
            default:
                // Keep as is
                break;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Object convertEnum(Object value, Field field) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated == null || !(value instanceof Number)) {
            return value;
        }

        Class<?> fieldType = field.getType();
        if (fieldType.isEnum()) {
            switch (enumerated.value()) {
                case ORDINAL:
                    return fieldType.getEnumConstants()[((Number) value).intValue()];
                case STRING:
                default:
                    // Already handled by string storage
                    break;
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private <A extends Annotation> void executeLifecycleCallback(Object entity, Class<A> annotationType) {
        Class<?> clazz = entity.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotationType)) {
                method.setAccessible(true);
                try {
                    method.invoke(entity);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to execute lifecycle callback: " + method.getName(), e);
                }
            }
        }
    }

    private void validateEntity(Class<?> clazz) {
        if (!clazz.isAnnotationPresent(Entity.class)) {
            throw new IllegalArgumentException("Class " + clazz.getName() + " is not annotated with @Entity");
        }
    }

    private String getTableName(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Table.class)) {
            return clazz.getAnnotation(Table.class).name();
        }
        return clazz.getSimpleName().toLowerCase();
    }

    private Field getIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
        }
        throw new IllegalArgumentException("No @Id field found in " + clazz.getName());
    }

    private String getColumnName(Field field) {
        if (field.isAnnotationPresent(Column.class)) {
            String name = field.getAnnotation(Column.class).name();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return field.getName();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
