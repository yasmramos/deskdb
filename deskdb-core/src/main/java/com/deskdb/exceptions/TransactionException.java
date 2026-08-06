package com.deskdb.exceptions;

/**
 * Exception thrown when a transaction fails to complete successfully.
 * <p>
 * This exception indicates errors that occur during transaction processing,
 * including commit failures, rollback issues, or concurrency conflicts.
 * </p>
 */
public class TransactionException extends DeskDBException {
    
    /**
     * Creates a new TransactionException with the specified detail message.
     * 
     * @param message the detail message
     */
    public TransactionException(String message) {
        super(message);
    }
    
    /**
     * Creates a new TransactionException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a new TransactionException with the specified cause.
     * 
     * @param cause the cause of this exception
     */
    public TransactionException(Throwable cause) {
        super(cause);
    }
}
