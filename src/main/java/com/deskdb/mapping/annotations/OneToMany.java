package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a one-to-many relationship.
 * The field should be a Collection of another Entity class.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneToMany {
    /**
     * The target entity class.
     */
    Class<?> targetEntity();

    /**
     * The name of the foreign key column in the target table.
     * Defaults to the source entity's table name + "_id".
     */
    String mappedBy() default "";
}
