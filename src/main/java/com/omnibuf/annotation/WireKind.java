package com.omnibuf.annotation;

/**
 * Explicit wire-type override for {@link OmniBufField} when a Java type maps to multiple
 * OmniBuf encodings (such as {@code int} as {@code uint32}, {@code sint32}, {@code fixed32},
 * or {@code sfixed32}).
 */
public enum WireKind {
    AUTO,
    UINT,
    SINT,
    BOOL,
    FIXED32,
    SFIXED32,
    FLOAT32,
    FIXED64,
    SFIXED64,
    FLOAT64,
    STRING,
    BYTES,
    SUB_MSG
}
