package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a many-to-many relationship between entities.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ManyToMany {
    /**
     * The entity class that is the target of the relationship.
     */
    Class<?> targetEntity() default void.class;

    /**
     * The join table used to store the relationship.
     */
    JoinTable joinTable() default @JoinTable;

    /**
     * The name of the field in the target entity that owns the relationship.
     */
    String mappedBy() default "";
}
