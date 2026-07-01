package com.deskdb.validation;

import java.lang.annotation.*;

/**
 * Marks a field as required.
 * Validated before persisting entities.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotNull {
    
    /**
     * Custom error message for validation failure.
     * 
     * @return Error message
     */
    String message() default "Field cannot be null";
}
