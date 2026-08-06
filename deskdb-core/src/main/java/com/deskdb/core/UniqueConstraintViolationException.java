package com.deskdb.core;

/**
 * Excepción lanzada cuando se viola una restricción de unicidad.
 */
public class UniqueConstraintViolationException extends RuntimeException {
    public UniqueConstraintViolationException(String message) {
        super(message);
    }
}
