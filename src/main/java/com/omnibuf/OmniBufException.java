package com.omnibuf;

/**
 * Unchecked exception thrown when an OmniBuf packet or value violates
 * wire-format, UTF-8, recursion, or schema constraints.
 */
public final class OmniBufException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OmniBufException(String message) {
        super(message);
    }

    public OmniBufException(String message, Throwable cause) {
        super(message, cause);
    }
}
