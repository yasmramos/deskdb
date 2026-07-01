package com.deskdb.validation;

import java.lang.annotation.*;

/**
 * Validates numeric maximum value.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Max {
    
    /**
     * Maximum value allowed.
     * 
     * @return Maximum value
     */
    long value();
    
    /**
     * Custom error message for validation failure.
     * 
     * @return Error message
     */
    String message() default "Value must be less than or equal to {value}";
}
