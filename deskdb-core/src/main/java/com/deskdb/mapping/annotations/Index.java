package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a single index on a table.
 * Can be used within @Indexes or directly on an entity (though @Indexes is preferred for multiple).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Index {
    /**
     * The name of the index. If omitted, a default name will be generated.
     */
    String name() default "";

    /**
     * A comma-separated list of column names to include in the index.
     * Example: "lastName", "lastName, firstName"
     */
    String columnList();

    /**
     * Whether the index should be unique.
     */
    boolean unique() default false;
}
