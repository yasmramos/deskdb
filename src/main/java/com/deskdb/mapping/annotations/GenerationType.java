package com.deskdb.mapping.annotations;

/**
 * Primary key generation strategies.
 */
public enum GenerationType {
    /**
     * The persistence provider chooses an appropriate strategy for the particular database.
     */
    AUTO,
    
    /**
     * Uses a database sequence to generate primary keys.
     */
    SEQUENCE,
    
    /**
     * Uses a database identity column to generate primary keys.
     */
    IDENTITY,
    
    /**
     * Uses a table to generate primary keys.
     */
    TABLE
}
