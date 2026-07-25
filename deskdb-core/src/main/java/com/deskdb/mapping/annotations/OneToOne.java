package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a one-to-one relationship.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneToOne {
    /**
     * The field that owns the relationship.
     */
    String mappedBy() default "";
    
    /**
     * Whether to cascade persist operations.
     */
    boolean cascade() default true;
    
    /**
     * The target entity class.
     */
    Class<?> targetEntity() default void.class;
    
    /**
     * The join column(s) for this relationship.
     */
    JoinColumn[] joinColumn() default {};
}
