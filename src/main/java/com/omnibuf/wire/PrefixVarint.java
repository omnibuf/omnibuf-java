package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Prefix-Varint (TZCNT) and ZigZag codec using little-endian {@link VarHandle} views.
 *
 * <p>Layout determined by {@code Integer.numberOfTrailingZeros(b0 & 0xFF)}:
 * <ul>
 *   <li>{@code tz == 0} ({@code (b0 & 1) != 0}): 1 byte, 7 payload bits ({@code 0..127})</li>
 *   <li>{@code tz == 1} ({@code (b0 & 2) != 0}): 2 bytes LE, 14 payload bits ({@code 0..16383})</li>
 *   <li>{@code tz == 2} ({@code (b0 & 4) != 0}): 4 bytes LE, 29 payload bits ({@code 0..536870911})</li>
 *   <li>{@code b0 == 0} ({@code tz == 32}): 9 bytes ({@code 0x00} + 8-byte LE {@code uint64})</li>
 *   <li>{@code (b0 & 0x07) == 0 && b0 != 0} ({@code tz} in {@code 3..7}): reserved marker, rejected</li>
 * </ul>
 */
public final class PrefixVarint {

    public static final VarHandle SHORT_LE =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    public static final VarHandle INT_LE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    public static final VarHandle LONG_LE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    public static final long MAX_1BYTE = 127L;
    public static final long MAX_2BYTE = 16_383L;
    public static final long MAX_4BYTE = 536_870_911L;

    public record Decoded(long value, int bytesRead) {}

    private PrefixVarint() {}

    public static short getShortLE(byte[] buf, int pos) {
        return (short) SHORT_LE.get(buf, pos);
    }

    public static void setShortLE(byte[] buf, int pos, short v) {
        SHORT_LE.set(buf, pos, v);
    }

    public static int getIntLE(byte[] buf, int pos) {
        return (int) INT_LE.get(buf, pos);
    }

    public static void setIntLE(byte[] buf, int pos, int v) {
        INT_LE.set(buf, pos, v);
    }

    public static long getLongLE(byte[] buf, int pos) {
        return (long) LONG_LE.get(buf, pos);
    }

    public static void setLongLE(byte[] buf, int pos, long v) {
        LONG_LE.set(buf, pos, v);
    }

    /**
     * Returns the canonical encoded byte length (1, 2, 4, or 9) for unsigned 64-bit {@code v}.
     */
    public static int encodedLength(long v) {
        if (Long.compareUnsigned(v, MAX_1BYTE) <= 0) {
            return 1;
        }
        if (Long.compareUnsigned(v, MAX_2BYTE) <= 0) {
            return 2;
        }
        if (Long.compareUnsigned(v, MAX_4BYTE) <= 0) {
            return 4;
        }
        return 9;
    }

    /**
     * Writes unsigned 64-bit {@code v} as a canonical Prefix-Varint into {@code dst[pos..]}
     * and returns the updated offset.
     */
    public static int write(byte[] dst, int pos, long v) {
        if (Long.compareUnsigned(v, MAX_1BYTE) <= 0) {
            dst[pos] = (byte) ((v << 1) | 1L);
            return pos + 1;
        }
        if (Long.compareUnsigned(v, MAX_2BYTE) <= 0) {
            SHORT_LE.set(dst, pos, (short) ((v << 2) | 2L));
            return pos + 2;
        }
        if (Long.compareUnsigned(v, MAX_4BYTE) <= 0) {
            INT_LE.set(dst, pos, (int) ((v << 3) | 4L));
            return pos + 4;
        }
        dst[pos] = 0;
        LONG_LE.set(dst, pos + 1, v);
        return pos + 9;
    }

    /**
     * Writes an overlong 2-byte Prefix-Varint for {@code 0 <= v <= 16383}, used when
     * backpatching container lengths reserved as 2 bytes.
     */
    public static void writeOverlong2Byte(byte[] dst, int pos, int v) {
        SHORT_LE.set(dst, pos, (short) ((v << 2) | 2));
    }

