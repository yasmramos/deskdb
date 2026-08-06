package com.deskdb.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class for automatic partitioning by a specific column and interval.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Partitioned {
    /**
     * The column to partition by (e.g., "created_at").
     */
    String by();
    
    /**
     * The partition interval (e.g., "DAY", "MONTH", "YEAR").
     */
    String interval();
}
