package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a join table for a many-to-many relationship.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JoinTable {
    /**
     * The name of the join table.
     */
    String name() default "";

    /**
     * The join column for the owning side of the relationship.
     */
    JoinColumn joinColumns() default @JoinColumn;

    /**
     * The inverse join column for the non-owning side of the relationship.
     */
    JoinColumn inverseJoinColumns() default @JoinColumn;
}
