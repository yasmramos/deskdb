package com.deskdb.validation;

import java.lang.annotation.*;

/**
 * Validates numeric minimum value.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Min {
    
    /**
     * Minimum value allowed.
     * 
     * @return Minimum value
     */
    long value();
    
    /**
     * Custom error message for validation failure.
     * 
     * @return Error message
     */
    String message() default "Value must be greater than or equal to {value}";
}
