package com.deskdb.mapping.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.TemporalType;

/**
 * Specifies the temporal type for date/time fields.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Temporal {
    /**
     * The temporal type.
     */
    TemporalType value() default TemporalType.TIMESTAMP;
}
