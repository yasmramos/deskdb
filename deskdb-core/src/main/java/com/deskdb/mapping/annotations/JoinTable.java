package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a join table for many-to-many relationships.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JoinTable {
    /**
     * The name of the join table.
     */
    String name();
    
    /**
     * The join columns for the owning entity.
     */
    JoinColumn[] joinColumns() default {};
    
    /**
     * The inverse join columns for the related entity.
     */
    JoinColumn[] inverseJoinColumns() default {};
}
