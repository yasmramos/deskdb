package com.deskdb.validation;

import java.util.List;

/**
 * Exception thrown when entity validation fails.
 */
public class ValidationException extends RuntimeException {
    
    private final List<String> errors;
    
    /**
     * Creates a new validation exception.
     * 
     * @param errors List of validation error messages
     */
    public ValidationException(List<String> errors) {
        super("Validation failed: " + String.join(", ", errors));
        this.errors = errors;
    }
    
    /**
     * Returns the list of validation errors.
     * 
     * @return Error messages
     */
    public List<String> getErrors() {
        return errors;
    }
}
