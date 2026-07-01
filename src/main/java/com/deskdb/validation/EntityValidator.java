package com.deskdb.validation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Validates entities using validation annotations.
 */
public class EntityValidator {
    
    /**
     * Validates an entity and returns all validation errors.
     * 
     * @param entity Entity to validate
     * @return List of validation error messages (empty if valid)
     * @throws IllegalAccessException if field access fails
     */
    public static List<String> validate(Object entity) throws IllegalAccessException {
        List<String> errors = new ArrayList<>();
        
        if (entity == null) {
            errors.add("Entity cannot be null");
            return errors;
        }
        
        Class<?> clazz = entity.getClass();
        
        // Check all fields
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(entity);
            
            // @NotNull validation
            if (field.isAnnotationPresent(NotNull.class)) {
                NotNull notNull = field.getAnnotation(NotNull.class);
                if (value == null) {
                    errors.add(notNull.message());
                }
            }
            
            // @Size validation (for Strings and Collections)
            if (field.isAnnotationPresent(Size.class)) {
                Size size = field.getAnnotation(Size.class);
                
                if (value instanceof String) {
                    int length = ((String) value).length();
                    if (length < size.min() || length > size.max()) {
                        errors.add(size.message());
                    }
                } else if (value instanceof Collection) {
                    int collectionSize = ((Collection<?>) value).size();
                    if (collectionSize < size.min() || collectionSize > size.max()) {
                        errors.add(size.message());
                    }
                }
            }
            
            // @Min validation (for numeric types)
            if (field.isAnnotationPresent(Min.class)) {
                Min min = field.getAnnotation(Min.class);
                if (value instanceof Number) {
                    long numValue = ((Number) value).longValue();
                    if (numValue < min.value()) {
                        errors.add(min.message().replace("{value}", String.valueOf(min.value())));
                    }
                }
            }
            
            // @Max validation (for numeric types)
            if (field.isAnnotationPresent(Max.class)) {
                Max max = field.getAnnotation(Max.class);
                if (value instanceof Number) {
                    long numValue = ((Number) value).longValue();
                    if (numValue > max.value()) {
                        errors.add(max.message().replace("{value}", String.valueOf(max.value())));
                    }
                }
            }
        }
        
        return errors;
    }
    
    /**
     * Validates an entity and throws exception if invalid.
     * 
     * @param entity Entity to validate
     * @throws ValidationException if validation fails
     * @throws IllegalAccessException if field access fails
     */
    public static void validateAndThrow(Object entity) throws ValidationException, IllegalAccessException {
        List<String> errors = validate(entity);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
