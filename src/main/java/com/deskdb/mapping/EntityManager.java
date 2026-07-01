package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.core.Row;
import com.deskdb.mapping.annotations.*;
import com.deskdb.validation.EntityValidator;
import com.deskdb.validation.ValidationException;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EntityManager provides ORM-like functionality for mapping Java objects to database rows.
 */
public class EntityManager {

    private final DeskDB db;
    private final boolean autoValidate;

    public EntityManager(DeskDB db) {
        this.db = db;
        this.autoValidate = true; // Enable auto-validation by default
    }

    public EntityManager(DeskDB db, boolean autoValidate) {
        this.db = db;
        this.autoValidate = autoValidate;
    }

    /**
     * Persists an entity to the database. If the entity already exists (based on ID), it updates it.
     * Also handles ManyToOne, OneToMany, and ManyToMany relationships.
     * Automatically validates the entity before persisting if autoValidate is enabled.
     */
    public <T> void persist(T entity) {
        Class<?> clazz = entity.getClass();
        validateEntity(clazz);

        // Ensure table exists before persisting
        try {
            ensureTableExists(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create table for entity: " + clazz.getName(), e);
        }

        // Auto-validate entity before persisting
        if (autoValidate) {
            try {
                EntityValidator.validateAndThrow(entity);
            } catch (ValidationException e) {
                throw new RuntimeException("Validation failed: " + e.getErrors(), e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to validate entity", e);
            }
        }

        String tableName = getTableName(clazz);
        Field idField = getIdField(clazz);
        String idColumnName = getColumnName(idField);
        
        Map<String, Object> values = new HashMap<>();
        Object idValue = null;

        // Auto-generate ID FIRST if null, before processing relationships
        try {
            idField.setAccessible(true);
            idValue = idField.get(entity);
            
            if (idValue == null) {
                try {
                    Long generatedId = generateNextId(tableName, idColumnName);
                    // Convert to Integer if the field type is Integer
                    if (idField.getType() == Integer.class || idField.getType() == int.class) {
                        idValue = generatedId.intValue();
                    } else {
                        idValue = generatedId;
                    }
                    // Set the generated ID back to the entity immediately
                    idField.set(entity, idValue);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate ID", e);
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access or generate ID field", e);
        }

        for (Field field : clazz.getDeclaredFields()) {
            // Skip transient fields
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }

            // Handle ManyToOne relationships - store foreign key
            if (field.isAnnotationPresent(ManyToOne.class)) {
                field.setAccessible(true);
                try {
                    ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                    JoinColumn[] joinColumns = manyToOne.joinColumn();
                    String joinColumnName = (joinColumns == null || joinColumns.length == 0) ? 
                        field.getName() + "_id" : joinColumns[0].name();
                    
                    Object relatedEntity = field.get(entity);
                    if (relatedEntity != null) {
                        // Persist related entity if it's new
                        Field relatedIdField = getIdField(relatedEntity.getClass());
                        relatedIdField.setAccessible(true);
                        Object relatedIdValue = relatedIdField.get(relatedEntity);
                        
                        // Check if related entity needs to be persisted first
                        if (relatedIdValue == null) {
                            persist(relatedEntity);
                            relatedIdValue = relatedIdField.get(relatedEntity);
                        }
                        
                        values.put(joinColumnName, relatedIdValue);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access ManyToOne field: " + field.getName(), e);
                }
                continue;
            }

            // Handle ManyToMany relationships
            if (field.isAnnotationPresent(ManyToMany.class)) {
                field.setAccessible(true);
                try {
                    ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
                    JoinTable joinTable = field.getAnnotation(JoinTable.class);
                    
                    if (joinTable != null) {
                        String joinTableName = joinTable.name();
                        JoinColumn[] joinColumns = joinTable.joinColumns();
                        JoinColumn[] inverseJoinColumns = joinTable.inverseJoinColumns();
                        
                        // Get column names
                        String ownerColumnName = (joinColumns != null && joinColumns.length > 0) ?
                            joinColumns[0].name() : clazz.getSimpleName().toLowerCase() + "_id";
                        String inverseColumnName = (inverseJoinColumns != null && inverseJoinColumns.length > 0) ?
                            inverseJoinColumns[0].name() : field.getType().getSimpleName().replace("List", "").toLowerCase() + "_id";
                        
                        // Get related entities
                        Object relatedEntities = field.get(entity);
                        if (relatedEntities instanceof java.util.Collection) {
                            java.util.Collection<?> relatedCollection = (java.util.Collection<?>) relatedEntities;
                            
                            // Persist each related entity if needed and collect IDs
                            for (Object relatedEntity : relatedCollection) {
                                // Persist related entity if it's new
                                if (!isEntityPersisted(relatedEntity)) {
                                    persist(relatedEntity);
                                }
                                
                                // Get IDs
                                Field ownerIdField = getIdField(clazz);
                                ownerIdField.setAccessible(true);
                                Object ownerId = ownerIdField.get(entity);
                                
                                Field relatedIdField = getIdField(relatedEntity.getClass());
                                relatedIdField.setAccessible(true);
                                Object relatedId = relatedIdField.get(relatedEntity);
                                
                                // Create join table if not exists
                                createJoinTableIfNotExists(joinTableName, ownerColumnName, inverseColumnName, tableName, 
                                    getTableName(relatedEntity.getClass()));
                                
                                // Insert into join table
                                Map<String, Object> joinValues = new HashMap<>();
                                joinValues.put(ownerColumnName, ownerId);
                                joinValues.put(inverseColumnName, relatedId);
                                
                                // Check if relationship already exists
                                List<Row> existing = db.table(joinTableName)
                                    .select()
                                    .where(ownerColumnName).eq(ownerId)
                                    .execute();
                                
                                boolean exists = false;
                                for (Row row : existing) {
                                    Object inverseId = row.get(inverseColumnName);
                                    if (inverseId != null && inverseId.equals(relatedId)) {
                                        exists = true;
                                        break;
                                    }
                                }
                                
                                if (!exists) {
                                    db.table(joinTableName).insert().insert(joinValues).execute();
                                }
                            }
                        }
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access ManyToMany field: " + field.getName(), e);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to process ManyToMany relationship: " + field.getName(), e);
                }
                continue;
            }

            // Handle regular columns (including fields without @Column annotation)
            boolean isRelationship = field.isAnnotationPresent(ManyToOne.class) || 
                                    field.isAnnotationPresent(OneToOne.class) || 
                                    field.isAnnotationPresent(ManyToMany.class);
            
            if (!isRelationship && !field.isAnnotationPresent(Transient.class)) {
                field.setAccessible(true);
                try {
                    String columnName = null;
                    
                    // Determine column name
                    if (field.isAnnotationPresent(Column.class)) {
                        Column col = field.getAnnotation(Column.class);
                        columnName = col.name().isEmpty() ? field.getName() : col.name();
                    } else if (field.isAnnotationPresent(Id.class)) {
                        columnName = getColumnName(field);
                    } else {
                        // Basic fields without @Column are mapped by field name
                        columnName = field.getName();
                    }
                    
                    if (columnName != null) {
                        Object value = field.get(entity);
                        values.put(columnName, value);
                        
                        if (field.isAnnotationPresent(Id.class)) {
                            idValue = value;
                        }
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access field: " + field.getName(), e);
                }
            }
        }

        try {
            // Check if entity already exists
            List<Row> existing = db.table(tableName)
                    .select()
                    .where(idColumnName)
                    .eq(idValue)
                    .execute();
            
            if (!existing.isEmpty()) {
                // Update existing entity
                db.table(tableName)
                    .update()
                    .set(values)
                    .where(idColumnName)
                    .eq(idValue)
                    .execute();
            } else {
                // Insert new entity
                db.table(tableName).insert().insert(values).execute();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist entity", e);
        }
    }

    /**
     * Generates the next available ID for a table by finding the maximum existing ID and adding 1.
     */
    private Long generateNextId(String tableName, String idColumnName) throws Exception {
        List<Row> rows = db.table(tableName).select().execute();
        long maxId = 0;
        for (Row row : rows) {
            Object idValue = row.get(idColumnName);
            if (idValue instanceof Number) {
                long id = ((Number) idValue).longValue();
                if (id > maxId) {
                    maxId = id;
                }
            }
        }
        return maxId + 1;
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

        return mapToEntity(clazz, rows.get(0));
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
            entities.add(mapToEntity(clazz, row));
        }

        return entities;
    }

    /**
     * Deletes an entity from the database.
     */
    public <T> void remove(T entity) {
        Class<?> clazz = entity.getClass();
        validateEntity(clazz);

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
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access ID field", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove entity", e);
        }
    }

    /**
     * Executes a native SQL query and maps results to entities.
     * Note: This is a simplified implementation that currently ignores the SQL and parameters,
     * returning all entities of the given type. Full SQL parsing support is planned for future versions.
     */
    public <T> List<T> createQuery(String sql, Class<T> entityType, Object... params) {
        validateEntity(entityType);
        
        // For now, just return all records - full SQL parsing would be complex
        // TODO: Implement proper SQL parsing and parameter binding in future versions
        return findAll(entityType);
    }

    @SuppressWarnings("unchecked")
    private <T> T mapToEntity(Class<T> clazz, Row row) {
        try {
            T entity = clazz.getDeclaredConstructor().newInstance();

            for (Field field : clazz.getDeclaredFields()) {
                // Skip transient fields
                if (field.isAnnotationPresent(Transient.class)) {
                    continue;
                }

                // Handle ManyToOne relationships
                if (field.isAnnotationPresent(ManyToOne.class)) {
                    field.setAccessible(true);
                    ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                    JoinColumn[] joinColumns = manyToOne.joinColumn();
                    String joinColumnName = (joinColumns == null || joinColumns.length == 0) ? 
                        field.getName() + "_id" : joinColumns[0].name();
                    
                    Object foreignKeyId = row.get(joinColumnName);
                    if (foreignKeyId != null) {
                        Class<?> targetEntity = manyToOne.targetEntity();
                        EntityManager targetEm = new EntityManager(db);
                        Object relatedEntity = targetEm.find(targetEntity, foreignKeyId);
                        field.set(entity, relatedEntity);
                    }
                    continue;
                }

                // Handle OneToOne relationships (similar to ManyToOne)
                if (field.isAnnotationPresent(OneToOne.class)) {
                    field.setAccessible(true);
                    OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                    JoinColumn[] joinColumns = oneToOne.joinColumn();
                    String joinColumnName = (joinColumns == null || joinColumns.length == 0) ? 
                        field.getName() + "_id" : joinColumns[0].name();
                    
                    Object foreignKeyId = row.get(joinColumnName);
                    if (foreignKeyId != null) {
                        Class<?> targetEntity = oneToOne.targetEntity();
                        EntityManager targetEm = new EntityManager(db);
                        Object relatedEntity = targetEm.find(targetEntity, foreignKeyId);
                        field.set(entity, relatedEntity);
                    }
                    continue;
                }

                // Handle ManyToMany relationships
                if (field.isAnnotationPresent(ManyToMany.class)) {
                    field.setAccessible(true);
                    ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
                    
                    // Check if this is the inverse side (mappedBy)
                    String mappedBy = manyToMany.mappedBy();
                    if (mappedBy != null && !mappedBy.isEmpty()) {
                        // This is the inverse side - skip loading to avoid circular references
                        // The owning side will handle the relationship
                        field.set(entity, new ArrayList<>());
                        continue;
                    } else {
                        // This is the owning side - process normally
                        JoinTable joinTable = field.getAnnotation(JoinTable.class);
                        
                        if (joinTable != null && !joinTable.name().isEmpty()) {
                            String joinTableName = joinTable.name();
                            JoinColumn[] joinColumns = joinTable.joinColumns();
                            JoinColumn[] inverseJoinColumns = joinTable.inverseJoinColumns();
                            
                            String ownerColumnName = (joinColumns == null || joinColumns.length == 0) ? 
                                getTableName(clazz) + "_id" : joinColumns[0].name();
                            String inverseColumnName = (inverseJoinColumns == null || inverseJoinColumns.length == 0) ? 
                                field.getType().getSimpleName().replace("List", "").toLowerCase() + "_id" : inverseJoinColumns[0].name();
                            
                            // Get the ID of the current entity
                            Field idField = getIdField(clazz);
                            idField.setAccessible(true);
                            Object currentEntityId = idField.get(entity);
                            
                            if (currentEntityId != null) {
                                // Query the join table to get related IDs
                                List<Row> joinRows = db.table(joinTableName)
                                    .select()
                                    .where(ownerColumnName)
                                    .eq(currentEntityId)
                                    .execute();
                                
                                if (!joinRows.isEmpty()) {
                                    // Extract target entity class from generic type
                                    Class<?> targetEntity = null;
                                    if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType) {
                                        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) field.getGenericType();
                                        if (pt.getActualTypeArguments().length > 0) {
                                            targetEntity = (Class<?>) pt.getActualTypeArguments()[0];
                                        }
                                    }
                                    
                                    if (targetEntity == null) {
                                        targetEntity = field.getType();
                                    }
                                    
                                    List<Object> relatedEntities = new ArrayList<>();
                                    EntityManager targetEm = new EntityManager(db);
                                    
                                    for (Row joinRow : joinRows) {
                                        Object relatedId = joinRow.get(inverseColumnName);
                                        if (relatedId != null) {
                                            Object relatedEntity = targetEm.find(targetEntity, relatedId);
                                            if (relatedEntity != null) {
                                                relatedEntities.add(relatedEntity);
                                            }
                                        }
                                    }
                                    
                                    field.set(entity, relatedEntities);
                                } else {
                                    // Set empty list if no relationships found
                                    field.set(entity, new ArrayList<>());
                                }
                            }
                        }
                    }
                    continue;
                }

                // Handle regular columns (Id, Column, or basic fields without annotations)
                boolean isRelationship = field.isAnnotationPresent(ManyToOne.class) || 
                                        field.isAnnotationPresent(OneToOne.class) || 
                                        field.isAnnotationPresent(ManyToMany.class);
                
                if (!isRelationship && !field.isAnnotationPresent(Transient.class)) {
                    field.setAccessible(true);
                    String columnName = null;
                    
                    // Determine column name
                    if (field.isAnnotationPresent(Column.class)) {
                        Column col = field.getAnnotation(Column.class);
                        columnName = col.name().isEmpty() ? field.getName() : col.name();
                    } else if (field.isAnnotationPresent(Id.class)) {
                        columnName = getColumnName(field);
                    } else {
                        // Basic fields without @Column are mapped by field name
                        columnName = field.getName();
                    }
                    
                    if (columnName != null) {
                        Object value = row.get(columnName);
                        if (value != null) {
                            field.set(entity, value);
                        }
                    }
                }
            }

            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row to entity", e);
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

    /**
     * Checks if an entity is already persisted by looking for its ID in the database.
     */
    private boolean isEntityPersisted(Object entity) throws Exception {
        Field idField = getIdField(entity.getClass());
        idField.setAccessible(true);
        Object idValue = idField.get(entity);
        
        if (idValue == null) {
            return false;
        }
        
        String tableName = getTableName(entity.getClass());
        String idColumnName = getColumnName(idField);
        
        List<Row> existing = db.table(tableName)
            .select()
            .where(idColumnName)
            .eq(idValue)
            .execute();
        
        return !existing.isEmpty();
    }

    /**
     * Creates a join table if it doesn't exist.
     */
    private void createJoinTableIfNotExists(String joinTableName, String ownerColumnName, String inverseColumnName, 
                                            String ownerTableName, String relatedTableName) throws Exception {
        // Check if table exists
        try {
            db.table(joinTableName).select().execute();
            // Table exists, nothing to do
            return;
        } catch (Exception e) {
            // Table doesn't exist, create it
        }
        
        // Create join table with two foreign key columns
        com.deskdb.core.Column col1 = new com.deskdb.core.Column(ownerColumnName, com.deskdb.core.DataType.INT);
        com.deskdb.core.Column col2 = new com.deskdb.core.Column(inverseColumnName, com.deskdb.core.DataType.INT);
        db.createTable(joinTableName, col1, col2);
    }

    /**
     * Ensures the entity table exists by creating it if necessary.
     */
    private void ensureTableExists(Class<?> clazz) throws Exception {
        String tableName = getTableName(clazz);
        
        // Check if table exists
        try {
            db.table(tableName).select().execute();
            // Table exists, nothing to do
            return;
        } catch (Exception e) {
            // Table doesn't exist, create it
        }
        
        // Build columns from entity fields
        List<com.deskdb.core.Column> columns = new ArrayList<>();
        Field idField = getIdField(clazz);
        String idColumnName = getColumnName(idField);
        columns.add(new com.deskdb.core.Column(idColumnName, com.deskdb.core.DataType.INT));
        
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            
            boolean isRelationship = field.isAnnotationPresent(ManyToOne.class) || 
                                    field.isAnnotationPresent(OneToOne.class) || 
                                    field.isAnnotationPresent(ManyToMany.class);
            
            if (!isRelationship && !field.isAnnotationPresent(Id.class)) {
                String columnName = field.isAnnotationPresent(Column.class) ? 
                    field.getAnnotation(Column.class).name() : field.getName();
                
                // Determine column type based on Java field type
                com.deskdb.core.DataType dataType = getDataTypeFromField(field.getType());
                if (dataType != null) {
                    columns.add(new com.deskdb.core.Column(columnName, dataType));
                }
            }
        }
        
        // Create table with all columns
        db.createTable(tableName, columns.toArray(new com.deskdb.core.Column[0]));
    }

    /**
     * Maps Java field types to DeskDB DataType.
     */
    private com.deskdb.core.DataType getDataTypeFromField(Class<?> fieldType) {
        if (fieldType == String.class) {
            return com.deskdb.core.DataType.STRING;
        } else if (fieldType == Integer.class || fieldType == int.class) {
            return com.deskdb.core.DataType.INT;
        } else if (fieldType == Long.class || fieldType == long.class) {
            return com.deskdb.core.DataType.LONG;
        } else if (fieldType == Double.class || fieldType == double.class) {
            return com.deskdb.core.DataType.DOUBLE;
        } else if (fieldType == Float.class || fieldType == float.class) {
            return com.deskdb.core.DataType.DOUBLE; // Float maps to DOUBLE in DeskDB
        } else if (fieldType == Boolean.class || fieldType == boolean.class) {
            return com.deskdb.core.DataType.BOOLEAN;
        }
        // For unsupported types, default to STRING
        return com.deskdb.core.DataType.STRING;
    }
}
