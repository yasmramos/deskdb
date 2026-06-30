package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.core.Row;
import com.deskdb.mapping.annotations.*;

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

    public EntityManager(DeskDB db) {
        this.db = db;
    }

    /**
     * Persists an entity to the database. If the entity already exists (based on ID), it updates it.
     * Also handles ManyToOne relationships by storing foreign key values.
     */
    public <T> void persist(T entity) {
        Class<?> clazz = entity.getClass();
        validateEntity(clazz);

        String tableName = getTableName(clazz);
        Field idField = getIdField(clazz);
        String idColumnName = getColumnName(idField);
        
        Map<String, Object> values = new HashMap<>();
        Object idValue = null;

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
                    String joinColumn = manyToOne.joinColumn().isEmpty() ? 
                        field.getName() + "_id" : manyToOne.joinColumn();
                    
                    Object relatedEntity = field.get(entity);
                    if (relatedEntity != null) {
                        // Extract ID from related entity
                        Field relatedIdField = getIdField(relatedEntity.getClass());
                        relatedIdField.setAccessible(true);
                        Object relatedIdValue = relatedIdField.get(relatedEntity);
                        values.put(joinColumn, relatedIdValue);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access ManyToOne field: " + field.getName(), e);
                }
                continue;
            }

            // Handle regular columns
            if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                try {
                    String columnName = getColumnName(field);
                    Object value = field.get(entity);
                    values.put(columnName, value);
                    
                    if (field.isAnnotationPresent(Id.class)) {
                        idValue = value;
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
                    String joinColumn = manyToOne.joinColumn().isEmpty() ? 
                        field.getName() + "_id" : manyToOne.joinColumn();
                    
                    Object foreignKeyId = row.get(joinColumn);
                    if (foreignKeyId != null) {
                        Class<?> targetEntity = manyToOne.targetEntity();
                        EntityManager targetEm = new EntityManager(db);
                        Object relatedEntity = targetEm.find(targetEntity, foreignKeyId);
                        field.set(entity, relatedEntity);
                    }
                    continue;
                }

                // Handle regular columns
                if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Column.class)) {
                    field.setAccessible(true);
                    String columnName = getColumnName(field);
                    Object value = row.get(columnName);

                    if (value != null) {
                        field.set(entity, value);
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
}
