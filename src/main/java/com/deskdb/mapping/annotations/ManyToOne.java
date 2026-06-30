package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a many-to-one relationship.
 * The field should reference another Entity class.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ManyToOne {
    /**
     * The target entity class.
     */
    Class<?> targetEntity();

    /**
     * The name of the foreign key column in the current table.
     * Defaults to the field name + "_id".
     */
    String joinColumn() default "";
}
