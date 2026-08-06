package com.deskdb.exceptions;

/**
 * Exception thrown when storage operations fail.
 * <p>
 * This exception is used to indicate errors in the storage layer,
 * including file I/O errors, corruption detection, or resource exhaustion.
 * </p>
 */
public class StorageException extends DeskDBException {
    
    /**
     * Creates a new StorageException with the specified detail message.
     * 
     * @param message the detail message
     */
    public StorageException(String message) {
        super(message);
    }
    
    /**
     * Creates a new StorageException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a new StorageException with the specified cause.
     * 
     * @param cause the cause of this exception
     */
    public StorageException(Throwable cause) {
        super(cause);
    }
}
