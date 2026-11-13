package com.omnibuf.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java class or Java 21 {@code record} as an OmniBuf serializable message type.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OmniBufMessage {
    /**
     * Optional schema type name emitted into Field #0 {@code SchemaMeta.type_name}.
     * Defaults to {@link Class#getSimpleName()} when empty.
     */
    String name() default "";
}
