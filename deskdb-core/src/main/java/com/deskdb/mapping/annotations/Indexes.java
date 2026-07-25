package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies multiple indexes for an entity.
 * Used at the class level to define composite or multiple indexes on a table.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Indexes {
    /**
     * An array of @Index annotations specifying the indexes to be created.
     */
    Index[] value();
}
