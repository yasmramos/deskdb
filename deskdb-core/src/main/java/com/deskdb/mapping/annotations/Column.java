package com.deskdb.mapping.annotations;

import com.deskdb.core.DataType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies column details for a field.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {
    String name() default "";
    boolean nullable() default true;
    DataType type() default DataType.STRING;
}
