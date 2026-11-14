package com.omnibuf.wire;

import com.omnibuf.UnmarshalOptions;

/**
 * Re-entrant thread-local pool for {@link OmniBufEncoder} and {@link OmniBufDecoder} instances.
 *
 * <p>Uses {@code get() + set(null)} on checkout and {@code set(instance)} on release so nested or
 * re-entrant codec invocations on the same thread never corrupt an active buffer or cursor.
 * Oversized encoder buffers exceeding {@link #MAX_POOLED_ENCODER_CAPACITY} are trimmed on release.
 */
public final class CodecPool {

    public static final int INITIAL_ENCODER_CAPACITY = 512;
    public static final int MAX_POOLED_ENCODER_CAPACITY = 65_536;

    private static final ThreadLocal<OmniBufEncoder> ENCODER_SLOT = new ThreadLocal<>();
    private static final ThreadLocal<OmniBufDecoder> DECODER_SLOT = new ThreadLocal<>();

    private CodecPool() {}

    public static OmniBufEncoder acquireEncoder() {
        OmniBufEncoder enc = ENCODER_SLOT.get();
        if (enc != null) {
            ENCODER_SLOT.set(null);
            enc.reset();
            return enc;
        }
        return new OmniBufEncoder(INITIAL_ENCODER_CAPACITY);
    }

    public static void releaseEncoder(OmniBufEncoder enc) {
        if (enc != null) {
            enc.resetAndTrim(MAX_POOLED_ENCODER_CAPACITY, INITIAL_ENCODER_CAPACITY);
            ENCODER_SLOT.set(enc);
        }
    }

    public static OmniBufDecoder acquireDecoder(byte[] buf, int offset, int length) {
        return acquireDecoder(buf, offset, length, UnmarshalOptions.DEFAULT);
    }

    public static OmniBufDecoder acquireDecoder(
            byte[] buf, int offset, int length, UnmarshalOptions options) {
        OmniBufDecoder dec = DECODER_SLOT.get();
        if (dec != null) {
            DECODER_SLOT.set(null);
        } else {
            dec = new OmniBufDecoder();
        }
        try {
            dec.reset(buf, offset, length, options);
            return dec;
        } catch (RuntimeException e) {
            dec.clearBuffer();
            DECODER_SLOT.set(dec);
            throw e;
        }
    }

    public static void releaseDecoder(OmniBufDecoder dec) {
        if (dec != null) {
            dec.clearBuffer();
            DECODER_SLOT.set(dec);
        }
    }
}
