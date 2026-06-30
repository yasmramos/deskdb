package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a join column for a relationship.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JoinColumn {
    /**
     * The name of the join column.
     */
    String name() default "";

    /**
     * The name of the referenced column in the target entity.
     */
    String referencedColumnName() default "";
}
