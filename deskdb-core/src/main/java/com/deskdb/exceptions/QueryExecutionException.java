package com.deskdb.exceptions;

/**
 * Exception thrown when a query execution fails.
 * <p>
 * This exception is used to indicate errors that occur during the parsing,
 * planning, or execution of SQL-like queries in DeskDB.
 * </p>
 */
public class QueryExecutionException extends DeskDBException {
    
    /**
     * Creates a new QueryExecutionException with the specified detail message.
     * 
     * @param message the detail message
     */
    public QueryExecutionException(String message) {
        super(message);
    }
    
    /**
     * Creates a new QueryExecutionException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a new QueryExecutionException with the specified cause.
     * 
     * @param cause the cause of this exception
     */
    public QueryExecutionException(Throwable cause) {
        super(cause);
    }
}
