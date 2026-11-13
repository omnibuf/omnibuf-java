package com.omnibuf;

/**
 * Controls binary serialization options for OmniBuf encoders.
 *
 * @param deterministic  sorts map entries by canonical key order before writing
 * @param selfDescribing prepends Field #0 {@link SchemaMeta} at the root packet
 */
public record MarshalOptions(boolean deterministic, boolean selfDescribing) {

    public static final MarshalOptions DEFAULT = new MarshalOptions(false, false);
    public static final MarshalOptions DETERMINISTIC = new MarshalOptions(true, false);
    public static final MarshalOptions SELF_DESCRIBING = new MarshalOptions(false, true);
    public static final MarshalOptions DETERMINISTIC_SELF_DESCRIBING = new MarshalOptions(true, true);

    public static MarshalOptions of(boolean deterministic, boolean selfDescribing) {
        if (deterministic) {
            return selfDescribing ? DETERMINISTIC_SELF_DESCRIBING : DETERMINISTIC;
        }
        return selfDescribing ? SELF_DESCRIBING : DEFAULT;
    }

    public MarshalOptions withDeterministic(boolean value) {
        return of(value, this.selfDescribing);
    }

    public MarshalOptions withSelfDescribing(boolean value) {
        return of(this.deterministic, value);
    }
}
