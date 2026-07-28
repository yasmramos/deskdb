package com.deskdb.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class for automatic auditing.
 * When applied, every change to entities of this class will be recorded with:
 * - User who made the change
 * - Timestamp of the change
 * - Old and new values
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
}
