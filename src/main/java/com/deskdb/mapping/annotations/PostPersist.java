package com.deskdb.mapping.annotations;

import java.lang.annotation.*;

/**
 * Specifies callbacks for entity lifecycle events.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PostPersist {}