    /**
     * Returns the Prefix-Varint byte width from its leading byte {@code b0 & 0xFF},
     * or throws {@link OmniBufException} if {@code (b0 & 0x07) == 0 && b0 != 0}.
     */
    public static int widthFromLeadingByte(int b0) {
        int unsigned = b0 & 0xFF;
        return switch (Integer.numberOfTrailingZeros(unsigned)) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            case 32 -> 9;
            default -> throw new OmniBufException(
                String.format("omnibuf: reserved prefix-varint marker 0x%02x", unsigned)
            );
        };
    }

    /**
     * Decodes a Prefix-Varint from {@code src[pos..limit]} and returns value + byte count.
     * Throws {@link OmniBufException} on EOF, truncation, or reserved marker bytes.
     */
    public static Decoded decode(byte[] src, int pos, int limit) {
        if (pos >= limit) {
            throw new OmniBufException("omnibuf: unexpected EOF reading prefix-varint");
        }
        int b0 = src[pos] & 0xFF;
        if ((b0 & 1) != 0) {
            return new Decoded(b0 >>> 1, 1);
        }
        if ((b0 & 2) != 0) {
            if (pos + 2 > limit) {
                throw new OmniBufException("omnibuf: truncated 2-byte prefix-varint");
            }
            long v = Short.toUnsignedLong((short) SHORT_LE.get(src, pos)) >>> 2;
            return new Decoded(v, 2);
        }
        if ((b0 & 4) != 0) {
            if (pos + 4 > limit) {
                throw new OmniBufException("omnibuf: truncated 4-byte prefix-varint");
            }
            long v = Integer.toUnsignedLong((int) INT_LE.get(src, pos)) >>> 3;
            return new Decoded(v, 4);
        }
        if (b0 == 0) {
            if (pos + 9 > limit) {
                throw new OmniBufException("omnibuf: truncated 9-byte prefix-varint");
            }
            long v = (long) LONG_LE.get(src, pos + 1);
            return new Decoded(v, 9);
        }
        throw new OmniBufException(
            String.format("omnibuf: reserved prefix-varint marker 0x%02x", b0)
        );
    }

    /**
     * Non-throwing probe used by low-level opaque scanners: writes {@code out[0] = value}
     * and returns bytes consumed (1, 2, 4, 9), or {@code 0} on truncation/reserved byte.
     */
    public static int tryDecode(byte[] src, int pos, int limit, long[] out) {
        if (pos >= limit) {
            return 0;
        }
        int b0 = src[pos] & 0xFF;
        if ((b0 & 1) != 0) {
            out[0] = b0 >>> 1;
            return 1;
        }
        if ((b0 & 2) != 0) {
            if (pos + 2 > limit) {
                return 0;
            }
            out[0] = Short.toUnsignedLong((short) SHORT_LE.get(src, pos)) >>> 2;
            return 2;
        }
        if ((b0 & 4) != 0) {
            if (pos + 4 > limit) {
                return 0;
            }
            out[0] = Integer.toUnsignedLong((int) INT_LE.get(src, pos)) >>> 3;
            return 4;
        }
        if (b0 == 0) {
            if (pos + 9 > limit) {
                return 0;
            }
            out[0] = (long) LONG_LE.get(src, pos + 1);
            return 9;
        }
        return 0;
    }

    /**
     * Zero-allocation varint skipper returning {@code pos + width}, or {@code -1} on EOF,
     * truncation, or reserved marker byte.
     */
    public static int skipVarint(byte[] src, int pos, int limit) {
        if (pos >= limit) {
            return -1;
        }
        int b0 = src[pos] & 0xFF;
        if ((b0 & 1) != 0) {
            return pos + 1;
        }
        if ((b0 & 2) != 0) {
            int next = pos + 2;
            return next <= limit ? next : -1;
        }
        if ((b0 & 4) != 0) {
            int next = pos + 4;
            return next <= limit ? next : -1;
        }
        if (b0 == 0) {
            int next = pos + 9;
            return next <= limit ? next : -1;
        }
        return -1;
    }

    /**
     * Zero-allocation varint value reader for opaque scalar accessors; returns {@code 0L} on
     * malformed/truncated input.
     */
    public static long decodeValueOrZero(byte[] src, int pos, int limit) {
        if (pos >= limit) {
            return 0L;
        }
        int b0 = src[pos] & 0xFF;
        if ((b0 & 1) != 0) {
            return b0 >>> 1;
        }
        if ((b0 & 2) != 0) {
            return pos + 2 <= limit ? (Short.toUnsignedLong((short) SHORT_LE.get(src, pos)) >>> 2) : 0L;
        }
        if ((b0 & 4) != 0) {
            return pos + 4 <= limit ? (Integer.toUnsignedLong((int) INT_LE.get(src, pos)) >>> 3) : 0L;
        }
        if (b0 == 0) {
            return pos + 9 <= limit ? (long) LONG_LE.get(src, pos + 1) : 0L;
        }
        return 0L;
    }

    /**
     * Zero-allocation length-prefixed slice probe. Decodes the Prefix-Varint byte length at
     * {@code src[pos..limit]}, verifies {@code byteLen >= minLen} and {@code bodyStart + byteLen <= limit},
     * and returns packed {@code (((long) bodyStart) << 32) | (bodyEnd & 0xFFFF_FFFFL)}, or {@code -1L}
     * on any framing/bounds error.
     */
    public static long tryReadLengthSlicePacked(byte[] src, int pos, int limit, int minLen) {
        if (pos >= limit) {
            return -1L;
        }
        int b0 = src[pos] & 0xFF;
        long byteLen;
        int bodyStart;
        if ((b0 & 1) != 0) {
            byteLen = b0 >>> 1;
            bodyStart = pos + 1;
        } else if ((b0 & 2) != 0) {
            if (pos + 2 > limit) {
                return -1L;
            }
            byteLen = Short.toUnsignedLong((short) SHORT_LE.get(src, pos)) >>> 2;
            bodyStart = pos + 2;
        } else if ((b0 & 4) != 0) {
            if (pos + 4 > limit) {
                return -1L;
            }
            byteLen = Integer.toUnsignedLong((int) INT_LE.get(src, pos)) >>> 3;
            bodyStart = pos + 4;
        } else if (b0 == 0) {
            if (pos + 9 > limit) {
                return -1L;
            }
            byteLen = (long) LONG_LE.get(src, pos + 1);
            bodyStart = pos + 9;
        } else {
            return -1L;
        }
        if (Long.compareUnsigned(byteLen, minLen) < 0
                || Long.compareUnsigned(byteLen, limit - bodyStart) > 0) {
            return -1L;
        }
        int bodyEnd = bodyStart + (int) byteLen;
        return (((long) bodyStart) << 32) | (bodyEnd & 0xFFFF_FFFFL);
    }

    /**
     * Throwing counterpart to {@link #tryReadLengthSlicePacked(byte[], int, int, int)}.
     */
    public static long readLengthSlicePacked(byte[] src, int pos, int limit, int minLen) {
        long packed = tryReadLengthSlicePacked(src, pos, limit, minLen);
        if (packed < 0L) {
            // Preserve exact PrefixVarint error semantics on reserved/truncated varints
            Decoded d = decode(src, pos, limit);
            throw new OmniBufException(
                "omnibuf: length-prefixed payload (" + d.value() + ") out of bounds at " + pos
            );
        }
        return packed;
    }

    public static long encodeZigZag64(long n) {
        return (n << 1) ^ (n >> 63);
    }

    public static long encodeZigZag32(int n) {
        long wide = n;
        return (wide << 1) ^ (wide >> 63);
    }

    public static long decodeZigZag64(long u) {
        return (u >>> 1) ^ -(u & 1L);
    }

    public static int decodeZigZag32(long u) {
        return (int) ((u >>> 1) ^ -(u & 1L));
    }
}
