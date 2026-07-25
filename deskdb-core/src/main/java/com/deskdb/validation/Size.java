package com.deskdb.validation;

import java.lang.annotation.*;

/**
 * Validates string length or collection size.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Size {
    
    /**
     * Minimum size allowed.
     * 
     * @return Minimum size
     */
    int min() default 0;
    
    /**
     * Maximum size allowed.
     * 
     * @return Maximum size
     */
    int max() default Integer.MAX_VALUE;
    
    /**
     * Custom error message for validation failure.
     * 
     * @return Error message
     */
    String message() default "Size constraint violation";
}
