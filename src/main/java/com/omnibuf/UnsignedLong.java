package com.omnibuf;

import java.math.BigInteger;

/**
 * Boxed unsigned 64-bit integer value used by {@link OmniBufBlind} so full
 * {@code uint64} and {@code fixed64} values up to {@code 18446744073709551615}
 * preserve exact unsigned semantics in Java and JSON output.
 */
public final class UnsignedLong extends Number implements Comparable<UnsignedLong> {
    private static final long serialVersionUID = 1L;

    public static final UnsignedLong ZERO = new UnsignedLong(0L);
    public static final UnsignedLong MAX_VALUE = new UnsignedLong(-1L);

    private final long bits;

    private UnsignedLong(long bits) {
        this.bits = bits;
    }

    public static UnsignedLong fromLongBits(long bits) {
        if (bits == 0L) {
            return ZERO;
        }
        if (bits == -1L) {
            return MAX_VALUE;
        }
        return new UnsignedLong(bits);
    }

    public static UnsignedLong parseUnsigned(String decimal) {
        return fromLongBits(Long.parseUnsignedLong(decimal));
    }

    public long longBits() {
        return bits;
    }

    public BigInteger bigIntegerValue() {
        if (bits >= 0L) {
            return BigInteger.valueOf(bits);
        }
        byte[] mag = new byte[] {
            (byte) (bits >>> 56),
            (byte) (bits >>> 48),
            (byte) (bits >>> 40),
            (byte) (bits >>> 32),
            (byte) (bits >>> 24),
            (byte) (bits >>> 16),
            (byte) (bits >>> 8),
            (byte) bits
        };
        return new BigInteger(1, mag);
    }

    @Override
    public int intValue() {
        return (int) bits;
    }

    @Override
    public long longValue() {
        return bits;
    }

    @Override
    public float floatValue() {
        return bigIntegerValue().floatValue();
    }

    @Override
    public double doubleValue() {
        return bigIntegerValue().doubleValue();
    }

    @Override
    public int compareTo(UnsignedLong other) {
        return Long.compareUnsigned(this.bits, other.bits);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof UnsignedLong other && this.bits == other.bits;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bits);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(bits);
    }
}
