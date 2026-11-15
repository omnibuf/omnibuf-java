package com.omnibuf.wire;

import com.omnibuf.UnmarshalOptions;

/**
 * Re-entrant thread-local pool for {@link OmniBufEncoder} and {@link OmniBufDecoder} instances.
 *
 * <p>Uses a lightweight per-thread {@link Slot} with {@code pooledInUse} flags on the pooled
 * instances so top-level checkout requires a single {@link ThreadLocal#get()} with zero
 * {@link ThreadLocal#set(Object)} table mutations, and release requires zero thread-local lookups.
 * Re-entrant calls on the same thread transparently allocate a temporary non-pooled instance.
 */
public final class CodecPool {

    public static final int INITIAL_ENCODER_CAPACITY = 512;
    public static final int MAX_POOLED_ENCODER_CAPACITY = 65_536;

    private static final class Slot {
        final OmniBufEncoder encoder = new OmniBufEncoder(INITIAL_ENCODER_CAPACITY, true);
        final OmniBufDecoder decoder = new OmniBufDecoder(true);
    }

    private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

    private CodecPool() {}

    public static OmniBufEncoder acquireEncoder() {
        OmniBufEncoder enc = SLOT.get().encoder;
        if (!enc.pooledInUse) {
            enc.pooledInUse = true;
            enc.reset();
            return enc;
        }
        return new OmniBufEncoder(INITIAL_ENCODER_CAPACITY);
    }

    public static void releaseEncoder(OmniBufEncoder enc) {
        if (enc != null && enc.isPooledSlot) {
            enc.resetAndTrim(MAX_POOLED_ENCODER_CAPACITY, INITIAL_ENCODER_CAPACITY);
            enc.pooledInUse = false;
        }
    }

    public static OmniBufDecoder acquireDecoder(byte[] buf, int offset, int length) {
        return acquireDecoder(buf, offset, length, UnmarshalOptions.DEFAULT);
    }

    public static OmniBufDecoder acquireDecoder(
            byte[] buf, int offset, int length, UnmarshalOptions options) {
        OmniBufDecoder dec = SLOT.get().decoder;
        boolean pooled = false;
        if (!dec.pooledInUse) {
            dec.pooledInUse = true;
            pooled = true;
        } else {
            dec = new OmniBufDecoder();
        }
        try {
            dec.reset(buf, offset, length, options);
            return dec;
        } catch (RuntimeException e) {
            dec.clearBuffer();
            if (pooled) {
                dec.pooledInUse = false;
            }
            throw e;
        }
    }

    public static void releaseDecoder(OmniBufDecoder dec) {
        if (dec != null && dec.isPooledSlot) {
            dec.clearBuffer();
            dec.pooledInUse = false;
        }
    }
}
