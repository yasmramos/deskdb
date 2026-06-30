package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a many-to-many relationship.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ManyToMany {
    /**
     * The field that owns the relationship.
     */
    String mappedBy() default "";
    
    /**
     * Whether to cascade persist operations.
     */
    boolean cascade() default true;
}
