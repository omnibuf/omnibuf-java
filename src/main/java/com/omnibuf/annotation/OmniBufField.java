package com.omnibuf.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a Java field or {@code record} component to an OmniBuf field number and wire encoding.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface OmniBufField {
    /** Positive OmniBuf field number (1..536870911). */
    int id();

    /**
     * Optional wire/schema field name emitted into Field #0 {@code SchemaMeta.fields}.
     * Defaults to the snake_case form of the Java field/component name when empty.
     */
    String name() default "";

    /** Wire encoding for scalar fields or {@code List<E>} elements. */
    WireKind wire() default WireKind.AUTO;

    /** Wire encoding for {@code Map<K, V>} keys. */
    WireKind keyWire() default WireKind.AUTO;

    /** Wire encoding for {@code Map<K, V>} values. */
    WireKind valueWire() default WireKind.AUTO;
}
