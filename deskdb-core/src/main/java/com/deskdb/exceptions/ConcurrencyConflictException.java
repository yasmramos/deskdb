package com.deskdb.exceptions;

/**
 * Exception thrown when a concurrency conflict is detected during transaction processing.
 * <p>
 * This exception indicates that a transaction has encountered a conflict with another
 * concurrent transaction, typically in optimistic concurrency control scenarios.
 * The transaction should be retried to resolve the conflict.
 * </p>
 */
public class ConcurrencyConflictException extends DeskDBException {
    
    /**
     * Creates a new ConcurrencyConflictException with the specified detail message.
     * 
     * @param message the detail message
     */
    public ConcurrencyConflictException(String message) {
        super(message);
    }
    
    /**
     * Creates a new ConcurrencyConflictException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public ConcurrencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a new ConcurrencyConflictException with the specified cause.
     * 
     * @param cause the cause of this exception
     */
    public ConcurrencyConflictException(Throwable cause) {
        super(cause);
    }
}
