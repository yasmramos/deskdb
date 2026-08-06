package com.deskdb.exceptions;

/**
 * Base exception for all DeskDB-specific exceptions.
 * <p>
 * This abstract class serves as the root of the DeskDB exception hierarchy,
 * providing a common type for catching all database-related exceptions.
 * </p>
 */
public abstract class DeskDBException extends RuntimeException {
    
    /**
     * Creates a new DeskDBException with the specified detail message.
     * 
     * @param message the detail message
     */
    public DeskDBException(String message) {
        super(message);
    }
    
    /**
     * Creates a new DeskDBException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public DeskDBException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a new DeskDBException with the specified cause.
     * 
     * @param cause the cause of this exception
     */
    public DeskDBException(Throwable cause) {
        super(cause);
    }
}
