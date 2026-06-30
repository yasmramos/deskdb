package com.deskdb.mapping.annotations;

import java.lang.annotation.*;

/**
 * Specifies the primary key generation strategy.
 */
public @interface GeneratedValue {
    GenerationType strategy() default GenerationType.AUTO;
    String generator() default "";
}
